// Package nodecache contains storage primitives for an optional node-local archive cache.
// It does not provide access control: callers must authorize requests before using a Store.
package nodecache

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

var (
	digestPattern = regexp.MustCompile(`^sha256:[0-9a-f]{64}$`)
	keyPattern    = regexp.MustCompile(`^[0-9a-f]{64}$`)
	ErrCapacity   = errors.New("node cache storage capacity exhausted")
)

// Store holds verified blobs in a private, service-owned directory. It is not a
// security boundary and must not be mounted by untrusted workloads.
type Store struct {
	dir      string
	maxBlob  int64
	maxTotal int64
	used     int64
	reserved int64
	mu       sync.Mutex
	inflight map[string]*flight
}

type flight struct {
	done chan struct{}
	path string
	err  error
}

// RepositoryScope identifies the registry authorization boundary for a blob.
type RepositoryScope struct {
	Registry, Repository string
}

// NewStore opens a private directory owned by exactly one store process.
// Limits count blob payload and in-progress fills, not filesystem metadata.
// This store does not evict entries; a full cache rejects new fills.
func NewStore(dir string, maxBlob, maxTotal int64) (*Store, error) {
	if dir == "" || maxBlob <= 0 || maxTotal < maxBlob {
		return nil, errors.New("cache directory and valid blob and total limits are required")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	info, err := os.Lstat(dir)
	if err != nil || !info.IsDir() || info.Mode().Perm()&0o077 != 0 {
		return nil, errors.New("cache directory must be a private directory")
	}
	s := &Store{dir: dir, maxBlob: maxBlob, maxTotal: maxTotal, inflight: make(map[string]*flight)}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			return nil, errors.New("cache contains an invalid entry")
		}
		if strings.HasPrefix(entry.Name(), ".fill-") {
			if err := os.Remove(filepath.Join(dir, entry.Name())); err != nil {
				return nil, errors.New("cannot remove interrupted cache fill")
			}
			continue
		}
		if !keyPattern.MatchString(entry.Name()) || info.Size() > maxTotal-s.used {
			return nil, errors.New("cache contains an invalid entry or exceeds its limit")
		}
		s.used += info.Size()
	}
	return s, nil
}

// Get returns a verified file for digest in scope, fetching it on a miss. Callers must
// independently authorize every request, including a hit. Concurrent misses in
// this process share one fill; cancellation of a waiter does not cancel it.
// open must honor ctx while opening and reading its response.
func (s *Store) Get(ctx context.Context, scope RepositoryScope, digest string, size int64, open func(context.Context) (io.ReadCloser, error)) (string, error) {
	if scope.Registry == "" || scope.Repository == "" || len(scope.Registry) > 255 || len(scope.Repository) > 1024 || strings.ContainsRune(scope.Registry+scope.Repository, '\x00') || !digestPattern.MatchString(digest) || size < 0 || size == math.MaxInt64 || size > s.maxBlob || open == nil {
		return "", errors.New("invalid digest, size, or source")
	}
	key := scopedKey(scope, digest)
	s.mu.Lock()
	if f := s.inflight[key]; f != nil {
		s.mu.Unlock()
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-f.done:
			if f.err != nil {
				return "", f.err
			}
			// Callers must not reuse another request's expected size unchecked.
			if err := verifyFile(f.path, digest, size); err != nil {
				return "", err
			}
			return f.path, nil
		}
	}
	f := &flight{done: make(chan struct{})}
	s.inflight[key] = f
	s.mu.Unlock()

	f.path, f.err = s.get(ctx, key, digest, size, open)
	s.mu.Lock()
	delete(s.inflight, key)
	close(f.done)
	s.mu.Unlock()
	return f.path, f.err
}

func scopedKey(scope RepositoryScope, digest string) string {
	h := sha256.Sum256([]byte(scope.Registry + "\x00" + scope.Repository + "\x00" + digest))
	return hex.EncodeToString(h[:])
}

func (s *Store) get(ctx context.Context, key, digest string, size int64, open func(context.Context) (io.ReadCloser, error)) (string, error) {
	path := filepath.Join(s.dir, key)
	info, statErr := os.Lstat(path)
	if statErr == nil {
		if !info.Mode().IsRegular() {
			return "", errors.New("cache entry is not a regular file")
		}
		if verifyFile(path, digest, info.Size()) == nil {
			if info.Size() != size {
				return "", errors.New("cache blob size differs from requested size")
			}
			return path, nil
		}
		// Keep the corrupt entry until safe removal is implemented. Unlinking a
		// file still held by a reader can consume disk after accounting frees it.
		return "", errors.New("cache blob failed verification")
	} else if !os.IsNotExist(statErr) {
		return "", statErr
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	s.mu.Lock()
	if size > s.maxTotal-s.used-s.reserved {
		s.mu.Unlock()
		return "", ErrCapacity
	}
	s.reserved += size
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		s.reserved -= size
		s.mu.Unlock()
	}()
	src, err := open(ctx)
	if err != nil {
		return "", err
	}
	defer src.Close()
	tmp, err := os.CreateTemp(s.dir, ".fill-*")
	if err != nil {
		return "", err
	}
	defer os.Remove(tmp.Name())
	h := sha256.New()
	n, copyErr := io.Copy(io.MultiWriter(tmp, h), io.LimitReader(src, size))
	if copyErr == nil && n == size {
		var extra [1]byte
		if count, err := io.ReadFull(src, extra[:]); count != 0 {
			copyErr = errors.New("cache fill exceeds declared size")
		} else if err != io.EOF {
			copyErr = err
		}
	}
	if closeErr := tmp.Close(); copyErr == nil {
		copyErr = closeErr
	}
	if copyErr != nil {
		return "", copyErr
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if n != size || "sha256:"+hex.EncodeToString(h.Sum(nil)) != digest {
		return "", errors.New("cache fill failed size or digest verification")
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		return "", fmt.Errorf("publish cache blob: %w", err)
	}
	s.mu.Lock()
	s.used += size
	s.mu.Unlock()
	return path, nil
}

func verifyFile(path, digest string, size int64) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() != size {
		return errors.New("cache blob has wrong type or size")
	}
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return err
	}
	if "sha256:"+hex.EncodeToString(h.Sum(nil)) != digest {
		return errors.New("cache blob digest mismatch")
	}
	return nil
}

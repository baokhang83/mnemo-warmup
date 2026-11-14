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
	"sync"
)

var digestPattern = regexp.MustCompile(`^sha256:[0-9a-f]{64}$`)

// Store holds verified blobs in a private, service-owned directory. It is not a
// security boundary and must not be mounted by untrusted workloads.
type Store struct {
	dir      string
	maxBlob  int64
	mu       sync.Mutex
	inflight map[string]*flight
}

type flight struct {
	done chan struct{}
	path string
	err  error
}

// NewStore opens a private directory. maxBlob limits the size of each fill;
// total storage limits and eviction are a separate responsibility.
func NewStore(dir string, maxBlob int64) (*Store, error) {
	if dir == "" || maxBlob <= 0 {
		return nil, errors.New("cache directory and positive blob limit are required")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	info, err := os.Lstat(dir)
	if err != nil || !info.IsDir() || info.Mode().Perm()&0o077 != 0 {
		return nil, errors.New("cache directory must be a private directory")
	}
	return &Store{dir: dir, maxBlob: maxBlob, inflight: make(map[string]*flight)}, nil
}

// Get returns a verified file for digest, fetching it on a miss. Callers must
// independently authorize every request, including a hit. Concurrent misses in
// this process share one fill; cancellation of a waiter does not cancel it.
// open must honor ctx while opening and reading its response.
func (s *Store) Get(ctx context.Context, digest string, size int64, open func(context.Context) (io.ReadCloser, error)) (string, error) {
	if !digestPattern.MatchString(digest) || size < 0 || size == math.MaxInt64 || size > s.maxBlob || open == nil {
		return "", errors.New("invalid digest, size, or source")
	}
	key := digest[7:]
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

func (s *Store) get(ctx context.Context, key, digest string, size int64, open func(context.Context) (io.ReadCloser, error)) (string, error) {
	path := filepath.Join(s.dir, key)
	if verifyFile(path, digest, size) == nil {
		return path, nil
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
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
	n, copyErr := io.Copy(io.MultiWriter(tmp, h), io.LimitReader(src, size+1))
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

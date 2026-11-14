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
	"time"
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
	entries  map[string]cacheEntry
	pins     map[string]int
}

type cacheEntry struct {
	size     int64
	lastUsed time.Time
}

type flight struct {
	done chan struct{}
	path string
	err  error
	hit  bool
}

// Lease pins an open blob until Close. It must not be copied.
type Lease struct {
	file  *os.File
	store *Store
	key   string
	once  sync.Once
	err   error
}

func (l *Lease) Read(p []byte) (int, error) { return l.file.Read(p) }

func (l *Lease) Close() error {
	l.once.Do(func() {
		l.err = l.file.Close()
		l.store.unpin(l.key)
	})
	return l.err
}

// RepositoryScope identifies the registry authorization boundary for a blob.
type RepositoryScope struct {
	Registry, Repository string
}

// NewStore opens a private directory owned by exactly one store process.
// Limits count blob payload and in-progress fills, not filesystem metadata.
// Unpinned least-recently-used entries are evicted to make room. A full cache
// with no evictable entries rejects new fills.
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
	s := &Store{dir: dir, maxBlob: maxBlob, maxTotal: maxTotal, inflight: make(map[string]*flight), entries: make(map[string]cacheEntry), pins: make(map[string]int)}
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
		s.entries[entry.Name()] = cacheEntry{size: info.Size(), lastUsed: info.ModTime()}
	}
	return s, nil
}

// Acquire returns a verified, pinned reader and its hit/miss/shared outcome.
// The caller must authorize every request and close the lease when finished.
// open must honor ctx while opening and reading its response.
func (s *Store) Acquire(ctx context.Context, scope RepositoryScope, digest string, size int64, open func(context.Context) (io.ReadCloser, error)) (*Lease, string, error) {
	if scope.Registry == "" || scope.Repository == "" || len(scope.Registry) > 255 || len(scope.Repository) > 1024 || strings.ContainsRune(scope.Registry+scope.Repository, '\x00') || !digestPattern.MatchString(digest) || size < 0 || size == math.MaxInt64 || size > s.maxBlob || open == nil {
		return nil, "", errors.New("invalid digest, size, or source")
	}
	if err := ctx.Err(); err != nil {
		return nil, "", err
	}
	key := scopedKey(scope, digest)
	s.mu.Lock()
	s.pins[key]++
	s.mu.Unlock()
	path, outcome, err := s.getWithOutcome(ctx, key, digest, size, open)
	if err != nil {
		s.unpin(key)
		return nil, "", err
	}
	f, err := os.Open(path)
	if err != nil {
		s.unpin(key)
		return nil, "", err
	}
	s.mu.Lock()
	entry, ok := s.entries[key]
	if ok {
		entry.lastUsed = time.Now()
		s.entries[key] = entry
	}
	s.mu.Unlock()
	if !ok {
		f.Close()
		s.unpin(key)
		return nil, "", errors.New("cache entry disappeared")
	}
	return &Lease{file: f, store: s, key: key}, outcome, nil
}

func (s *Store) unpin(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pins[key]--
	if s.pins[key] == 0 {
		delete(s.pins, key)
	}
}

func (s *Store) getWithOutcome(ctx context.Context, key, digest string, size int64, open func(context.Context) (io.ReadCloser, error)) (string, string, error) {
	s.mu.Lock()
	if f := s.inflight[key]; f != nil {
		s.mu.Unlock()
		select {
		case <-ctx.Done():
			return "", "", ctx.Err()
		case <-f.done:
			if f.err != nil {
				return "", "", f.err
			}
			// Callers must not reuse another request's expected size unchecked.
			if err := verifyFile(f.path, digest, size); err != nil {
				return "", "", err
			}
			outcome := "shared"
			if f.hit {
				outcome = "hit"
			}
			return f.path, outcome, nil
		}
	}
	f := &flight{done: make(chan struct{})}
	s.inflight[key] = f
	s.mu.Unlock()

	f.path, f.hit, f.err = s.get(ctx, key, digest, size, open)
	s.mu.Lock()
	delete(s.inflight, key)
	close(f.done)
	s.mu.Unlock()
	if f.err != nil {
		return "", "", f.err
	}
	outcome := "miss"
	if f.hit {
		outcome = "hit"
	}
	return f.path, outcome, nil
}

func scopedKey(scope RepositoryScope, digest string) string {
	h := sha256.Sum256([]byte(scope.Registry + "\x00" + scope.Repository + "\x00" + digest))
	return hex.EncodeToString(h[:])
}

func (s *Store) get(ctx context.Context, key, digest string, size int64, open func(context.Context) (io.ReadCloser, error)) (string, bool, error) {
	path := filepath.Join(s.dir, key)
	info, statErr := os.Lstat(path)
	if statErr == nil {
		if !info.Mode().IsRegular() {
			return "", false, errors.New("cache entry is not a regular file")
		}
		if verifyFile(path, digest, info.Size()) == nil {
			if info.Size() != size {
				return "", false, errors.New("cache blob size differs from requested size")
			}
			return path, true, nil
		}
		// Remove a corrupt entry only when this is its sole pinned request.
		s.mu.Lock()
		entry, exists := s.entries[key]
		if !exists || entry.size != info.Size() || s.pins[key] != 1 {
			s.mu.Unlock()
			return "", false, errors.New("cache blob failed verification")
		}
		if err := os.Remove(path); err != nil {
			s.mu.Unlock()
			return "", false, errors.New("cannot remove corrupt cache blob")
		}
		delete(s.entries, key)
		s.used -= entry.size
		s.mu.Unlock()
	} else if !os.IsNotExist(statErr) {
		return "", false, statErr
	} else {
		s.mu.Lock()
		_, tracked := s.entries[key]
		s.mu.Unlock()
		if tracked {
			return "", false, errors.New("tracked cache entry disappeared")
		}
	}
	if err := ctx.Err(); err != nil {
		return "", false, err
	}
	if err := s.reserve(size); err != nil {
		return "", false, err
	}
	reserved := true
	defer func() {
		if reserved {
			s.mu.Lock()
			s.reserved -= size
			s.mu.Unlock()
		}
	}()
	src, err := open(ctx)
	if err != nil {
		return "", false, err
	}
	defer src.Close()
	tmp, err := os.CreateTemp(s.dir, ".fill-*")
	if err != nil {
		return "", false, err
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
		return "", false, copyErr
	}
	if err := ctx.Err(); err != nil {
		return "", false, err
	}
	if n != size || "sha256:"+hex.EncodeToString(h.Sum(nil)) != digest {
		return "", false, errors.New("cache fill failed size or digest verification")
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		return "", false, fmt.Errorf("publish cache blob: %w", err)
	}
	s.mu.Lock()
	s.used += size
	s.reserved -= size
	reserved = false
	s.entries[key] = cacheEntry{size: size, lastUsed: time.Now()}
	s.mu.Unlock()
	return path, false, nil
}

// reserve evicts the least recently used unpinned entry before reserving a
// fill. Pins protect active readers and in-progress requests from eviction.
func (s *Store) reserve(size int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for size > s.maxTotal-s.used-s.reserved {
		victim := ""
		var oldest time.Time
		for key, entry := range s.entries {
			if s.pins[key] != 0 || s.inflight[key] != nil {
				continue
			}
			if victim == "" || entry.lastUsed.Before(oldest) || (entry.lastUsed.Equal(oldest) && key < victim) {
				victim, oldest = key, entry.lastUsed
			}
		}
		if victim == "" {
			return ErrCapacity
		}
		if err := os.Remove(filepath.Join(s.dir, victim)); err != nil {
			return errors.New("cannot evict cache blob")
		}
		s.used -= s.entries[victim].size
		delete(s.entries, victim)
	}
	s.reserved += size
	return nil
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

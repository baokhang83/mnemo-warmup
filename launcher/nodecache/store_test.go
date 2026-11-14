package nodecache

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
)

var testScope = RepositoryScope{Registry: "registry.example", Repository: "team/archive"}

func digestOf(b []byte) string {
	h := sha256.Sum256(b)
	return "sha256:" + hex.EncodeToString(h[:])
}

func TestConcurrentMissesShareOneVerifiedFill(t *testing.T) {
	s, err := NewStore(filepath.Join(t.TempDir(), "cache"), 1024, 4096)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("one archive")
	var calls atomic.Int32
	started, release := make(chan struct{}), make(chan struct{})
	open := func(ctx context.Context) (io.ReadCloser, error) {
		if calls.Add(1) == 1 {
			close(started)
		}
		select {
		case <-release:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		return io.NopCloser(bytes.NewReader(data)), nil
	}
	var wg sync.WaitGroup
	errCh := make(chan error, 9)
	for i := 0; i < 9; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			path, err := s.Get(context.Background(), testScope, digestOf(data), int64(len(data)), open)
			if err == nil {
				var got []byte
				got, err = os.ReadFile(path)
				if err == nil && !bytes.Equal(got, data) {
					err = errors.New("wrong bytes")
				}
			}
			errCh <- err
		}()
	}
	<-started
	close(release)
	wg.Wait()
	close(errCh)
	for err := range errCh {
		if err != nil {
			t.Fatal(err)
		}
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("opened source %d times", got)
	}
}

func TestInvalidFillIsNotPublishedAndCorruptHitIsRejected(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "cache")
	s, err := NewStore(dir, 1024, 4096)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("archive")
	digest := digestOf(data)
	bad := func(context.Context) (io.ReadCloser, error) {
		return io.NopCloser(bytes.NewReader([]byte("corrupt"))), nil
	}
	if _, err := s.Get(context.Background(), testScope, digest, int64(len(data)), bad); err == nil {
		t.Fatal("accepted corrupted fill")
	}
	if entries, err := os.ReadDir(dir); err != nil || len(entries) != 0 {
		t.Fatalf("unverified files remain: %v %v", entries, err)
	}
	good := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(data)), nil }
	path, err := s.Get(context.Background(), testScope, digest, int64(len(data)), good)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("corrupt"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Get(context.Background(), testScope, digest, int64(len(data)), good); err == nil {
		t.Fatal("accepted corrupt cache hit")
	}
}

func TestWaiterCancellationAndBounds(t *testing.T) {
	s, err := NewStore(filepath.Join(t.TempDir(), "cache"), 10, 40)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("archive")
	started, release := make(chan struct{}), make(chan struct{})
	open := func(context.Context) (io.ReadCloser, error) {
		close(started)
		<-release
		return io.NopCloser(bytes.NewReader(data)), nil
	}
	done := make(chan error, 1)
	go func() {
		_, err := s.Get(context.Background(), testScope, digestOf(data), int64(len(data)), open)
		done <- err
	}()
	<-started
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := s.Get(ctx, testScope, digestOf(data), int64(len(data)), open); !errors.Is(err, context.Canceled) {
		t.Fatalf("waiter: %v", err)
	}
	if _, err := s.Get(context.Background(), testScope, digestOf(data), 11, open); err == nil {
		t.Fatal("accepted oversized blob")
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestBlobCannotBeReusedAcrossRepositoryScopes(t *testing.T) {
	s, err := NewStore(filepath.Join(t.TempDir(), "cache"), 1024, 4096)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("private archive")
	good := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(data)), nil }
	if _, err := s.Get(context.Background(), testScope, digestOf(data), int64(len(data)), good); err != nil {
		t.Fatal(err)
	}
	denied := func(context.Context) (io.ReadCloser, error) {
		return nil, errors.New("repository does not contain blob")
	}
	for _, scope := range []RepositoryScope{
		{Registry: testScope.Registry, Repository: "other/archive"},
		{Registry: "other.example", Repository: testScope.Repository},
	} {
		if _, err := s.Get(context.Background(), scope, digestOf(data), int64(len(data)), denied); err == nil {
			t.Fatalf("cross-repository cache hit: %+v", scope)
		}
	}
}

func TestCapacityReservesConcurrentFillsAndRecountsOnRestart(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "cache")
	s, err := NewStore(dir, 8, 10)
	if err != nil {
		t.Fatal(err)
	}
	first, second := []byte("first!!"), []byte("second")
	started, release := make(chan struct{}), make(chan struct{})
	blocked := func(context.Context) (io.ReadCloser, error) {
		close(started)
		<-release
		return io.NopCloser(bytes.NewReader([]byte("corrupt"))), nil
	}
	done := make(chan error, 1)
	go func() {
		_, err := s.Get(context.Background(), testScope, digestOf(first), int64(len(first)), blocked)
		done <- err
	}()
	<-started
	called := false
	good := func(context.Context) (io.ReadCloser, error) {
		called = true
		return io.NopCloser(bytes.NewReader(second)), nil
	}
	if _, err := s.Get(context.Background(), testScope, digestOf(second), int64(len(second)), good); !errors.Is(err, ErrCapacity) || called {
		t.Fatalf("concurrent reservation: %v, source called %v", err, called)
	}
	close(release)
	if err := <-done; err == nil {
		t.Fatal("accepted invalid first fill")
	}
	if _, err := s.Get(context.Background(), testScope, digestOf(second), int64(len(second)), good); err != nil {
		t.Fatal(err)
	}
	// A restarted single owner must count the existing file before accepting fills.
	restarted, err := NewStore(dir, 8, 10)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := restarted.Get(context.Background(), testScope, digestOf(first), int64(len(first)), good); !errors.Is(err, ErrCapacity) {
		t.Fatalf("restart capacity: %v", err)
	}
}

func TestIncorrectDescriptorDoesNotRemoveVerifiedBlob(t *testing.T) {
	s, err := NewStore(filepath.Join(t.TempDir(), "cache"), 10, 10)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("archive")
	open := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(data)), nil }
	path, err := s.Get(context.Background(), testScope, digestOf(data), int64(len(data)), open)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Get(context.Background(), testScope, digestOf(data), int64(len(data)-1), open); err == nil {
		t.Fatal("accepted wrong descriptor size")
	}
	got, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("verified entry removed: %v", err)
	}
}

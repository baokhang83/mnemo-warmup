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

func getForTest(s *Store, ctx context.Context, scope RepositoryScope, digest string, size int64, open func(context.Context) (io.ReadCloser, error)) (string, error) {
	lease, _, err := s.Acquire(ctx, scope, digest, size, open)
	if err != nil {
		return "", err
	}
	if err := lease.Close(); err != nil {
		return "", err
	}
	return filepath.Join(s.dir, scopedKey(scope, digest)), nil
}

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
	outcomes := make(chan string, 9)
	for i := 0; i < 9; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			lease, outcome, err := s.Acquire(context.Background(), testScope, digestOf(data), int64(len(data)), open)
			if err == nil {
				var got []byte
				got, err = io.ReadAll(lease)
				if closeErr := lease.Close(); err == nil {
					err = closeErr
				}
				if err == nil && !bytes.Equal(got, data) {
					err = errors.New("wrong bytes")
				}
			}
			outcomes <- outcome
			errCh <- err
		}()
	}
	<-started
	close(release)
	wg.Wait()
	close(errCh)
	close(outcomes)
	for err := range errCh {
		if err != nil {
			t.Fatal(err)
		}
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("opened source %d times", got)
	}
	misses := 0
	for outcome := range outcomes {
		if outcome == "miss" {
			misses++
		} else if outcome != "hit" && outcome != "shared" {
			t.Fatalf("invalid outcome %q", outcome)
		}
	}
	if misses != 1 {
		t.Fatalf("got %d misses, want one", misses)
	}
}

func TestInvalidFillIsNotPublishedAndCorruptHitIsReplaced(t *testing.T) {
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
	if _, err := getForTest(s, context.Background(), testScope, digest, int64(len(data)), bad); err == nil {
		t.Fatal("accepted corrupted fill")
	}
	if entries, err := os.ReadDir(dir); err != nil || len(entries) != 0 {
		t.Fatalf("unverified files remain: %v %v", entries, err)
	}
	good := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(data)), nil }
	path, err := getForTest(s, context.Background(), testScope, digest, int64(len(data)), good)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("corrupt"), 0o600); err != nil {
		t.Fatal(err)
	}
	path, err = getForTest(s, context.Background(), testScope, digest, int64(len(data)), good)
	if err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("corrupt blob was not replaced: %v", err)
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
		_, err := getForTest(s, context.Background(), testScope, digestOf(data), int64(len(data)), open)
		done <- err
	}()
	<-started
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := getForTest(s, ctx, testScope, digestOf(data), int64(len(data)), open); !errors.Is(err, context.Canceled) {
		t.Fatalf("waiter: %v", err)
	}
	if _, err := getForTest(s, context.Background(), testScope, digestOf(data), 11, open); err == nil {
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
	if _, err := getForTest(s, context.Background(), testScope, digestOf(data), int64(len(data)), good); err != nil {
		t.Fatal(err)
	}
	denied := func(context.Context) (io.ReadCloser, error) {
		return nil, errors.New("repository does not contain blob")
	}
	for _, scope := range []RepositoryScope{
		{Registry: testScope.Registry, Repository: "other/archive"},
		{Registry: "other.example", Repository: testScope.Repository},
	} {
		if _, err := getForTest(s, context.Background(), scope, digestOf(data), int64(len(data)), denied); err == nil {
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
		_, err := getForTest(s, context.Background(), testScope, digestOf(first), int64(len(first)), blocked)
		done <- err
	}()
	<-started
	called := false
	good := func(context.Context) (io.ReadCloser, error) {
		called = true
		return io.NopCloser(bytes.NewReader(second)), nil
	}
	if _, err := getForTest(s, context.Background(), testScope, digestOf(second), int64(len(second)), good); !errors.Is(err, ErrCapacity) || called {
		t.Fatalf("concurrent reservation: %v, source called %v", err, called)
	}
	close(release)
	if err := <-done; err == nil {
		t.Fatal("accepted invalid first fill")
	}
	if _, err := getForTest(s, context.Background(), testScope, digestOf(second), int64(len(second)), good); err != nil {
		t.Fatal(err)
	}
	// A restarted single owner must count the existing file before accepting fills.
	restarted, err := NewStore(dir, 8, 10)
	if err != nil {
		t.Fatal(err)
	}
	firstSource := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(first)), nil }
	if _, err := getForTest(restarted, context.Background(), testScope, digestOf(first), int64(len(first)), firstSource); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, scopedKey(testScope, digestOf(second)))); !os.IsNotExist(err) {
		t.Fatalf("old entry not evicted: %v", err)
	}
}

func TestIncorrectDescriptorDoesNotRemoveVerifiedBlob(t *testing.T) {
	s, err := NewStore(filepath.Join(t.TempDir(), "cache"), 10, 10)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("archive")
	open := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(data)), nil }
	path, err := getForTest(s, context.Background(), testScope, digestOf(data), int64(len(data)), open)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := getForTest(s, context.Background(), testScope, digestOf(data), int64(len(data)-1), open); err == nil {
		t.Fatal("accepted wrong descriptor size")
	}
	got, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("verified entry removed: %v", err)
	}
}

func TestEvictionNeverRemovesAnActiveReader(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "cache")
	s, err := NewStore(dir, 8, 10)
	if err != nil {
		t.Fatal(err)
	}
	first, second := []byte("first!"), []byte("second!")
	firstSource := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(first)), nil }
	secondSource := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(second)), nil }
	lease, _, err := s.Acquire(context.Background(), testScope, digestOf(first), int64(len(first)), firstSource)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := getForTest(s, context.Background(), testScope, digestOf(second), int64(len(second)), secondSource); !errors.Is(err, ErrCapacity) {
		t.Fatalf("active reader capacity: %v", err)
	}
	got, err := io.ReadAll(lease)
	if err != nil || !bytes.Equal(got, first) {
		t.Fatalf("active reader: %q %v", got, err)
	}
	if err := lease.Close(); err != nil {
		t.Fatal(err)
	}
	if err := lease.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := getForTest(s, context.Background(), testScope, digestOf(second), int64(len(second)), secondSource); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, scopedKey(testScope, digestOf(first)))); !os.IsNotExist(err) {
		t.Fatalf("old entry not evicted: %v", err)
	}
}

func TestEvictionUsesLeastRecentlyUsedEntry(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "cache")
	s, err := NewStore(dir, 4, 8)
	if err != nil {
		t.Fatal(err)
	}
	first, second, third := []byte("one"), []byte("two"), []byte("six")
	for _, data := range [][]byte{first, second, first, third} {
		data := data
		open := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(data)), nil }
		if _, err := getForTest(s, context.Background(), testScope, digestOf(data), int64(len(data)), open); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := os.Stat(filepath.Join(dir, scopedKey(testScope, digestOf(second)))); !os.IsNotExist(err) {
		t.Fatalf("least recently used entry retained: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, scopedKey(testScope, digestOf(first)))); err != nil {
		t.Fatalf("recent entry missing: %v", err)
	}
}

func TestCorruptBlobIsNotRemovedWhileReaderIsActive(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "cache")
	s, err := NewStore(dir, 10, 10)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("archive")
	open := func(context.Context) (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(data)), nil }
	lease, _, err := s.Acquire(context.Background(), testScope, digestOf(data), int64(len(data)), open)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, scopedKey(testScope, digestOf(data)))
	if err := os.WriteFile(path, []byte("corrupt"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := getForTest(s, context.Background(), testScope, digestOf(data), int64(len(data)), open); err == nil {
		t.Fatal("accepted corruption while another reader was active")
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("removed blob held by active reader: %v", err)
	}
	if err := lease.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := getForTest(s, context.Background(), testScope, digestOf(data), int64(len(data)), open); err != nil {
		t.Fatalf("did not repair unpinned blob: %v", err)
	}
}

func TestRestartCleansInterruptedFills(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "cache")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, ".fill-interrupted"), []byte("partial"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewStore(dir, 8, 10); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 0 {
		t.Fatalf("stale fill remains: %v %v", entries, err)
	}
}

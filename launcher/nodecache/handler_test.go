package nodecache

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type reviewFunc func(context.Context, string) (string, error)

func (f reviewFunc) Review(ctx context.Context, token string) (string, error) { return f(ctx, token) }

type verifyFunc func(context.Context, RepositoryScope, string, string) error

func (f verifyFunc) Verify(ctx context.Context, s RepositoryScope, digest, proof string) error {
	return f(ctx, s, digest, proof)
}

type sourceFunc func(context.Context, RepositoryScope, string) (io.ReadCloser, error)

func (f sourceFunc) Open(ctx context.Context, s RepositoryScope, digest string) (io.ReadCloser, error) {
	return f(ctx, s, digest)
}

func TestHandlerReauthorizesCacheHits(t *testing.T) {
	data := []byte("archive")
	var reviews, verifications, downloads atomic.Int32
	reviewer := reviewFunc(func(_ context.Context, token string) (string, error) {
		reviews.Add(1)
		if token != "caller" {
			return "", ErrDenied
		}
		return "system:serviceaccount:team:app", nil
	})
	auth, err := NewAuthorizer(reviewer, []Grant{{"system:serviceaccount:team:app", "registry.example", "team/archive"}})
	if err != nil {
		t.Fatal(err)
	}
	verify := verifyFunc(func(_ context.Context, scope RepositoryScope, digest, proof string) error {
		verifications.Add(1)
		if scope != testScope || digest != digestOf(data) || proof != "registry-proof" {
			return ErrDenied
		}
		return nil
	})
	src := sourceFunc(func(_ context.Context, scope RepositoryScope, digest string) (io.ReadCloser, error) {
		downloads.Add(1)
		if scope != testScope || digest != digestOf(data) {
			return nil, errors.New("wrong scope")
		}
		return io.NopCloser(bytes.NewReader(data)), nil
	})
	store, err := NewStore(filepath.Join(t.TempDir(), "cache"), 1024, 4096)
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHandler(auth, verify, src, store, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewTLSServer(handler)
	defer srv.Close()
	body := `{"registry":"registry.example","repository":"team/archive","digest":"` + digestOf(data) + `","size":7}`
	request := func(caller, proof string) *http.Response {
		req, err := http.NewRequest(http.MethodPost, srv.URL+"/v1/blobs", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer "+caller)
		req.Header.Set("X-Mnemo-Registry-Authorization", proof)
		resp, err := srv.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return resp
	}
	for i := 0; i < 2; i++ {
		resp := request("caller", "registry-proof")
		got, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil || resp.StatusCode != http.StatusOK || !bytes.Equal(got, data) {
			t.Fatalf("fetch: %d %q %v", resp.StatusCode, got, err)
		}
	}
	resp := request("caller", "revoked")
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("revoked registry proof: %d", resp.StatusCode)
	}
	resp = request("other", "registry-proof")
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("unauthorized caller: %d", resp.StatusCode)
	}
	if reviews.Load() != 4 || verifications.Load() != 3 || downloads.Load() != 1 {
		t.Fatalf("reviews=%d verifications=%d downloads=%d", reviews.Load(), verifications.Load(), downloads.Load())
	}
}

func TestHandlerRequiresTLS(t *testing.T) {
	store, err := NewStore(filepath.Join(t.TempDir(), "cache"), 1024, 4096)
	if err != nil {
		t.Fatal(err)
	}
	auth, err := NewAuthorizer(reviewFunc(func(context.Context, string) (string, error) { return "", nil }), nil)
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHandler(auth, verifyFunc(func(context.Context, RepositoryScope, string, string) error { return nil }), sourceFunc(func(context.Context, RepositoryScope, string) (io.ReadCloser, error) { return nil, nil }), store, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "http://cache/v1/blobs", strings.NewReader("{}"))
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("plaintext status: %d", w.Code)
	}
}

func TestRegistryRevocationDeniesAnExistingCacheHit(t *testing.T) {
	data := []byte("archive")
	var revoked atomic.Bool
	registry := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if revoked.Load() || r.Header.Get("Authorization") != "Bearer registry-token" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		w.WriteHeader(http.StatusPartialContent)
	}))
	defer registry.Close()
	host := strings.TrimPrefix(registry.URL, "https://")
	verifier, err := NewOCIRegistryVerifier([]string{host}, registry.Client())
	if err != nil {
		t.Fatal(err)
	}
	auth, err := NewAuthorizer(reviewFunc(func(context.Context, string) (string, error) {
		return "system:serviceaccount:team:app", nil
	}), []Grant{{"system:serviceaccount:team:app", host, "team/archive"}})
	if err != nil {
		t.Fatal(err)
	}
	store, err := NewStore(filepath.Join(t.TempDir(), "cache"), 1024, 4096)
	if err != nil {
		t.Fatal(err)
	}
	var downloads atomic.Int32
	src := sourceFunc(func(context.Context, RepositoryScope, string) (io.ReadCloser, error) {
		downloads.Add(1)
		return io.NopCloser(bytes.NewReader(data)), nil
	})
	handler, err := NewHandler(auth, verifier, src, store, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	cache := httptest.NewTLSServer(handler)
	defer cache.Close()
	body := `{"registry":"` + host + `","repository":"team/archive","digest":"` + digestOf(data) + `","size":7}`
	request := func() int {
		req, err := http.NewRequest(http.MethodPost, cache.URL+"/v1/blobs", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer caller")
		req.Header.Set("X-Mnemo-Registry-Authorization", "Bearer registry-token")
		resp, err := cache.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		_, _ = io.Copy(io.Discard, resp.Body)
		return resp.StatusCode
	}
	if got := request(); got != http.StatusOK {
		t.Fatalf("first request: %d", got)
	}
	revoked.Store(true)
	if got := request(); got != http.StatusForbidden {
		t.Fatalf("revoked cache hit: %d", got)
	}
	if got := downloads.Load(); got != 1 {
		t.Fatalf("source downloads: %d", got)
	}
}

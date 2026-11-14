package nodecache

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestOCIRegistryVerifierChecksExactRepositoryAndCurrentCredential(t *testing.T) {
	digest := digestOf([]byte("archive"))
	var requests atomic.Int32
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Method != http.MethodGet || r.Header.Get("Range") != "bytes=0-0" || r.URL.Path != "/v2/team/archive/blobs/"+digest {
			t.Error("wrong registry request")
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if r.Header.Get("Authorization") == "Bearer partial" {
			w.WriteHeader(http.StatusPartialContent)
			return
		}
		if r.Header.Get("Authorization") != "Bearer allowed" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	host := strings.TrimPrefix(srv.URL, "https://")
	v, err := NewOCIRegistryVerifier([]string{host}, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	scope := RepositoryScope{Registry: host, Repository: "team/archive"}
	if err := v.Verify(context.Background(), scope, digest, "Bearer allowed"); err != nil {
		t.Fatal(err)
	}
	if err := v.Verify(context.Background(), scope, digest, "Bearer partial"); err != nil {
		t.Fatal(err)
	}
	if err := v.Verify(context.Background(), scope, digest, "Bearer revoked"); !errors.Is(err, ErrDenied) {
		t.Fatalf("revoked credential: %v", err)
	}
	if err := v.Verify(context.Background(), RepositoryScope{Registry: "elsewhere.example", Repository: scope.Repository}, digest, "Bearer allowed"); !errors.Is(err, ErrDenied) {
		t.Fatalf("unlisted registry: %v", err)
	}
	if err := v.Verify(context.Background(), RepositoryScope{Registry: host, Repository: "../other"}, digest, "Bearer allowed"); !errors.Is(err, ErrDenied) {
		t.Fatalf("invalid repository: %v", err)
	}
	if err := v.Verify(context.Background(), scope, digest, "Bearer secret\r\nX: leak"); !errors.Is(err, ErrDenied) || strings.Contains(err.Error(), "secret") {
		t.Fatalf("unsafe proof: %v", err)
	}
	if got := requests.Load(); got != 3 {
		t.Fatalf("unexpected registry requests: %d", got)
	}
}

func TestOCIRegistryVerifierNeverFollowsRedirects(t *testing.T) {
	var redirected atomic.Bool
	other := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { redirected.Store(true) }))
	defer other.Close()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL, http.StatusTemporaryRedirect)
	}))
	defer srv.Close()
	host := strings.TrimPrefix(srv.URL, "https://")
	v, err := NewOCIRegistryVerifier([]string{host}, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	if err := v.Verify(context.Background(), RepositoryScope{Registry: host, Repository: "team/archive"}, digestOf([]byte("x")), "Bearer secret"); !errors.Is(err, ErrDenied) {
		t.Fatalf("redirect: %v", err)
	}
	if redirected.Load() {
		t.Fatal("followed registry redirect")
	}
}

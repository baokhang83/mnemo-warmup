package nodecache

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestRegistrySourceReadsRotatedCredentialAndRestrictsScope(t *testing.T) {
	data := []byte("archive")
	var current atomic.Value
	current.Store("Bearer first")
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/v2/team/archive/blobs/"+digestOf(data) || r.Header.Get("Authorization") != current.Load().(string) {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		_, _ = w.Write(data)
	}))
	defer srv.Close()
	scope := RepositoryScope{Registry: strings.TrimPrefix(srv.URL, "https://"), Repository: "team/archive"}
	credential := filepath.Join(t.TempDir(), "credential")
	if err := os.WriteFile(credential, []byte("Bearer first\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	source, err := NewOCIRegistrySource(map[RepositoryScope]string{scope: credential}, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	for _, token := range []string{"Bearer first", "Bearer second"} {
		current.Store(token)
		if err := os.WriteFile(credential, []byte(token), 0o600); err != nil {
			t.Fatal(err)
		}
		body, err := source.Open(ctx, scope, digestOf(data))
		if err != nil {
			t.Fatal(err)
		}
		got, err := io.ReadAll(body)
		body.Close()
		if err != nil || !bytes.Equal(got, data) {
			t.Fatalf("got %q: %v", got, err)
		}
	}
	if _, err := source.Open(ctx, RepositoryScope{Registry: scope.Registry, Repository: "other/archive"}, digestOf(data)); !errors.Is(err, ErrDenied) {
		t.Fatalf("unlisted repository: %v", err)
	}
	if _, err := source.Open(context.Background(), scope, digestOf(data)); err == nil {
		t.Fatal("download without deadline accepted")
	}
}

func TestRegistrySourceDoesNotForwardCredentialOnRedirect(t *testing.T) {
	var redirected atomic.Bool
	other := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { redirected.Store(true) }))
	defer other.Close()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL, http.StatusTemporaryRedirect)
	}))
	defer srv.Close()
	scope := RepositoryScope{Registry: strings.TrimPrefix(srv.URL, "https://"), Repository: "team/archive"}
	credential := filepath.Join(t.TempDir(), "credential")
	if err := os.WriteFile(credential, []byte("Bearer sensitive"), 0o600); err != nil {
		t.Fatal(err)
	}
	source, err := NewOCIRegistrySource(map[RepositoryScope]string{scope: credential}, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, err = source.Open(ctx, scope, digestOf([]byte("x")))
	if err == nil || strings.Contains(err.Error(), "sensitive") || redirected.Load() {
		t.Fatalf("redirect or credential leak: %v", err)
	}
}

func TestHandlerSeparatesCallerAndSourceCredentials(t *testing.T) {
	data := []byte("archive")
	var checks, downloads atomic.Int32
	registry := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v2/team/archive/blobs/"+digestOf(data) {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if r.Header.Get("Range") == "bytes=0-0" {
			checks.Add(1)
			if r.Header.Get("Authorization") != "Bearer caller-registry" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			w.WriteHeader(http.StatusPartialContent)
			return
		}
		downloads.Add(1)
		if r.Header.Get("Authorization") != "Bearer service-registry" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_, _ = w.Write(data)
	}))
	defer registry.Close()
	scope := RepositoryScope{Registry: strings.TrimPrefix(registry.URL, "https://"), Repository: "team/archive"}
	credential := filepath.Join(t.TempDir(), "credential")
	if err := os.WriteFile(credential, []byte("Bearer service-registry"), 0o600); err != nil {
		t.Fatal(err)
	}
	source, err := NewOCIRegistrySource(map[RepositoryScope]string{scope: credential}, registry.Client())
	if err != nil {
		t.Fatal(err)
	}
	verifier, err := NewOCIRegistryVerifier([]string{scope.Registry}, registry.Client())
	if err != nil {
		t.Fatal(err)
	}
	auth, err := NewAuthorizer(reviewFunc(func(context.Context, string) (string, error) { return "system:serviceaccount:team:app", nil }), []Grant{{"system:serviceaccount:team:app", scope.Registry, scope.Repository}})
	if err != nil {
		t.Fatal(err)
	}
	store, err := NewStore(filepath.Join(t.TempDir(), "cache"), 1024, 4096)
	if err != nil {
		t.Fatal(err)
	}
	handler, err := NewHandler(auth, verifier, source, store, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	cache := httptest.NewTLSServer(handler)
	defer cache.Close()
	body := `{"registry":"` + scope.Registry + `","repository":"team/archive","digest":"` + digestOf(data) + `","size":7}`
	for i := 0; i < 2; i++ {
		req, err := http.NewRequest(http.MethodPost, cache.URL+"/v1/blobs", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer caller-service-account")
		req.Header.Set("X-Mnemo-Registry-Authorization", "Bearer caller-registry")
		resp, err := cache.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		got, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil || resp.StatusCode != http.StatusOK || !bytes.Equal(got, data) {
			t.Fatalf("cache request: %d %q %v", resp.StatusCode, got, err)
		}
	}
	if checks.Load() != 2 || downloads.Load() != 1 {
		t.Fatalf("checks=%d downloads=%d", checks.Load(), downloads.Load())
	}
}

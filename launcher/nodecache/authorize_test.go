package nodecache

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestTokenReviewAndExactRepositoryGrants(t *testing.T) {
	var requests atomic.Int32
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.URL.Path != "/apis/authentication.k8s.io/v1/tokenreviews" || r.Header.Get("Authorization") != "Bearer reviewer" {
			t.Error("missing reviewer authorization")
			w.WriteHeader(http.StatusForbidden)
			return
		}
		var request struct {
			Spec struct {
				Token     string   `json:"token"`
				Audiences []string `json:"audiences"`
			} `json:"spec"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil || len(request.Spec.Audiences) != 1 || request.Spec.Audiences[0] != "node-cache" {
			t.Error("invalid token review request")
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		subject := "system:serviceaccount:team:app"
		if request.Spec.Token == "other" {
			subject = "system:serviceaccount:other:app"
		}
		audiences := []string{"node-cache"}
		if request.Spec.Token == "wrong-audience" {
			audiences = []string{"other"}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"status": map[string]any{
			"authenticated": request.Spec.Token != "invalid", "user": map[string]string{"username": subject}, "audiences": audiences,
		}})
	}))
	defer srv.Close()
	tokenPath := filepath.Join(t.TempDir(), "reviewer-token")
	if err := os.WriteFile(tokenPath, []byte("reviewer\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	reviewer, err := NewKubernetesReviewer(srv.URL, "node-cache", tokenPath, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	auth, err := NewAuthorizer(reviewer, []Grant{{"system:serviceaccount:team:app", "registry.example", "team/archive"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := auth.Authorize(context.Background(), "allowed", "registry.example", "team/archive"); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct{ token, registry, repo string }{
		{"other", "registry.example", "team/archive"},
		{"invalid", "registry.example", "team/archive"},
		{"wrong-audience", "registry.example", "team/archive"},
		{"allowed", "registry.example", "other/archive"},
		{"allowed", "other.example", "team/archive"},
	} {
		if err := auth.Authorize(context.Background(), tc.token, tc.registry, tc.repo); !errors.Is(err, ErrDenied) {
			t.Errorf("unauthorized scope accepted: %v", err)
		}
	}
	if requests.Load() != 6 {
		t.Fatalf("each request needs a review; got %d", requests.Load())
	}
}

func TestReviewerRejectsRedirectWithoutForwardingTokens(t *testing.T) {
	var leaked atomic.Bool
	other := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { leaked.Store(true) }))
	defer other.Close()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL, http.StatusTemporaryRedirect)
	}))
	defer srv.Close()
	p := filepath.Join(t.TempDir(), "token")
	if err := os.WriteFile(p, []byte("reviewer"), 0o600); err != nil {
		t.Fatal(err)
	}
	reviewer, err := NewKubernetesReviewer(srv.URL, "node-cache", p, srv.Client())
	if err != nil {
		t.Fatal(err)
	}
	_, err = reviewer.Review(context.Background(), "secret-caller-token")
	if err == nil || strings.Contains(err.Error(), "secret-caller-token") || leaked.Load() {
		t.Fatalf("redirect or credential leak: %v", err)
	}
}

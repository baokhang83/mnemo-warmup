package nodecache

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// RegistryVerifier checks that the caller's registry credential can read this
// exact blob in this repository. It must not log or retain the credential.
type RegistryVerifier interface {
	Verify(context.Context, RepositoryScope, string, string) error
}

// BlobSource opens a blob from the exact configured repository using the
// service's read-only registry identity. It must restrict registry destinations
// to its operator-configured allowlist and honor cancellation while reading.
type BlobSource interface {
	Open(context.Context, RepositoryScope, string) (io.ReadCloser, error)
}

type cacheHandler struct {
	authorizer *Authorizer
	verifier   RegistryVerifier
	source     BlobSource
	store      *Store
	timeout    time.Duration
}

// NewHandler returns the private cache endpoint. Its server must terminate TLS
// directly; authorization and registry verification run on every request.
func NewHandler(a *Authorizer, v RegistryVerifier, src BlobSource, store *Store, timeout time.Duration) (http.Handler, error) {
	if a == nil || v == nil || src == nil || store == nil || timeout <= 0 {
		return nil, errors.New("authorizer, registry verifier, source, store and timeout are required")
	}
	return &cacheHandler{authorizer: a, verifier: v, source: src, store: store, timeout: timeout}, nil
}

func (h *cacheHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.TLS == nil || r.Method != http.MethodPost || r.URL.Path != "/v1/blobs" || r.URL.RawQuery != "" {
		http.Error(w, "invalid cache request", http.StatusBadRequest)
		return
	}
	parts := strings.Fields(r.Header.Get("Authorization"))
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
		http.Error(w, "cache access denied", http.StatusForbidden)
		return
	}
	defer r.Body.Close()
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10))
	dec.DisallowUnknownFields()
	var request struct {
		Registry   string `json:"registry"`
		Repository string `json:"repository"`
		Digest     string `json:"digest"`
		Size       int64  `json:"size"`
	}
	if err := dec.Decode(&request); err != nil {
		http.Error(w, "invalid cache request", http.StatusBadRequest)
		return
	}
	if err := dec.Decode(new(any)); err != io.EOF || !digestPattern.MatchString(request.Digest) || request.Size < 0 || request.Size > h.store.maxBlob || request.Registry == "" || request.Repository == "" {
		http.Error(w, "invalid cache request", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), h.timeout)
	defer cancel()
	if err := h.authorizer.Authorize(ctx, parts[1], request.Registry, request.Repository); err != nil {
		if errors.Is(err, ErrDenied) {
			http.Error(w, "cache access denied", http.StatusForbidden)
		} else {
			http.Error(w, "cache authorization unavailable", http.StatusServiceUnavailable)
		}
		return
	}
	scope := RepositoryScope{Registry: request.Registry, Repository: request.Repository}
	// This proof is ephemeral and must only be forwarded over verified TLS.
	if err := h.verifier.Verify(ctx, scope, request.Digest, r.Header.Get("X-Mnemo-Registry-Authorization")); err != nil {
		http.Error(w, "registry access unavailable", http.StatusForbidden)
		return
	}
	path, err := h.store.Get(ctx, scope, request.Digest, request.Size, func(ctx context.Context) (io.ReadCloser, error) {
		return h.source.Open(ctx, scope, request.Digest)
	})
	if err != nil {
		http.Error(w, "archive cache unavailable", http.StatusServiceUnavailable)
		return
	}
	f, err := os.Open(path)
	if err != nil {
		http.Error(w, "archive cache unavailable", http.StatusServiceUnavailable)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.Copy(w, f)
}

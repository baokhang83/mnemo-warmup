package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
)

func TestNativeFetchUsesNodeCacheOrFallsBackToRegistry(t *testing.T) {
	for _, mode := range []string{"hit", "corrupt", "short", "overlong", "denied", "redirect", "timeout", "missing-ca", "missing-token"} {
		t.Run(mode, func(t *testing.T) {
			f := newFixture(t)
			fr := newFakeRegistry(t, f)
			fr.needBasic = true
			archive, err := os.ReadFile(f.path("cache/app.jsa"))
			if err != nil {
				t.Fatal(err)
			}
			var cacheCalls, redirected atomic.Int32
			other := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { redirected.Add(1) }))
			defer other.Close()
			cache := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				cacheCalls.Add(1)
				if r.Method != http.MethodPost || r.URL.Path != "/v1/blobs" || r.Header.Get("Authorization") != "Bearer pod-token" || r.Header.Get("X-Mnemo-Registry-Authorization") != "Basic "+base64.StdEncoding.EncodeToString([]byte("puller:s3cret")) {
					t.Error("incorrect cache request or credentials")
					w.WriteHeader(http.StatusForbidden)
					return
				}
				var request struct {
					Registry, Repository, Digest string
					Size                         int64
				}
				if json.NewDecoder(r.Body).Decode(&request) != nil || request.Registry != fr.host() || request.Repository != "team/app" || request.Size != int64(len(archive)) || !digestRe.MatchString(request.Digest) {
					t.Error("incorrect cache scope or descriptor")
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				switch mode {
				case "denied":
					w.WriteHeader(http.StatusForbidden)
				case "redirect":
					http.Redirect(w, r, other.URL, http.StatusTemporaryRedirect)
				case "timeout":
					<-r.Context().Done()
				case "corrupt":
					b := append([]byte{}, archive...)
					b[0] ^= 1
					_, _ = w.Write(b)
				case "short":
					_, _ = w.Write(archive[:len(archive)-1])
				case "overlong":
					_, _ = w.Write(append(append([]byte{}, archive...), 'x'))
				default:
					w.Header().Set("X-Mnemo-Cache-Outcome", "hit")
					_, _ = w.Write(archive)
				}
			}))
			defer cache.Close()
			ca := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cache.Certificate().Raw})
			if err := os.WriteFile(f.path("cache-ca.pem"), ca, 0o600); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(f.path("cache-token"), []byte("pod-token\n"), 0o600); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(f.path("auth.json"), []byte(fmt.Sprintf(`{"auths":{"%s":{"username":"puller","password":"s3cret"}}}`, fr.host())), 0o600); err != nil {
				t.Fatal(err)
			}
			caPath, tokenPath := f.path("cache-ca.pem"), f.path("cache-token")
			if mode == "missing-ca" {
				caPath = f.path("absent-ca")
			}
			if mode == "missing-token" {
				tokenPath = f.path("absent-token")
			}
			l, args, _, stderr := fetchHarness(t, f, fr,
				"MNEMO_WARMUP_REGISTRY_AUTH="+f.path("auth.json"), "MNEMO_WARMUP_NODECACHE_URL="+cache.URL,
				"MNEMO_WARMUP_NODECACHE_CA_FILE="+caPath, "MNEMO_WARMUP_NODECACHE_TOKEN_FILE="+tokenPath,
				"MNEMO_WARMUP_NODECACHE_TIMEOUT_SECONDS=1")
			code, done := l.nativeFetch(args, false)
			if !done || code != 0 {
				t.Fatalf("fetch failed: %s", stderr)
			}
			got, err := os.ReadFile(f.path("out/app.jsa"))
			if err != nil || !bytes.Equal(got, archive) {
				t.Fatalf("archive not verified: %v", err)
			}
			wantCacheCalls := int32(1)
			if mode == "missing-ca" || mode == "missing-token" {
				wantCacheCalls = 0
			}
			if cacheCalls.Load() != wantCacheCalls || redirected.Load() != 0 {
				t.Fatalf("cache calls=%d redirects=%d", cacheCalls.Load(), redirected.Load())
			}
			if strings.Contains(stderr.String(), "s3cret") || strings.Contains(stderr.String(), "pod-token") {
				t.Fatal("credential leaked to log")
			}
			fr.mu.Lock()
			requests := append([]string(nil), fr.requests...)
			fr.mu.Unlock()
			blobDownloads := 0
			for _, request := range requests {
				if strings.Contains(request, "/blobs/") {
					blobDownloads++
				}
			}
			// One config blob is always fetched. The archive blob comes directly from
			// the registry only after a failed cache request.
			want := 2
			if mode == "hit" {
				want = 1
			}
			if blobDownloads != want {
				t.Fatalf("registry blob requests=%d want=%d", blobDownloads, want)
			}
			if mode != "hit" && !strings.Contains(stderr.String(), "trying registry directly") && !strings.Contains(stderr.String(), "node cache configuration unavailable") {
				t.Fatal("fallback not logged")
			}
			if mode == "hit" && !strings.Contains(stderr.String(), "node cache hit: verified archive") {
				t.Fatal("cache outcome not logged")
			}
			entries, err := os.ReadDir(f.path("out"))
			if err != nil {
				t.Fatal(err)
			}
			for _, entry := range entries {
				if strings.HasSuffix(entry.Name(), ".part") {
					t.Fatal("partial archive left behind")
				}
			}
		})
	}
}

func TestNodeCacheConfigurationRequiresTLSAndTrust(t *testing.T) {
	for _, env := range []map[string]string{
		{"MNEMO_WARMUP_NODECACHE_URL": "http://cache:8443"},
		{"MNEMO_WARMUP_NODECACHE_URL": "https://cache:8443"},
		{"MNEMO_WARMUP_NODECACHE_URL": "https://cache:8443/path", "MNEMO_WARMUP_NODECACHE_CA_FILE": "ca", "MNEMO_WARMUP_NODECACHE_TOKEN_FILE": "token"},
	} {
		if _, err := newNodeCacheClient(env); err == nil {
			t.Fatal("accepted unsafe or incomplete cache configuration")
		}
	}
}

package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/baokhang83/mnemo-warmup/launcher/nodecache"
)

func TestNodeCacheEndToEndConcurrentFetch(t *testing.T) {
	f := newFixture(t)
	config, err := os.ReadFile(f.path("cache/mnemo-warmup.manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	manifest, ok := parseManifest(config)
	if !ok {
		t.Fatal("invalid fixture manifest")
	}
	archive, err := os.ReadFile(f.path("cache/app.jsa"))
	if err != nil {
		t.Fatal(err)
	}
	digest := func(b []byte) string { h := sha256.Sum256(b); return "sha256:" + hex.EncodeToString(h[:]) }
	image, err := json.Marshal(map[string]any{
		"schemaVersion": 2, "mediaType": ociManifestType,
		"config": map[string]any{"mediaType": ociConfigType, "digest": digest(config), "size": len(config)},
		"layers": []any{map[string]any{"mediaType": ociLayerType, "digest": digest(archive), "size": len(archive)}},
	})
	if err != nil {
		t.Fatal(err)
	}
	podAuth := "Basic " + base64.StdEncoding.EncodeToString([]byte("pod:password"))
	serviceAuth := "Basic " + base64.StdEncoding.EncodeToString([]byte("cache:password"))
	var reviews, checks, downloads atomic.Int32
	registry := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth := r.Header.Get("Authorization")
		if auth != podAuth && auth != serviceAuth {
			w.Header().Set("WWW-Authenticate", `Basic realm="registry"`)
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch r.URL.Path {
		case "/v2/team/app/manifests/fp-" + strings.TrimPrefix(manifest.Fingerprint, "sha256:"):
			if auth != podAuth {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			_, _ = w.Write(image)
		case "/v2/team/app/blobs/" + digest(config):
			if auth != podAuth {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			_, _ = w.Write(config)
		case "/v2/team/app/blobs/" + digest(archive):
			if r.Header.Get("Range") == "bytes=0-0" && auth == podAuth {
				checks.Add(1)
				w.WriteHeader(http.StatusPartialContent)
				return
			}
			if auth == serviceAuth {
				downloads.Add(1)
				_, _ = w.Write(archive)
				return
			}
			w.WriteHeader(http.StatusForbidden)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer registry.Close()
	kube := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reviews.Add(1)
		if r.Header.Get("Authorization") != "Bearer reviewer" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		var request struct {
			Spec struct {
				Token     string   `json:"token"`
				Audiences []string `json:"audiences"`
			} `json:"spec"`
		}
		if json.NewDecoder(r.Body).Decode(&request) != nil || request.Spec.Token != "pod-identity" || len(request.Spec.Audiences) != 1 || request.Spec.Audiences[0] != "node-cache" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"status": map[string]any{"authenticated": true, "user": map[string]string{"username": "system:serviceaccount:team:app"}, "audiences": []string{"node-cache"}}})
	}))
	defer kube.Close()
	write := func(name string, data []byte) string {
		t.Helper()
		p := f.path(name)
		if err := os.WriteFile(p, data, 0o600); err != nil {
			t.Fatal(err)
		}
		return p
	}
	registryCA := write("registry-ca.pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: registry.Certificate().Raw}))
	kubeCA := write("kube-ca.pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: kube.Certificate().Raw}))
	cert := write("server-cert.pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: registry.TLS.Certificates[0].Certificate[0]}))
	keyBytes, err := x509.MarshalPKCS8PrivateKey(registry.TLS.Certificates[0].PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	key := write("server-key.pem", pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyBytes}))
	host := strings.TrimPrefix(registry.URL, "https://")
	configServer := nodecache.ServerConfig{
		ListenAddress: ":8443", TLSCertificateFile: cert, TLSKeyFile: key,
		KubernetesAPI: kube.URL, KubernetesCAFile: kubeCA, ReviewerTokenFile: write("reviewer", []byte("reviewer")), TokenAudience: "node-cache",
		RegistryCAFile: registryCA, CacheDirectory: f.path("node-store"), MaxBlobBytes: 1 << 20, MaxTotalBytes: 2 << 20, RequestTimeoutSecs: 10,
		Repositories: []nodecache.RepositoryConfig{{Registry: host, Repository: "team/app", CredentialFile: write("service-auth", []byte(serviceAuth)), Subjects: []string{"system:serviceaccount:team:app"}}},
	}
	server, err := nodecache.NewServer(configServer)
	if err != nil {
		t.Fatal(err)
	}
	cache := httptest.NewTLSServer(server.Handler)
	defer cache.Close()
	cacheCA := write("cache-ca.pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cache.Certificate().Raw}))
	token := write("pod-token", []byte("pod-identity"))
	auth := write("auth.json", []byte(fmt.Sprintf(`{"auths":{"%s":{"username":"pod","password":"password"}}}`, host)))
	env := map[string]string{"MNEMO_WARMUP_NODECACHE_URL": cache.URL, "MNEMO_WARMUP_NODECACHE_CA_FILE": cacheCA, "MNEMO_WARMUP_NODECACHE_TOKEN_FILE": token, "MNEMO_WARMUP_REGISTRY_AUTH": auth}
	var wg sync.WaitGroup
	errs := make(chan error, 9)
	for i := 0; i < 9; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			l := launcher{stderr: io.Discard}
			r, err := l.openRegistry(fetchOptions{store: "oci://" + host + "/team/app", timeout: 10 * time.Second}, env)
			if err != nil {
				errs <- err
				return
			}
			r.client = registry.Client()
			out := filepath.Join(f.root, fmt.Sprintf("pod-%d", i))
			if err := os.Mkdir(out, 0o700); err != nil {
				errs <- err
				return
			}
			hit, err := r.fetchArchive(manifest.Fingerprint, manifest, out)
			if err != nil {
				errs <- err
				return
			}
			got, err := os.ReadFile(hit.archive)
			if err == nil && !bytes.Equal(got, archive) {
				err = fmt.Errorf("wrong archive")
			}
			errs <- err
		}(i)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	if reviews.Load() != 9 || checks.Load() != 9 || downloads.Load() != 1 {
		t.Fatalf("token reviews=%d registry checks=%d source downloads=%d", reviews.Load(), checks.Load(), downloads.Load())
	}
}

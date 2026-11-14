package nodecache

import (
	"bytes"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestServerWiresKubernetesRegistryAndVerifiedStore(t *testing.T) {
	data := []byte("archive")
	var reviews, checks, downloads atomic.Int32
	kube := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reviews.Add(1)
		if r.URL.Path != "/apis/authentication.k8s.io/v1/tokenreviews" || r.Header.Get("Authorization") != "Bearer reviewer" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		var review struct {
			Spec struct {
				Token     string   `json:"token"`
				Audiences []string `json:"audiences"`
			} `json:"spec"`
		}
		if json.NewDecoder(r.Body).Decode(&review) != nil || review.Spec.Token != "pod-token" || len(review.Spec.Audiences) != 1 || review.Spec.Audiences[0] != "node-cache" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"status": map[string]any{"authenticated": true, "user": map[string]string{"username": "system:serviceaccount:team:app"}, "audiences": []string{"node-cache"}}})
	}))
	defer kube.Close()
	registry := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v2/team/archive/blobs/"+digestOf(data) {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if r.Header.Get("Range") == "bytes=0-0" {
			checks.Add(1)
			if r.Header.Get("Authorization") != "Bearer pod-registry" {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			w.WriteHeader(http.StatusPartialContent)
			return
		}
		downloads.Add(1)
		if r.Header.Get("Authorization") != "Bearer cache-registry" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		_, _ = w.Write(data)
	}))
	defer registry.Close()
	dir := t.TempDir()
	write := func(name string, data []byte) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, data, 0o600); err != nil {
			t.Fatal(err)
		}
		return p
	}
	kubeCA := write("kube-ca.pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: kube.Certificate().Raw}))
	registryCA := write("registry-ca.pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: registry.Certificate().Raw}))
	cert := write("server-cert.pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: registry.TLS.Certificates[0].Certificate[0]}))
	keyBytes, err := x509.MarshalPKCS8PrivateKey(registry.TLS.Certificates[0].PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	key := write("server-key.pem", pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyBytes}))
	host := strings.TrimPrefix(registry.URL, "https://")
	cfg := ServerConfig{
		ListenAddress: ":8443", TLSCertificateFile: cert, TLSKeyFile: key,
		KubernetesAPI: kube.URL, KubernetesCAFile: kubeCA, ReviewerTokenFile: write("reviewer", []byte("reviewer")), TokenAudience: "node-cache",
		RegistryCAFile: registryCA, CacheDirectory: filepath.Join(dir, "cache"), MaxBlobBytes: 1024, MaxTotalBytes: 2048, RequestTimeoutSecs: 5,
		Repositories: []RepositoryConfig{{Registry: host, Repository: "team/archive", CredentialFile: write("credential", []byte("Bearer cache-registry")), Subjects: []string{"system:serviceaccount:team:app"}}},
	}
	server, err := NewServer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	cache := httptest.NewTLSServer(server.Handler)
	defer cache.Close()
	body := `{"registry":"` + host + `","repository":"team/archive","digest":"` + digestOf(data) + `","size":7}`
	for i := 0; i < 2; i++ {
		req, err := http.NewRequest(http.MethodPost, cache.URL+"/v1/blobs", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer pod-token")
		req.Header.Set("X-Mnemo-Registry-Authorization", "Bearer pod-registry")
		resp, err := cache.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		got, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil || resp.StatusCode != http.StatusOK || !bytes.Equal(got, data) {
			t.Fatalf("cache: %d %q %v", resp.StatusCode, got, err)
		}
	}
	if reviews.Load() != 2 || checks.Load() != 2 || downloads.Load() != 1 {
		t.Fatalf("reviews=%d checks=%d downloads=%d", reviews.Load(), checks.Load(), downloads.Load())
	}
	cfg.Repositories = append(cfg.Repositories, cfg.Repositories[0])
	if _, err := NewServer(cfg); err == nil {
		t.Fatal("accepted duplicate repository")
	}
}

func TestReadServerConfigRejectsUnknownTrailingAndOversizeData(t *testing.T) {
	p := filepath.Join(t.TempDir(), "config.json")
	for _, data := range []string{`{"unknown":true}`, `{} {}`, `{}` + strings.Repeat(" ", 1<<20)} {
		if err := os.WriteFile(p, []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
		if _, err := ReadServerConfig(p); err == nil {
			t.Fatal("accepted invalid config")
		}
	}
}

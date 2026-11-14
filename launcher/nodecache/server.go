package nodecache

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"time"
)

// ServerConfig contains paths and policy, never credential values. The server
// requires a private cache directory owned by this process alone.
type ServerConfig struct {
	ListenAddress      string             `json:"listenAddress"`
	TLSCertificateFile string             `json:"tlsCertificateFile"`
	TLSKeyFile         string             `json:"tlsKeyFile"`
	KubernetesAPI      string             `json:"kubernetesAPI"`
	KubernetesCAFile   string             `json:"kubernetesCAFile"`
	ReviewerTokenFile  string             `json:"reviewerTokenFile"`
	TokenAudience      string             `json:"tokenAudience"`
	RegistryCAFile     string             `json:"registryCAFile"`
	CacheDirectory     string             `json:"cacheDirectory"`
	MaxBlobBytes       int64              `json:"maxBlobBytes"`
	MaxTotalBytes      int64              `json:"maxTotalBytes"`
	RequestTimeoutSecs int                `json:"requestTimeoutSeconds"`
	Repositories       []RepositoryConfig `json:"repositories"`
}

// RepositoryConfig grants named Kubernetes service accounts access to one
// repository. CredentialFile is a separate read-only registry credential;
// an empty path means anonymous source access.
type RepositoryConfig struct {
	Registry       string   `json:"registry"`
	Repository     string   `json:"repository"`
	CredentialFile string   `json:"credentialFile"`
	Subjects       []string `json:"subjects"`
}

// ReadServerConfig rejects unknown fields and limits the config file size.
func ReadServerConfig(path string) (ServerConfig, error) {
	var cfg ServerConfig
	f, err := os.Open(path)
	if err != nil {
		return cfg, errors.New("cannot open node cache configuration")
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, (1<<20)+1))
	if err != nil || len(data) > 1<<20 {
		return cfg, errors.New("invalid node cache configuration size")
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		return cfg, errors.New("invalid node cache configuration")
	}
	if err := dec.Decode(new(any)); err != io.EOF {
		return cfg, errors.New("invalid trailing node cache configuration")
	}
	return cfg, nil
}

// NewServer builds an HTTPS-only server. The caller owns its listener and must
// call ListenAndServeTLS with empty certificate arguments or use TLSConfig.
func NewServer(cfg ServerConfig) (*http.Server, error) {
	_, port, err := net.SplitHostPort(cfg.ListenAddress)
	portNumber, portErr := strconv.Atoi(port)
	if err != nil || portErr != nil || portNumber < 1 || portNumber > 65535 || cfg.RequestTimeoutSecs < 1 || cfg.RequestTimeoutSecs > 900 || cfg.CacheDirectory == "" || cfg.KubernetesCAFile == "" || len(cfg.Repositories) == 0 {
		return nil, errors.New("invalid node cache server settings")
	}
	cert, err := tls.LoadX509KeyPair(cfg.TLSCertificateFile, cfg.TLSKeyFile)
	if err != nil {
		return nil, errors.New("cannot load node cache TLS certificate")
	}
	kubeClient, err := trustedHTTPClient(cfg.KubernetesCAFile, false)
	if err != nil {
		return nil, errors.New("cannot configure Kubernetes TLS trust")
	}
	registryClient, err := trustedHTTPClient(cfg.RegistryCAFile, true)
	if err != nil {
		return nil, errors.New("cannot configure registry TLS trust")
	}
	reviewer, err := NewKubernetesReviewer(cfg.KubernetesAPI, cfg.TokenAudience, cfg.ReviewerTokenFile, kubeClient)
	if err != nil {
		return nil, err
	}
	credentials := make(map[RepositoryScope]string, len(cfg.Repositories))
	var grants []Grant
	var registries []string
	seenRegistries := make(map[string]bool)
	for _, repo := range cfg.Repositories {
		scope := RepositoryScope{Registry: repo.Registry, Repository: repo.Repository}
		if _, exists := credentials[scope]; exists || len(repo.Subjects) == 0 {
			return nil, errors.New("duplicate repository or missing subjects")
		}
		credentials[scope] = repo.CredentialFile
		if !seenRegistries[repo.Registry] {
			registries = append(registries, repo.Registry)
			seenRegistries[repo.Registry] = true
		}
		for _, subject := range repo.Subjects {
			grants = append(grants, Grant{Subject: subject, Registry: repo.Registry, Repository: repo.Repository})
		}
	}
	authorizer, err := NewAuthorizer(reviewer, grants)
	if err != nil {
		return nil, err
	}
	verifier, err := NewOCIRegistryVerifier(registries, registryClient)
	if err != nil {
		return nil, err
	}
	source, err := NewOCIRegistrySource(credentials, registryClient)
	if err != nil {
		return nil, err
	}
	store, err := NewStore(cfg.CacheDirectory, cfg.MaxBlobBytes, cfg.MaxTotalBytes)
	if err != nil {
		return nil, err
	}
	timeout := time.Duration(cfg.RequestTimeoutSecs) * time.Second
	handler, err := NewHandler(authorizer, verifier, source, store, timeout)
	if err != nil {
		return nil, err
	}
	return &http.Server{
		Addr: cfg.ListenAddress, Handler: handler,
		TLSConfig:         &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{cert}},
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: timeout + 10*time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 32 << 10,
	}, nil
}

func trustedHTTPClient(caFile string, systemRoots bool) (*http.Client, error) {
	var roots *x509.CertPool
	var err error
	if systemRoots {
		roots, err = x509.SystemCertPool()
		if err != nil || roots == nil {
			roots = x509.NewCertPool()
		}
	} else {
		roots = x509.NewCertPool()
	}
	if caFile != "" {
		data, err := os.ReadFile(caFile)
		if err != nil || !roots.AppendCertsFromPEM(data) {
			return nil, errors.New("invalid CA certificate")
		}
	}
	return &http.Client{Transport: &http.Transport{
		Proxy: nil, DialContext: (&net.Dialer{Timeout: 5 * time.Second}).DialContext,
		TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots},
		TLSHandshakeTimeout: 5 * time.Second, ResponseHeaderTimeout: 10 * time.Second,
	}}, nil
}

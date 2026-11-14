package main

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// nodeCacheClient is optional. Its TLS CA and projected service-account token
// are supplied by the operator; registry credentials are sent only to this
// verified cache endpoint and never through redirects.
type nodeCacheClient struct {
	endpoint  string
	tokenFile string
	client    *http.Client
}

func newNodeCacheClient(env map[string]string) (*nodeCacheClient, error) {
	address := env["MNEMO_WARMUP_NODECACHE_URL"]
	if address == "" {
		return nil, nil
	}
	u, err := url.Parse(address)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("invalid cache endpoint")
	}
	caPath, tokenPath := env["MNEMO_WARMUP_NODECACHE_CA_FILE"], env["MNEMO_WARMUP_NODECACHE_TOKEN_FILE"]
	if caPath == "" || tokenPath == "" {
		return nil, errors.New("cache CA and token files are required")
	}
	ca, err := readBoundedFile(caPath, 1<<20)
	if err != nil {
		return nil, errors.New("cannot read cache CA")
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(ca) {
		return nil, errors.New("invalid cache CA")
	}
	timeout := 15
	if configured := env["MNEMO_WARMUP_NODECACHE_TIMEOUT_SECONDS"]; configured != "" {
		timeout, err = strconv.Atoi(configured)
		if err != nil || timeout < 1 || timeout > 120 {
			return nil, errors.New("invalid cache timeout")
		}
	}
	u.Path = "/v1/blobs"
	return &nodeCacheClient{
		endpoint: u.String(), tokenFile: tokenPath,
		client: &http.Client{
			Timeout: time.Duration(timeout) * time.Second,
			Transport: &http.Transport{
				Proxy: nil, DialContext: (&net.Dialer{Timeout: 3 * time.Second}).DialContext,
				TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots},
				TLSHandshakeTimeout: 3 * time.Second,
			},
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}, nil
}

func readBoundedFile(path string, limit int64) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil || int64(len(b)) > limit {
		return nil, errors.New("file exceeds its limit or cannot be read")
	}
	return b, nil
}

// download verifies cache bytes independently before atomically publishing
// them to the pod-local directory. A failed cache request leaves no output.
func (c *nodeCacheClient) download(registry, repository, proof string, want ociDescriptor, target string, timing func(int64, time.Duration), outcome func(string)) error {
	if !digestRe.MatchString(want.Digest) || want.Size < 0 {
		return errors.New("invalid cache blob descriptor")
	}
	token, err := readBoundedFile(c.tokenFile, 16<<10)
	if err != nil || strings.TrimSpace(string(token)) == "" {
		return errors.New("cannot read cache identity token")
	}
	body, err := json.Marshal(struct {
		Registry   string `json:"registry"`
		Repository string `json:"repository"`
		Digest     string `json:"digest"`
		Size       int64  `json:"size"`
	}{registry, repository, want.Digest, want.Size})
	if err != nil {
		return errors.New("cannot encode cache request")
	}
	req, err := http.NewRequest(http.MethodPost, c.endpoint, bytes.NewReader(body))
	if err != nil {
		return errors.New("cannot create cache request")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(string(token)))
	if proof != "" {
		req.Header.Set("X-Mnemo-Registry-Authorization", proof)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return errors.New("cache request failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return errors.New("cache request was rejected")
	}
	tmp, err := os.CreateTemp(filepath.Dir(target), filepath.Base(target)+".*.part")
	if err != nil {
		return errors.New("cannot create cache output")
	}
	defer os.Remove(tmp.Name())
	h := sha256.New()
	started := time.Now()
	n, copyErr := io.Copy(io.MultiWriter(tmp, h), io.LimitReader(resp.Body, want.Size))
	if copyErr == nil && n == want.Size {
		var extra [1]byte
		if count, readErr := io.ReadFull(resp.Body, extra[:]); count != 0 {
			copyErr = errors.New("cache response exceeds declared size")
		} else if readErr != io.EOF {
			copyErr = readErr
		}
	}
	if closeErr := tmp.Close(); copyErr == nil {
		copyErr = closeErr
	}
	if copyErr != nil || n != want.Size || "sha256:"+hex.EncodeToString(h.Sum(nil)) != want.Digest {
		return errors.New("cache response failed verification")
	}
	if err := os.Rename(tmp.Name(), target); err != nil {
		return errors.New("cannot publish cache output")
	}
	if timing != nil {
		timing(n, time.Since(started))
	}
	if outcome != nil {
		switch status := resp.Header.Get("X-Mnemo-Cache-Outcome"); status {
		case "hit", "miss", "shared":
			outcome(status)
		default:
			outcome("served")
		}
	}
	return nil
}

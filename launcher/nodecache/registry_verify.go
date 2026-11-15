package nodecache

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

var repositoryPattern = regexp.MustCompile(`^[a-z0-9]+([._/-][a-z0-9]+)*$`)

// OCIRegistryVerifier confirms caller access using an authenticated GET for
// the blob in its exact repository. The operator explicitly allows each
// registry host; credentials are never sent to a redirect or another host.
type OCIRegistryVerifier struct {
	// allowed maps each registry host the operator configured to that same configured string. Requests are only ever
	// sent to the value read from here, never to the host string a caller supplied (that only selects the entry).
	allowed map[string]string
	client  *http.Client
}

// NewOCIRegistryVerifier requires an HTTPS client configured to validate the
// registry's TLS certificates. Registry hosts must be supplied by the operator.
func NewOCIRegistryVerifier(registries []string, client *http.Client) (*OCIRegistryVerifier, error) {
	if client == nil || len(registries) == 0 {
		return nil, errors.New("registry allowlist and HTTPS client are required")
	}
	v := &OCIRegistryVerifier{allowed: make(map[string]string, len(registries))}
	for _, host := range registries {
		if !validRegistryHost(host) {
			return nil, errors.New("invalid allowed registry host")
		}
		v.allowed[host] = host
	}
	c := *client
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if c.Timeout <= 0 || c.Timeout > 10*time.Second {
		c.Timeout = 10 * time.Second
	}
	v.client = &c
	return v, nil
}

func validRegistryHost(host string) bool {
	if host == "" || len(host) > 255 || strings.ContainsAny(host, " \t\r\n\\") {
		return false
	}
	u, err := url.Parse("https://" + host)
	return err == nil && u.Host == host && u.Hostname() != "" && u.User == nil && u.Path == "" && u.RawQuery == "" && u.Fragment == ""
}

// Verify accepts an ephemeral Basic or Bearer authorization header. Empty
// authorization is permitted for public registries. Other schemes are rejected.
func (v *OCIRegistryVerifier) Verify(ctx context.Context, scope RepositoryScope, digest, proof string) error {
	registry, ok := v.allowed[scope.Registry]
	if !ok || !repositoryPattern.MatchString(scope.Repository) || !digestPattern.MatchString(digest) || !validRegistryAuthorization(proof) {
		return ErrDenied
	}
	u := &url.URL{Scheme: "https", Host: registry, Path: "/v2/" + scope.Repository + "/blobs/" + digest}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return ErrDenied
	}
	if proof != "" {
		req.Header.Set("Authorization", proof)
	}
	// Ask for one byte to exercise pull authorization without downloading the
	// archive a second time. If Range is ignored, close the body immediately.
	req.Header.Set("Range", "bytes=0-0")
	resp, err := v.client.Do(req)
	if err != nil {
		return errors.New("registry access check failed")
	}
	if isRegistryRedirect(resp.StatusCode) {
		location := resp.Header.Get("Location")
		resp.Body.Close()
		target, ok := sameRegistryRedirect(registry, location)
		if !ok {
			return ErrDenied
		}
		redirect, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
		if err != nil {
			return ErrDenied
		}
		redirect.Header.Set("Range", "bytes=0-0")
		// The redirect must remain on the exact registry origin before the
		// caller's credential can be reused.
		if proof != "" {
			redirect.Header.Set("Authorization", proof)
		}
		resp, err = v.client.Do(redirect)
		if err != nil {
			return errors.New("registry redirect check failed")
		}
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		return ErrDenied
	}
	return nil
}

func isRegistryRedirect(status int) bool {
	switch status {
	case http.StatusMovedPermanently, http.StatusFound, http.StatusSeeOther, http.StatusTemporaryRedirect, http.StatusPermanentRedirect:
		return true
	default:
		return false
	}
}

// sameRegistryRedirect permits only HTTPS redirects that resolve to the
// registry's exact origin. Relative Artifact Registry download paths are
// supported; credentials are never forwarded to another host or scheme.
func sameRegistryRedirect(registry, location string) (*url.URL, bool) {
	if location == "" {
		return nil, false
	}
	base := &url.URL{Scheme: "https", Host: registry}
	reference, err := url.Parse(location)
	if err != nil {
		return nil, false
	}
	target := base.ResolveReference(reference)
	if target.Scheme != "https" || !strings.EqualFold(target.Host, registry) || target.User != nil || target.Fragment != "" {
		return nil, false
	}
	return target, true
}

func validRegistryAuthorization(proof string) bool {
	if len(proof) > 8192 {
		return false
	}
	if proof == "" {
		return true
	}
	parts := strings.Fields(proof)
	return len(parts) == 2 && (strings.EqualFold(parts[0], "Bearer") || strings.EqualFold(parts[0], "Basic")) && strings.Join(parts, " ") == proof
}

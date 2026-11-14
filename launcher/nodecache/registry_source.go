package nodecache

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
)

// OCIRegistrySource reads blobs using an operator-provided credential scoped
// to each exact repository. It does not follow redirects or log credentials.
type OCIRegistrySource struct {
	credentials map[RepositoryScope]string
	client      *http.Client
}

// NewOCIRegistrySource takes a map of allowed repositories to credential file
// paths. A file contains a Basic or Bearer HTTP authorization value; an empty
// path selects anonymous access. The client must validate TLS certificates.
// Calls must have a deadline, including while the returned body is read.
func NewOCIRegistrySource(credentials map[RepositoryScope]string, client *http.Client) (*OCIRegistrySource, error) {
	if len(credentials) == 0 || client == nil {
		return nil, errors.New("repository allowlist and HTTPS client are required")
	}
	s := &OCIRegistrySource{credentials: make(map[RepositoryScope]string, len(credentials))}
	for scope, path := range credentials {
		if !validRegistryHost(scope.Registry) || !repositoryPattern.MatchString(scope.Repository) {
			return nil, errors.New("invalid allowed registry repository")
		}
		s.credentials[scope] = path
	}
	c := *client
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	s.client = &c
	return s, nil
}

func (s *OCIRegistrySource) Open(ctx context.Context, scope RepositoryScope, digest string) (io.ReadCloser, error) {
	path, allowed := s.credentials[scope]
	if !allowed || !digestPattern.MatchString(digest) {
		return nil, ErrDenied
	}
	if _, ok := ctx.Deadline(); !ok {
		return nil, errors.New("registry download requires a deadline")
	}
	proof := ""
	if path != "" {
		file, err := os.Open(path)
		if err != nil {
			return nil, errors.New("cannot read registry source credential")
		}
		b, readErr := io.ReadAll(io.LimitReader(file, 8193))
		closeErr := file.Close()
		if readErr != nil || closeErr != nil || len(b) > 8192 {
			return nil, errors.New("cannot read registry source credential")
		}
		proof = strings.TrimSpace(string(b))
		if proof == "" || !validRegistryAuthorization(proof) {
			return nil, errors.New("invalid registry source credential")
		}
	}
	u := &url.URL{Scheme: "https", Host: scope.Registry, Path: "/v2/" + scope.Repository + "/blobs/" + digest}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, errors.New("cannot create registry download")
	}
	if proof != "" {
		req.Header.Set("Authorization", proof)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, errors.New("registry download failed")
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, errors.New("registry download was rejected")
	}
	return resp.Body, nil
}

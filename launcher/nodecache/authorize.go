package nodecache

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// ErrDenied deliberately does not disclose whether a subject or repository exists.
var ErrDenied = errors.New("cache access denied")

// Reviewer validates a Kubernetes service-account token and returns its username.
type Reviewer interface {
	Review(context.Context, string) (string, error)
}

// Grant is an operator-authorized service account and exact OCI repository.
type Grant struct {
	Subject, Registry, Repository string
}

// Authorizer checks every request, including hits, against exact operator grants.
type Authorizer struct {
	reviewer Reviewer
	grants   map[Grant]struct{}
}

func NewAuthorizer(reviewer Reviewer, grants []Grant) (*Authorizer, error) {
	if reviewer == nil {
		return nil, errors.New("token reviewer is required")
	}
	a := &Authorizer{reviewer: reviewer, grants: make(map[Grant]struct{}, len(grants))}
	for _, grant := range grants {
		parts := strings.Split(grant.Subject, ":")
		if len(parts) != 4 || parts[0] != "system" || parts[1] != "serviceaccount" ||
			parts[2] == "" || parts[3] == "" || grant.Registry == "" || grant.Repository == "" ||
			strings.ContainsAny(grant.Subject+grant.Registry+grant.Repository, " \t\r\n") {
			return nil, errors.New("invalid cache grant")
		}
		a.grants[grant] = struct{}{}
	}
	return a, nil
}

func (a *Authorizer) Authorize(ctx context.Context, token, registry, repository string) error {
	if token == "" || registry == "" || repository == "" {
		return ErrDenied
	}
	subject, err := a.reviewer.Review(ctx, token)
	if err != nil {
		return fmt.Errorf("review cache caller: %w", err)
	}
	if _, ok := a.grants[Grant{Subject: subject, Registry: registry, Repository: repository}]; !ok {
		return ErrDenied
	}
	return nil
}

// KubernetesReviewer uses the Kubernetes TokenReview API. The API client must
// trust the cluster CA; the service's projected reviewer token is read per call
// so Kubernetes can rotate it. No caller token is retained or logged.
type KubernetesReviewer struct {
	endpoint          string
	audience          string
	reviewerTokenFile string
	client            *http.Client
}

func NewKubernetesReviewer(apiURL, audience, reviewerTokenFile string, client *http.Client) (*KubernetesReviewer, error) {
	u, err := url.Parse(apiURL)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" || audience == "" || reviewerTokenFile == "" || client == nil {
		return nil, errors.New("HTTPS Kubernetes API, audience, reviewer token file and client are required")
	}
	// Never forward a reviewer credential or caller token on an API redirect.
	c := *client
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if c.Timeout <= 0 || c.Timeout > 5*time.Second {
		c.Timeout = 5 * time.Second
	}
	return &KubernetesReviewer{endpoint: u.String() + "/apis/authentication.k8s.io/v1/tokenreviews", audience: audience, reviewerTokenFile: reviewerTokenFile, client: &c}, nil
}

func (r *KubernetesReviewer) Review(ctx context.Context, token string) (string, error) {
	if token == "" {
		return "", ErrDenied
	}
	reviewerToken, err := os.ReadFile(r.reviewerTokenFile)
	if err != nil || strings.TrimSpace(string(reviewerToken)) == "" {
		return "", errors.New("cannot read Kubernetes reviewer credential")
	}
	body, err := json.Marshal(map[string]any{
		"apiVersion": "authentication.k8s.io/v1", "kind": "TokenReview",
		"spec": map[string]any{"token": token, "audiences": []string{r.audience}},
	})
	if err != nil {
		return "", errors.New("cannot encode token review")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.endpoint, bytes.NewReader(body))
	if err != nil {
		return "", errors.New("cannot create token review")
	}
	req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(string(reviewerToken)))
	req.Header.Set("Content-Type", "application/json")
	resp, err := r.client.Do(req)
	if err != nil {
		return "", errors.New("Kubernetes token review failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		return "", errors.New("Kubernetes token review was rejected")
	}
	var result struct {
		Status struct {
			Authenticated bool `json:"authenticated"`
			User          struct {
				Username string `json:"username"`
			} `json:"user"`
			Audiences []string `json:"audiences"`
		} `json:"status"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&result); err != nil || !result.Status.Authenticated {
		return "", ErrDenied
	}
	for _, audience := range result.Status.Audiences {
		if audience == r.audience && strings.HasPrefix(result.Status.User.Username, "system:serviceaccount:") {
			return result.Status.User.Username, nil
		}
	}
	return "", ErrDenied
}

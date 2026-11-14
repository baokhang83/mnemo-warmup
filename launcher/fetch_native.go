package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// The native fetch (docs/adr/0011): looks the archive up in the registry without starting a JVM. It returns "done" when it has an
// answer that is final (an archive was verified and written, or the registry could not give one) and "not decided" when the Java
// tool must do the work: an unsupported situation, or no archive found (a miss is re-checked, see the ADR).

const (
	ociManifestType = "application/vnd.oci.image.manifest.v1+json"
	ociConfigType   = "application/vnd.mnemo.warmup.manifest.v1+json"
	ociLayerType    = "application/vnd.mnemo.warmup.archive.v1"
	maxRedirects    = 5
)

// dialTimeout bounds connecting to the registry; a variable so a test can shorten it.
var dialTimeout = 10 * time.Second

var (
	digestRe = regexp.MustCompile(`^sha256:[0-9a-f]{64}$`)
	paramRe  = regexp.MustCompile(`(\w+)="([^"]*)"`)
	repoRe   = regexp.MustCompile(`^[a-z0-9]+([._/-][a-z0-9]+)*$`)
	hex64Re  = regexp.MustCompile(`^[0-9a-f]{64}$`)

	errNotFound = errors.New("not found")
)

// registryError is a final failure of the registry exchange: reported, never retried by the Java tool.
type registryError struct{ msg string }

func (e registryError) Error() string { return e.msg }

// needJava means the native step cannot make the decision; the Java tool is asked.
type needJava struct{ why string }

func (e needJava) Error() string { return e.why }

type fetchOptions struct {
	out, store, jar, cacheType, imageDigest, registryAuth, javaHome string
	hasImageDigest                                                  bool
	jvmArgs                                                         []string
	plainHttp, strict                                               bool
	timeout                                                         time.Duration
}

// parseFetchOptions accepts the arguments the injector and the chart pass. Anything else is the Java tool's.
func parseFetchOptions(args []string) (fetchOptions, bool) {
	o := fetchOptions{out: ".mnemo-warmup/cache", cacheType: "auto", timeout: 120 * time.Second}
	for i := 0; i < len(args); i++ {
		name, val, hasVal := strings.Cut(args[i], "=")
		switch name {
		case "--plain-http", "--strict":
			if hasVal {
				return o, false
			}
			if name == "--strict" {
				o.strict = true
			} else {
				o.plainHttp = true
			}
			continue
		case "--out", "--store", "--jar", "--work-dir", "--cache-type", "--jvm-arg", "--image-digest", "--registry-auth", "--java-home", "--store-timeout-seconds":
		default:
			return o, false
		}
		if !hasVal {
			i++
			if i >= len(args) {
				return o, false
			}
			val = args[i]
		}
		switch name {
		case "--out":
			o.out = val
		case "--store":
			o.store = val
		case "--jar":
			o.jar = val
		case "--cache-type":
			o.cacheType = val
		case "--jvm-arg":
			o.jvmArgs = append(o.jvmArgs, val)
		case "--image-digest":
			o.imageDigest, o.hasImageDigest = val, true
		case "--registry-auth":
			o.registryAuth = val
		case "--java-home":
			o.javaHome = val
		case "--store-timeout-seconds":
			var secs int
			if _, err := fmt.Sscanf(val, "%d", &secs); err != nil || secs <= 0 || fmt.Sprint(secs) != val {
				return o, false
			}
			o.timeout = time.Duration(secs) * time.Second
		}
	}
	return o, o.store != "" && o.jar != "" && o.out != ""
}

// nativeFetch returns (exit code, true) when it has the final answer, and (0, false) when the Java tool must run.
func (l launcher) nativeFetch(args []string, strict bool) (int, bool) {
	if slicesContains(l.environ, "MNEMO_WARMUP_NATIVE_FETCH=0") {
		return 0, false
	}
	o, ok := parseFetchOptions(args)
	if !ok {
		return 0, false
	}
	env := envMap(l.environ)
	t := newTimer(env["MNEMO_WARMUP_TIMING"] == "1", "native fetch")
	cpuBefore, cpuMeasured := cgroupCPUUsageUsec()
	hit, err := l.nativeLookup(o, env, t)
	if cpuMeasured && env["MNEMO_WARMUP_TIMING"] == "1" {
		if cpuAfter, ok := cgroupCPUUsageUsec(); ok && cpuAfter >= cpuBefore {
			fmt.Fprintf(l.stderr, "fetch: timing: cgroup-cpu-usage=%d usec\n", cpuAfter-cpuBefore)
		}
	}
	var nj needJava
	switch {
	case errors.As(err, &nj):
		if l.timingOn() {
			l.note("native fetch handed over to the Java tool: %s", nj.why)
		}
		return 0, false
	case err != nil:
		fmt.Fprintf(l.stderr, "fetch: %v\n", err)
		fmt.Fprintf(l.stdout, "No usable archive; the application will start without one.\n")
		if s := t.summary(); s != "" {
			fmt.Fprintf(l.stderr, "fetch: %s\n", s)
		}
		if strict {
			l.note("%s (--strict: failing)", "fetch could not get an archive")
			return 1, true
		}
		return 0, true
	case hit == nil:
		return 0, false // every candidate answered "not found": the Java tool confirms
	}
	if s := t.summary(); s != "" {
		fmt.Fprintf(l.stderr, "fetch: %s\n", s)
	}
	fmt.Fprintf(l.stdout, "Archive: %s (%s), fingerprint %s\n", hit.archive, hit.cacheType, hit.fingerprint)
	fmt.Fprintf(l.stdout, "Flags: %s\n", hit.flags)
	return 0, true
}

// cgroupCPUUsageUsec reads this container's cumulative CPU time from cgroup v2. The fetch init
// container has its own cgroup, so the delta measures CPU used while native fetch is running.
func cgroupCPUUsageUsec() (uint64, bool) {
	b, err := os.ReadFile("/sys/fs/cgroup/cpu.stat")
	if err != nil {
		return 0, false
	}
	return parseCgroupCPUUsageUsec(b)
}

func parseCgroupCPUUsageUsec(data []byte) (uint64, bool) {
	for _, line := range strings.Split(string(data), "\n") {
		key, value, ok := strings.Cut(line, " ")
		if !ok || key != "usage_usec" {
			continue
		}
		usage, err := strconv.ParseUint(strings.TrimSpace(value), 10, 64)
		return usage, err == nil
	}
	return 0, false
}

func slicesContains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

type fetchHit struct{ archive, flags, cacheType, fingerprint string }

func (l launcher) nativeLookup(o fetchOptions, env map[string]string, t *timer) (*fetchHit, error) {
	if strings.Contains(env["JAVA_TOOL_OPTIONS"]+" "+env["JDK_JAVA_OPTIONS"], "proxy") {
		return nil, needJava{"a proxy is configured for the JVM"}
	}
	home := o.javaHome
	if home == "" {
		home = strings.TrimSpace(env["JAVA_HOME"])
	}
	if home == "" {
		java := l.locator.find("")
		if java == "" {
			return nil, needJava{"no java found"}
		}
		real, err := filepath.EvalSymlinks(java)
		if err != nil {
			return nil, needJava{"cannot resolve java"}
		}
		home = filepath.Dir(filepath.Dir(real))
	}
	rel, ok := readRelease(home)
	if !ok {
		return nil, needJava{"no usable release file in " + home}
	}
	feature, ok := featureVersion(rel.version)
	if !ok {
		return nil, needJava{"unreadable Java version"}
	}
	t.lap("jdk-detect")

	var candidates []string
	switch o.cacheType {
	case "auto":
		if feature >= 25 {
			candidates = []string{"LEYDEN_AOT", "APP_CDS"}
		} else {
			candidates = []string{"APP_CDS"}
		}
	case "appcds":
		candidates = []string{"APP_CDS"}
	default:
		return nil, needJava{"cache type " + o.cacheType}
	}

	jar, err := filepath.Abs(o.jar)
	if err != nil {
		return nil, needJava{"bad jar path"}
	}
	if fat, ok := isFatJar(jar); !ok || fat {
		return nil, needJava{"the JAR is missing, unreadable, or needs extraction"}
	}
	files, ok := classpathFiles(jar)
	if !ok {
		return nil, needJava{"classpath files"}
	}
	entries, ok := describeClasspath(jar, files)
	if !ok {
		return nil, needJava{"cannot hash the classpath"}
	}
	t.lap("extract-and-hash-classpath")

	reg, err := l.openRegistry(o, env)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(o.out, 0o755); err != nil {
		return nil, needJava{"cannot create the output directory"}
	}
	if err := clearOutput(o.out); err != nil {
		return nil, needJava{"cannot clear the output directory: " + err.Error()}
	}
	flags := relevantJvmFlags(o.jvmArgs, env)
	var digest *string
	if o.hasImageDigest {
		digest = &o.imageDigest
	}
	for _, cacheType := range candidates {
		live := manifestJSON{SchemaVersion: schemaV1, CacheType: cacheType, ImageDigest: digest, JvmFlags: flags, Classpath: entries}
		live.JDK.Vendor, live.JDK.RuntimeVersion, live.JDK.Version, live.JDK.FeatureVersion = rel.vendor, rel.runtime, rel.version, int64(feature)
		fp := fingerprint(live)
		hit, err := reg.fetchArchive(fp, live, o.out)
		if errors.Is(err, errNotFound) {
			continue
		}
		if err != nil {
			_ = clearOutput(o.out)
			return nil, err
		}
		t.lap("store-lookup-download-verify")
		hit.cacheType = cacheType
		return hit, nil
	}
	t.lap("store-lookup-download-verify")
	return nil, nil
}

// clearOutput removes what a previous fetch published: the flags file first (emptied if it cannot be deleted), then the manifest.
func clearOutput(dir string) error {
	flags := filepath.Join(dir, flagsFile)
	if err := os.Remove(flags); err != nil && !os.IsNotExist(err) {
		if err := os.WriteFile(flags, nil, 0o644); err != nil {
			return fmt.Errorf("cannot remove or empty the stale %s: %w", flags, err)
		}
	}
	if err := os.Remove(filepath.Join(dir, manifestFile)); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("cannot remove the stale manifest in %s: %w", dir, err)
	}
	return nil
}

// ---- the registry ----

type registry struct {
	base           *url.URL
	repo           string
	client         *http.Client
	user           string
	pass           string
	hasAuth        bool
	authz          string
	timeout        time.Duration
	transferTiming func(int64, time.Duration)
	nodeCache      *nodeCacheClient
	cacheNote      func(string)
}

func (l launcher) openRegistry(o fetchOptions, env map[string]string) (*registry, error) {
	rest, ok := strings.CutPrefix(o.store, "oci://")
	if !ok {
		return nil, needJava{"store is not an oci:// reference"}
	}
	host, repo, ok := strings.Cut(rest, "/")
	if !ok || host == "" || repo == "" || !repoRe.MatchString(repo) {
		return nil, needJava{"unsupported store reference"}
	}
	scheme := "https"
	if o.plainHttp {
		scheme = "http"
	}
	r := &registry{base: &url.URL{Scheme: scheme, Host: host}, repo: repo, timeout: o.timeout}
	cache, cacheErr := newNodeCacheClient(env)
	if cacheErr != nil {
		l.note("node cache configuration unavailable; using registry")
	} else {
		r.nodeCache = cache
	}
	r.cacheNote = func(message string) { l.note("%s", message) }
	if env["MNEMO_WARMUP_TIMING"] == "1" {
		r.transferTiming = func(bytes int64, elapsed time.Duration) {
			fmt.Fprintf(l.stderr, "fetch: timing: archive-transfer-hash-write=%d ms, archive-bytes=%d\n", elapsed.Milliseconds(), bytes)
		}
	}
	auth := o.registryAuth
	if auth == "" {
		auth = env["MNEMO_WARMUP_REGISTRY_AUTH"]
	}
	if auth != "" {
		user, pass, found, err := dockerConfigLogin(auth, host)
		if err != nil {
			return nil, needJava{"registry credentials: " + err.Error()}
		}
		r.user, r.pass, r.hasAuth = user, pass, found
	}
	if !r.hasAuth && strings.Contains(host, "amazonaws.com") {
		return nil, needJava{"an AWS registry without a Docker-config login uses workload identity"}
	}
	r.client = &http.Client{
		// No proxy: the JDK's client does not use the environment's proxy variables either. The connect timeout matches the Java
		// store's (10 s): a registry whose address silently drops packets must not hold the pod for the operating system's
		// minutes-long TCP timeout (§3).
		Transport: &http.Transport{
			Proxy:                 nil,
			DialContext:           (&net.Dialer{Timeout: dialTimeout}).DialContext,
			TLSHandshakeTimeout:   10 * time.Second,
			ResponseHeaderTimeout: o.timeout,
			MaxIdleConns:          2,
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	return r, nil
}

// dockerConfigLogin reads the entry for host from a Docker-config JSON file, like RegistryCredentials.fromDockerConfig.
func dockerConfigLogin(path, host string) (user, pass string, found bool, err error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", "", false, err
	}
	var cfg struct {
		Auths map[string]struct {
			Auth     string `json:"auth"`
			Username string `json:"username"`
			Password string `json:"password"`
		} `json:"auths"`
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		return "", "", false, err
	}
	for key, v := range cfg.Auths {
		k := key
		if !strings.Contains(k, "://") {
			k = "//" + k
		}
		u, perr := url.Parse(strings.ReplaceAll(k, " ", ""))
		authority := key
		if perr == nil && u.Host != "" {
			authority = u.Host
		}
		if !strings.EqualFold(authority, host) {
			continue
		}
		if strings.TrimSpace(v.Auth) != "" {
			dec, derr := base64.StdEncoding.DecodeString(strings.TrimSpace(v.Auth))
			if derr != nil {
				return "", "", false, errors.New("the auth value is not valid base64")
			}
			if i := strings.IndexByte(string(dec), ':'); i > 0 {
				return string(dec[:i]), string(dec[i+1:]), true, nil
			}
		}
		if v.Username != "" && v.Password != "" {
			return v.Username, v.Password, true, nil
		}
		return "", "", false, nil
	}
	return "", "", false, nil
}

func (r *registry) uri(path string) *url.URL {
	u := *r.base
	u.Path = path
	return &u
}

// classify turns a transport error into a registry error, or a request to ask the Java tool when the failure may be specific to
// the native client (a certificate the operating system does not trust).
func classify(what string, err error) error {
	var unknownCA x509.UnknownAuthorityError
	var hostErr x509.HostnameError
	var invalid x509.CertificateInvalidError
	var verify *tls.CertificateVerificationError
	if errors.As(err, &unknownCA) || errors.As(err, &hostErr) || errors.As(err, &invalid) || errors.As(err, &verify) {
		return needJava{"the registry certificate is not trusted by the operating system: " + err.Error()}
	}
	return registryError{fmt.Sprintf("%s: %v", what, err)}
}

// exchange sends one logical request: answers a 401 challenge once, follows redirects for GET without sending the login to
// another origin, and never follows an https registry to http. The request is cancelled when no byte has arrived for the
// timeout (a server that sends headers and then stalls must not hold the init container); the session's touch() postpones
// that, and its stop() must be called when the body is done.
func (r *registry) exchange(method string, u *url.URL, accept string) (*http.Response, *session, error) {
	ctx, cancel := context.WithCancel(context.Background())
	var stalled atomic.Bool
	idle := time.AfterFunc(r.timeout, func() { stalled.Store(true); cancel() })
	s := &session{timer: idle, d: r.timeout, cancel: cancel, stalled: &stalled}
	stop := s.stop
	fail := func(what string, err error) (*http.Response, *session, error) {
		stop()
		if stalled.Load() {
			return nil, nil, registryError{fmt.Sprintf("registry %s did not answer within %d s", r.base.Host, int(r.timeout.Seconds()))}
		}
		return nil, nil, classify(what, err)
	}
	cur := u
	authenticated := false
	for hops := 0; hops <= maxRedirects; hops++ {
		if r.base.Scheme == "https" && cur.Scheme != "https" {
			stop()
			return nil, nil, registryError{"refusing to follow " + r.base.Host + " to a non-https URL"}
		}
		same := strings.EqualFold(cur.Scheme, r.base.Scheme) && strings.EqualFold(cur.Host, r.base.Host)
		req, _ := http.NewRequestWithContext(ctx, method, cur.String(), nil)
		if accept != "" {
			req.Header.Set("Accept", accept)
		}
		if same && r.authz != "" {
			req.Header.Set("Authorization", r.authz)
		}
		resp, err := r.client.Do(req)
		if err != nil {
			return fail("cannot reach registry "+r.base.Host, err)
		}
		switch {
		case resp.StatusCode == 401 && same && !authenticated:
			challenge := resp.Header.Get("Www-Authenticate")
			resp.Body.Close()
			if err := r.authorize(ctx, challenge); err != nil {
				stop()
				return nil, nil, err
			}
			if r.authz == "" {
				stop()
				return nil, nil, registryError{"registry " + r.base.Host + " requires authentication and no credentials were provided"}
			}
			authenticated = true
			hops--
			continue
		case resp.StatusCode == 301 || resp.StatusCode == 302 || resp.StatusCode == 303 || resp.StatusCode == 307 || resp.StatusCode == 308:
			loc := resp.Header.Get("Location")
			resp.Body.Close()
			if loc == "" {
				stop()
				return nil, nil, registryError{"registry redirected without a Location"}
			}
			next, err := cur.Parse(loc)
			if err != nil {
				stop()
				return nil, nil, registryError{"registry sent a bad redirect"}
			}
			cur = next
			continue
		}
		return resp, s, nil
	}
	stop()
	return nil, nil, registryError{"too many redirects from " + r.base.Host}
}

type session struct {
	timer   *time.Timer
	d       time.Duration
	cancel  context.CancelFunc
	stalled *atomic.Bool
}

func (s *session) touch() { s.timer.Reset(s.d) }
func (s *session) stop()  { s.timer.Stop(); s.cancel() }

// authorize sets the Authorization header from a WWW-Authenticate challenge (Bearer token or Basic).
func (r *registry) authorize(ctx context.Context, challenge string) error {
	switch {
	case len(challenge) >= 6 && strings.EqualFold(challenge[:6], "Bearer"):
		params := map[string]string{}
		for _, m := range paramRe.FindAllStringSubmatch(challenge, -1) {
			params[strings.ToLower(m[1])] = m[2]
		}
		realm := params["realm"]
		if realm == "" {
			return registryError{"registry sent a Bearer challenge without a realm"}
		}
		ru, err := url.Parse(realm)
		if err != nil {
			return registryError{"registry sent a bad token realm"}
		}
		if r.base.Scheme == "https" && ru.Scheme != "https" {
			return registryError{"refusing to follow " + r.base.Host + " to a non-https URL"}
		}
		scope := params["scope"]
		if scope == "" {
			scope = "repository:" + r.repo + ":pull"
		}
		q := ru.Query()
		if svc, ok := params["service"]; ok {
			q.Set("service", svc)
		}
		q.Set("scope", scope)
		ru.RawQuery = q.Encode()
		req, _ := http.NewRequestWithContext(ctx, "GET", ru.String(), nil)
		if r.hasAuth {
			req.SetBasicAuth(r.user, r.pass)
		}
		resp, err := r.client.Do(req)
		if err != nil {
			return classify("cannot reach the registry token service", err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			hint := ""
			if !r.hasAuth {
				hint = "; no credentials were provided"
			}
			return registryError{fmt.Sprintf("registry token service refused the request (HTTP %d)%s", resp.StatusCode, hint)}
		}
		var tok struct {
			Token       string `json:"token"`
			AccessToken string `json:"access_token"`
		}
		if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&tok); err != nil {
			return registryError{"registry token response is not valid JSON"}
		}
		token := tok.Token
		if token == "" {
			token = tok.AccessToken
		}
		if strings.TrimSpace(token) == "" {
			return registryError{"registry token response has no token"}
		}
		r.authz = "Bearer " + token
	case len(challenge) >= 5 && strings.EqualFold(challenge[:5], "Basic") && r.hasAuth:
		r.authz = "Basic " + base64.StdEncoding.EncodeToString([]byte(r.user+":"+r.pass))
	default:
		r.authz = ""
	}
	return nil
}

// get reads a small response fully (at most limit bytes). A 404 is errNotFound.
func (r *registry) get(path, accept string, limit int64) ([]byte, error) {
	resp, sess, err := r.exchange("GET", r.uri(path), accept)
	if err != nil {
		return nil, err
	}
	defer sess.stop()
	defer resp.Body.Close()
	if resp.StatusCode == 404 {
		return nil, errNotFound
	}
	if resp.StatusCode != 200 {
		return nil, registryError{fmt.Sprintf("registry %s could not read %s (HTTP %d)", r.base.Host, path, resp.StatusCode)}
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, classify("cannot read from registry "+r.base.Host, err)
	}
	if int64(len(b)) > limit {
		return nil, registryError{"registry sent more than the expected " + fmt.Sprint(limit) + " bytes for " + path}
	}
	return b, nil
}

type ociDescriptor struct {
	MediaType string `json:"mediaType"`
	Digest    string `json:"digest"`
	Size      int64  `json:"size"`
}

// fetchArchive downloads and verifies the archive stored for the fingerprint, and publishes it in out. errNotFound: no such tag.
func (r *registry) fetchArchive(fp string, live manifestJSON, out string) (*fetchHit, error) {
	hexPart, ok := strings.CutPrefix(fp, "sha256:")
	if !ok || !hex64Re.MatchString(hexPart) {
		return nil, needJava{"not a fingerprint"}
	}
	tag := "fp-" + hexPart
	body, err := r.get("/v2/"+r.repo+"/manifests/"+tag, ociManifestType, 1<<20)
	if err != nil {
		return nil, err
	}
	var image struct {
		Config ociDescriptor   `json:"config"`
		Layers []ociDescriptor `json:"layers"`
	}
	if err := json.Unmarshal(body, &image); err != nil || image.Config.MediaType != ociConfigType || len(image.Layers) != 1 ||
		image.Layers[0].MediaType != ociLayerType || !digestRe.MatchString(image.Config.Digest) || !digestRe.MatchString(image.Layers[0].Digest) {
		return nil, registryError{"tag " + tag + " in " + r.repo + " is not a mnemo-warmup archive"}
	}
	layer := image.Layers[0]

	cfg, err := r.get("/v2/"+r.repo+"/blobs/"+image.Config.Digest, "", 8<<20)
	if err != nil {
		if errors.Is(err, errNotFound) {
			return nil, registryError{"the manifest blob " + image.Config.Digest + " is missing"}
		}
		return nil, err
	}
	if sum := sha256.Sum256(cfg); image.Config.Digest != "sha256:"+hex.EncodeToString(sum[:]) {
		return nil, registryError{"digest mismatch for the manifest blob of " + tag}
	}
	stored, ok := parseManifest(cfg)
	if !ok {
		return nil, needJava{"the stored manifest is not one the native step understands"}
	}
	if stored.Fingerprint != fp || fingerprint(stored) != fp {
		return nil, registryError{"stored manifest for " + tag + " does not hash to that fingerprint"}
	}
	// The fingerprint covers the JDK, options and classpath, so it equals the live one; check the archive record explicitly.
	if stored.Archive == nil || !hex64Re.MatchString(stored.Archive.SHA256) || layer.Digest != "sha256:"+stored.Archive.SHA256 || layer.Size != stored.Archive.SizeBytes {
		return nil, registryError{"the archive the registry declares differs from the one the stored manifest records"}
	}
	name := filepath.Base(stored.Archive.File)
	if name == "." || name == ".." || name == string(filepath.Separator) || name != stored.Archive.File {
		return nil, registryError{"the stored manifest names an archive file outside the output directory"}
	}
	target := filepath.Join(out, name)
	cached := false
	if r.nodeCache != nil {
		if err := r.nodeCache.download(r.base.Host, r.repo, r.authz, layer, target, r.transferTiming,
			func(status string) { r.cacheNote("node cache " + status + ": verified archive") }); err == nil {
			cached = true
		} else {
			r.cacheNote("node cache unavailable or invalid; trying registry directly")
		}
	}
	if !cached {
		if err := r.download("/v2/"+r.repo+"/blobs/"+layer.Digest, layer, target); err != nil {
			return nil, err
		}
	}
	abs, err := filepath.Abs(target)
	if err != nil {
		return nil, needJava{"cannot make the archive path absolute"}
	}
	if err := writeAtomic(out, manifestFile, cfg); err != nil {
		return nil, registryError{err.Error()}
	}
	flag := runtimeFlag(stored.CacheType, abs)
	flagsPath := filepath.Join(out, flagsFile)
	if err := writeAtomic(out, flagsFile, []byte(flag+"\n")); err != nil {
		return nil, registryError{err.Error()}
	}
	return &fetchHit{archive: target, flags: flagsPath, fingerprint: fp}, nil
}

// download streams the layer to a temporary file next to target while hashing it, and renames it into place only when the size and
// SHA-256 are what the registry's manifest declared (§2). Nothing is left behind otherwise.
func (r *registry) download(path string, want ociDescriptor, target string) error {
	resp, sess, err := r.exchange("GET", r.uri(path), "")
	if err != nil {
		return err
	}
	defer sess.stop()
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return registryError{fmt.Sprintf("registry %s could not download the archive (HTTP %d)", r.base.Host, resp.StatusCode)}
	}
	tmp, err := os.CreateTemp(filepath.Dir(target), filepath.Base(target)+".*.part")
	if err != nil {
		return registryError{"cannot write the archive: " + err.Error()}
	}
	defer os.Remove(tmp.Name())
	h := sha256.New()
	started := time.Now()
	n, err := io.Copy(io.MultiWriter(tmp, h), &limitedIdle{r: resp.Body, left: want.Size + 1, touch: sess.touch})
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		if sess.stalled.Load() {
			return registryError{fmt.Sprintf("download stalled: no data for %d s", int(r.timeout.Seconds()))}
		}
		return classify("cannot download the archive", err)
	}
	if n != want.Size || "sha256:"+hex.EncodeToString(h.Sum(nil)) != want.Digest {
		return registryError{"downloaded archive failed verification (size or digest mismatch)"}
	}
	if r.transferTiming != nil {
		r.transferTiming(n, time.Since(started))
	}
	if err := os.Rename(tmp.Name(), target); err != nil {
		return registryError{"cannot publish the archive: " + err.Error()}
	}
	return nil
}

// limitedIdle reads at most left bytes (one more than the declared size, so an overlong body is detected, not truncated).
type limitedIdle struct {
	r     io.Reader
	left  int64
	touch func()
}

func (l *limitedIdle) Read(p []byte) (int, error) {
	if l.left <= 0 {
		return 0, io.EOF
	}
	if int64(len(p)) > l.left {
		p = p[:l.left]
	}
	n, err := l.r.Read(p)
	if n > 0 {
		l.touch()
	}
	l.left -= int64(n)
	return n, err
}

// writeAtomic writes dir/name by writing a temporary file and renaming it.
func writeAtomic(dir, name string, content []byte) error {
	tmp, err := os.CreateTemp(dir, name+".*.part")
	if err != nil {
		return fmt.Errorf("cannot write %s in %s: %w", name, dir, err)
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(content); err != nil {
		tmp.Close()
		return fmt.Errorf("cannot write %s in %s: %w", name, dir, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("cannot write %s in %s: %w", name, dir, err)
	}
	if err := os.Chmod(tmp.Name(), 0o644); err != nil {
		return fmt.Errorf("cannot write %s in %s: %w", name, dir, err)
	}
	return os.Rename(tmp.Name(), filepath.Join(dir, name))
}

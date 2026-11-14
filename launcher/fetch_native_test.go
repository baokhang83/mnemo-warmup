package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

// A fake OCI registry holding one artifact made from the Java-written fixture (testdata/compat): the config blob is the fixture's
// manifest, the layer is its pretend archive. The tag is the fingerprint the Java classes computed, so a hit proves that the Go
// side asks for the same tag Java would.

type fakeRegistry struct {
	*httptest.Server
	mu       sync.Mutex
	requests []string
	auth     []string // Authorization header of each request
	// behaviours
	layer     []byte // what the layer GET returns (default: the real archive)
	status    int    // when non-zero, every /v2/ request answers this
	needBasic bool
	needToken bool
	redirect  *httptest.Server // when set, the layer is served from here via 307
}

func newFakeRegistry(t *testing.T, f fixture) *fakeRegistry {
	t.Helper()
	cfg, err := os.ReadFile(f.path("cache/mnemo-warmup.manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	m, ok := parseManifest(cfg)
	if !ok {
		t.Fatal("fixture manifest does not parse")
	}
	archive, err := os.ReadFile(f.path("cache/app.jsa"))
	if err != nil {
		t.Fatal(err)
	}
	sum := func(b []byte) string { s := sha256.Sum256(b); return "sha256:" + hex.EncodeToString(s[:]) }
	image, _ := json.Marshal(map[string]any{
		"schemaVersion": 2, "mediaType": ociManifestType, "artifactType": "application/vnd.mnemo.warmup.v1",
		"config": map[string]any{"mediaType": ociConfigType, "digest": sum(cfg), "size": len(cfg)},
		"layers": []any{map[string]any{"mediaType": ociLayerType, "digest": sum(archive), "size": len(archive)}},
	})
	tag := "fp-" + strings.TrimPrefix(m.Fingerprint, "sha256:")
	fr := &fakeRegistry{layer: archive}
	fr.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fr.mu.Lock()
		fr.requests = append(fr.requests, r.Method+" "+r.URL.Path)
		fr.auth = append(fr.auth, r.Header.Get("Authorization"))
		status, needBasic, needToken, layer, redirect := fr.status, fr.needBasic, fr.needToken, fr.layer, fr.redirect
		fr.mu.Unlock()
		if r.URL.Path == "/token" {
			if u, p, _ := r.BasicAuth(); u != "puller" || p != "s3cret" {
				w.WriteHeader(401)
				return
			}
			fmt.Fprint(w, `{"token":"tok123"}`)
			return
		}
		if status != 0 {
			w.WriteHeader(status)
			return
		}
		if needBasic && r.Header.Get("Authorization") != "Basic "+base64.StdEncoding.EncodeToString([]byte("puller:s3cret")) {
			w.Header().Set("WWW-Authenticate", `Basic realm="registry"`)
			w.WriteHeader(401)
			return
		}
		if needToken && r.Header.Get("Authorization") != "Bearer tok123" {
			w.Header().Set("WWW-Authenticate", fmt.Sprintf(`Bearer realm="http://%s/token",service="reg"`, r.Host))
			w.WriteHeader(401)
			return
		}
		switch r.URL.Path {
		case "/v2/team/app/manifests/" + tag:
			w.Write(image)
		case "/v2/team/app/blobs/" + sum(cfg):
			w.Write(cfg)
		case "/v2/team/app/blobs/" + sum(archive):
			if redirect != nil {
				http.Redirect(w, r, redirect.URL+"/blob", http.StatusTemporaryRedirect)
				return
			}
			w.Write(layer)
		default:
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(fr.Close)
	return fr
}

func (fr *fakeRegistry) host() string { return strings.TrimPrefix(fr.URL, "http://") }

// fetchHarness is a launcher over the fixture's tree with the arguments the injector produces.
func fetchHarness(t *testing.T, f fixture, fr *fakeRegistry, extra ...string) (launcher, []string, *bytes.Buffer, *bytes.Buffer) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	l := launcher{locator: systemLocator(), root: "/tools", stdout: &stdout, stderr: &stderr, environ: append([]string{"PATH=/nonexistent"}, extra...)}
	args := []string{"--out", f.path("out"), "--jar", f.path("app/app.jar"), "--work-dir", f.path("work"), "--store", "oci://" + fr.host() + "/team/app",
		"--cache-type", "auto", "--jvm-arg=-Xmx512m", "--image-digest", "sha256:abc", "--java-home", f.path("javahome"), "--plain-http"}
	return l, args, &stdout, &stderr
}

func TestNativeFetchDownloadsVerifiesAndPublishes(t *testing.T) {
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	l, args, stdout, stderr := fetchHarness(t, f, fr, "MNEMO_WARMUP_TIMING=1")
	code, done := l.nativeFetch(args, false)
	if !done || code != 0 {
		t.Fatalf("done=%v code=%d stderr=%s", done, code, stderr)
	}
	archive := f.path("out/app.jsa")
	want, _ := os.ReadFile(f.path("cache/app.jsa"))
	if got, _ := os.ReadFile(archive); !bytes.Equal(got, want) {
		t.Fatal("archive content differs")
	}
	flags, _ := os.ReadFile(f.path("out/" + flagsFile))
	if string(flags) != "-XX:SharedArchiveFile="+archive+"\n" {
		t.Fatalf("flags %q", flags)
	}
	wantManifest, _ := os.ReadFile(f.path("cache/mnemo-warmup.manifest.json"))
	if got, _ := os.ReadFile(f.path("out/" + manifestFile)); !bytes.Equal(got, wantManifest) {
		t.Fatal("manifest differs")
	}
	if !strings.Contains(stdout.String(), "Archive: "+archive+" (APP_CDS), fingerprint sha256:") || !strings.Contains(stderr.String(), "fetch: timing: jdk-detect=") {
		t.Fatalf("stdout %q stderr %q", stdout, stderr)
	}
	if !strings.Contains(stderr.String(), "archive-transfer-hash-write=") || !strings.Contains(stderr.String(), "archive-bytes=") {
		t.Fatalf("missing archive transfer timing: %s", stderr)
	}
	entries, _ := os.ReadDir(f.path("out"))
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".part") {
			t.Fatalf("temporary file left behind: %s", e.Name())
		}
	}
}

// What the native fetch publishes is what the native planner then accepts: the two halves agree with each other.
func TestNativeFetchOutputIsAcceptedByTheNativePlanner(t *testing.T) {
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	l, args, _, stderr := fetchHarness(t, f, fr)
	if _, done := l.nativeFetch(args, false); !done {
		t.Fatalf("not done: %s", stderr)
	}
	f.env[0] = "MNEMO_WARMUP_CACHE_DIR=" + f.path("out")
	if got := f.plan(); !got.decided || !strings.HasPrefix(got.notes[0], "using APP_CDS archive") {
		t.Fatalf("planner: %+v", got)
	}
}

func TestNativeFetchAnswersAChallenge(t *testing.T) {
	t.Run("bearer token with credentials from a Docker config", func(t *testing.T) {
		f := newFixture(t)
		fr := newFakeRegistry(t, f)
		fr.needToken = true
		cfg := f.path("auth.json")
		login := base64.StdEncoding.EncodeToString([]byte("puller:s3cret"))
		os.WriteFile(cfg, []byte(fmt.Sprintf(`{"auths":{"%s":{"auth":"%s"}}}`, fr.host(), login)), 0o644)
		l, args, _, stderr := fetchHarness(t, f, fr)
		if _, done := l.nativeFetch(append(args, "--registry-auth", cfg), false); !done {
			t.Fatalf("not done: %s", stderr)
		}
		if _, err := os.Stat(f.path("out/" + flagsFile)); err != nil {
			t.Fatal("no archive published")
		}
	})
	t.Run("basic", func(t *testing.T) {
		f := newFixture(t)
		fr := newFakeRegistry(t, f)
		fr.needBasic = true
		cfg := f.path("auth.json")
		os.WriteFile(cfg, []byte(fmt.Sprintf(`{"auths":{"http://%s/v1/":{"username":"puller","password":"s3cret"}}}`, fr.host())), 0o644)
		l, args, _, _ := fetchHarness(t, f, fr, "MNEMO_WARMUP_REGISTRY_AUTH="+cfg)
		if _, done := l.nativeFetch(args, false); !done {
			t.Fatal("not done")
		}
		if _, err := os.Stat(f.path("out/" + flagsFile)); err != nil {
			t.Fatal("no archive published")
		}
	})
	t.Run("a registry that wants a login nobody provided is a final failure, and exits 0", func(t *testing.T) {
		f := newFixture(t)
		fr := newFakeRegistry(t, f)
		fr.needToken = true
		l, args, stdout, stderr := fetchHarness(t, f, fr)
		code, done := l.nativeFetch(args, false)
		if !done || code != 0 || !strings.Contains(stderr.String(), "token service refused") || !strings.Contains(stdout.String(), "No usable archive") {
			t.Fatalf("done=%v code=%d stdout=%q stderr=%q", done, code, stdout, stderr)
		}
		if _, err := os.Stat(f.path("out/" + flagsFile)); err == nil {
			t.Fatal("flags file must not exist")
		}
	})
}

func TestNativeFetchNeverSendsTheLoginToAnotherHost(t *testing.T) {
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	fr.needBasic = true
	var seen []string
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Header.Get("Authorization"))
		w.Write(fr.layer)
	}))
	defer other.Close()
	fr.redirect = other
	cfg := f.path("auth.json")
	os.WriteFile(cfg, []byte(fmt.Sprintf(`{"auths":{"%s":{"username":"puller","password":"s3cret"}}}`, fr.host())), 0o644)
	l, args, _, stderr := fetchHarness(t, f, fr, "MNEMO_WARMUP_REGISTRY_AUTH="+cfg)
	if _, done := l.nativeFetch(args, false); !done {
		t.Fatalf("not done: %s", stderr)
	}
	if len(seen) != 1 || seen[0] != "" {
		t.Fatalf("the other host saw Authorization %q", seen)
	}
	if _, err := os.Stat(f.path("out/app.jsa")); err != nil {
		t.Fatal("archive not published after the redirect")
	}
}

func TestNativeFetchDiscardsWhatFailsVerification(t *testing.T) {
	cases := map[string]func(fr *fakeRegistry){
		"same length, one byte changed": func(fr *fakeRegistry) { b := append([]byte{}, fr.layer...); b[0] ^= 1; fr.layer = b },
		"truncated":                     func(fr *fakeRegistry) { fr.layer = fr.layer[:len(fr.layer)-1] },
		"longer than declared":          func(fr *fakeRegistry) { fr.layer = append(append([]byte{}, fr.layer...), 'x') },
	}
	for name, corrupt := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			fr := newFakeRegistry(t, f)
			corrupt(fr)
			os.MkdirAll(f.path("out"), 0o755)
			os.WriteFile(f.path("out/"+flagsFile), []byte("-XX:SharedArchiveFile=/old.jsa\n"), 0o644) // left by an earlier fetch
			os.WriteFile(f.path("out/"+manifestFile), []byte("old"), 0o644)
			l, args, stdout, stderr := fetchHarness(t, f, fr)
			code, done := l.nativeFetch(args, false)
			if !done || code != 0 || !strings.Contains(stderr.String(), "failed verification") || !strings.Contains(stdout.String(), "No usable archive") {
				t.Fatalf("done=%v code=%d stdout=%q stderr=%q", done, code, stdout, stderr)
			}
			entries, _ := os.ReadDir(f.path("out"))
			if len(entries) != 0 {
				t.Fatalf("the output directory must be empty (no stale flags, manifest, archive or part file), has %v", entries)
			}
		})
	}
}

func TestNativeFetchWithAFailingRegistry(t *testing.T) {
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	fr.status = 503
	os.MkdirAll(f.path("out"), 0o755)
	os.WriteFile(f.path("out/"+flagsFile), []byte("-XX:SharedArchiveFile=/old.jsa\n"), 0o644)
	l, args, _, stderr := fetchHarness(t, f, fr)
	code, done := l.nativeFetch(args, false)
	if !done || code != 0 || !strings.Contains(stderr.String(), "HTTP 503") {
		t.Fatalf("done=%v code=%d stderr=%q", done, code, stderr)
	}
	if _, err := os.Stat(f.path("out/" + flagsFile)); err == nil {
		t.Fatal("a stale flags file must not survive a failed fetch")
	}
	if code, done := l.nativeFetch(args, true); !done || code != 1 {
		t.Fatalf("--strict: done=%v code=%d, want 1", done, code)
	}
	// the Java tool is not asked after a registry failure: it would wait on the same registry again
	if n := len(fr.requests); n < 2 {
		t.Fatalf("requests %v", fr.requests)
	}
}

func TestNativeFetchOnAnUnreachableRegistryIsFinal(t *testing.T) {
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	fr.Close()
	l, args, _, stderr := fetchHarness(t, f, fr)
	started := time.Now()
	code, done := l.nativeFetch(args, false)
	if !done || code != 0 || !strings.Contains(stderr.String(), "cannot reach registry") || time.Since(started) > 5*time.Second {
		t.Fatalf("done=%v code=%d after %v stderr=%q", done, code, time.Since(started), stderr)
	}
}

// 192.0.2.0/24 is reserved for documentation (RFC 5737) and routes nowhere. On some networks the connection fails at once, on
// others the packets are dropped; either way the fetch must give up within the connect timeout and exit 0.
func TestNativeFetchGivesUpOnARegistryThatDropsPackets(t *testing.T) {
	old := dialTimeout
	dialTimeout = 400 * time.Millisecond
	defer func() { dialTimeout = old }()
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	l, args, _, stderr := fetchHarness(t, f, fr)
	for i, a := range args {
		if a == "--store" {
			args[i+1] = "oci://192.0.2.1:5000/team/app"
		}
	}
	started := time.Now()
	code, done := l.nativeFetch(args, false)
	if !done || code != 0 || !strings.Contains(stderr.String(), "cannot reach registry") || time.Since(started) > 5*time.Second {
		t.Fatalf("done=%v code=%d after %v stderr=%q", done, code, time.Since(started), stderr)
	}
	if _, err := os.Stat(f.path("out/" + flagsFile)); err == nil {
		t.Fatal("flags file must not exist")
	}
}

func TestNativeFetchLeavesTheseToJava(t *testing.T) {
	cases := map[string]func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string{
		"no archive in the store": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			return append(args, "--image-digest", "sha256:another") // a different fingerprint: 404
		},
		"a flag it does not know": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			return append(args, "--unknown", "x")
		},
		"cache type aot": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			return append(args, "--cache-type", "aot")
		},
		"no release file": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			os.Remove(f.path("javahome/release"))
			return args
		},
		"a store that is not oci://": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			return append(args, "--store", "http://"+fr.host()+"/team/app")
		},
		"an AWS registry without a login": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			return append(args, "--store", "oci://123456789012.dkr.ecr.eu-west-1.amazonaws.com/team/app")
		},
		"a proxy configured for the JVM": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			*env = append(*env, "JAVA_TOOL_OPTIONS=-Dhttp.proxyHost=proxy")
			return args
		},
		"credentials that cannot be read": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			return append(args, "--registry-auth", f.path("missing.json"))
		},
		"a Spring Boot fat JAR": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			writeJar(t, f.path("app/app.jar"), "Manifest-Version: 1.0\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\n\n")
			return args
		},
		"a Class-Path entry that is missing": func(t *testing.T, f fixture, fr *fakeRegistry, args []string, env *[]string) []string {
			os.Remove(f.path("app/lib/dep.jar"))
			return args
		},
	}
	for name, setup := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			fr := newFakeRegistry(t, f)
			l, args, stdout, _ := fetchHarness(t, f, fr)
			args = setup(t, f, fr, args, &l.environ)
			code, done := l.nativeFetch(args, false)
			if done {
				t.Fatalf("answered natively (code %d); the Java tool must be asked", code)
			}
			if strings.Contains(stdout.String(), "Archive:") {
				t.Fatal("must not report an archive")
			}
			if _, err := os.Stat(f.path("out/" + flagsFile)); err == nil {
				t.Fatal("must not leave a flags file")
			}
		})
	}
}

func TestNativeFetchCanBeTurnedOff(t *testing.T) {
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	l, args, _, _ := fetchHarness(t, f, fr, "MNEMO_WARMUP_NATIVE_FETCH=0")
	if _, done := l.nativeFetch(args, false); done {
		t.Fatal("MNEMO_WARMUP_NATIVE_FETCH=0 must hand over to the Java tool")
	}
	if len(fr.requests) != 0 {
		t.Fatalf("no registry request expected, got %v", fr.requests)
	}
}

func TestParseFetchOptions(t *testing.T) {
	o, ok := parseFetchOptions([]string{"--out", "/c", "--jar", "/a.jar", "--store=oci://r/x", "--jvm-arg=-XX:MaxRAMPercentage=50", "--jvm-arg", "-Xmx1g", "--plain-http", "--store-timeout-seconds", "7"})
	if !ok || o.out != "/c" || o.jar != "/a.jar" || o.store != "oci://r/x" || !o.plainHttp || o.timeout != 7*time.Second ||
		len(o.jvmArgs) != 2 || o.jvmArgs[0] != "-XX:MaxRAMPercentage=50" || o.jvmArgs[1] != "-Xmx1g" {
		t.Fatalf("%+v ok=%v", o, ok)
	}
	for _, bad := range [][]string{
		{"--out", "/c"}, // no store or jar
		{"--store", "oci://r/x", "--jar", "a.jar", "--store-timeout-seconds", "0"},
		{"--store", "oci://r/x", "--jar", "a.jar", "--plain-http=false"},
		{"--store", "oci://r/x", "--jar"},
	} {
		if _, ok := parseFetchOptions(bad); ok {
			t.Errorf("%q should not be accepted", bad)
		}
	}
}

func writeJar(t *testing.T, path, manifest string) {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, _ := zw.Create("META-INF/MANIFEST.MF")
	io.WriteString(w, manifest)
	zw.Close()
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
}

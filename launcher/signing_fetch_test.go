package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"os"
	"strings"
	"syscall"
	"testing"
	"time"
)

// Signed archives (docs/adr/0013) through the native fetch and the native planner. The attacker is whoever can write to the registry or
// to the shared cache directory and keeps every digest check passing; only a signature they cannot make stops them.

type testSigner struct {
	priv ed25519.PrivateKey
	id   string
}

func newTestSigner(b byte) testSigner {
	priv := ed25519.NewKeyFromSeed(bytes.Repeat([]byte{b}, 32))
	return testSigner{priv: priv, id: signingKeyID(priv.Public().(ed25519.PublicKey))}
}

func (s testSigner) entry() string {
	return s.id + "=" + base64.StdEncoding.EncodeToString(s.priv.Public().(ed25519.PublicKey))
}

func (s testSigner) sign(t *testing.T, st signedStatement) []byte {
	t.Helper()
	text, err := st.text()
	if err != nil {
		t.Fatal(err)
	}
	return ed25519.Sign(s.priv, []byte(text))
}

func digestOf(b []byte) string { s := sha256.Sum256(b); return "sha256:" + hex.EncodeToString(s[:]) }

// signRegistry makes the fake registry serve a manifest signed by s for the given repository.
func signRegistry(t *testing.T, f fixture, fr *fakeRegistry, s testSigner, repository string) []byte {
	t.Helper()
	cfg, _ := os.ReadFile(f.path("cache/mnemo-warmup.manifest.json"))
	archive, _ := os.ReadFile(f.path("cache/app.jsa"))
	m, _ := parseManifest(cfg)
	signature := s.sign(t, signedStatement{m.Fingerprint, digestOf(cfg), digestOf(archive), int64(len(archive)), repository})
	fr.annotations = map[string]string{annotationKeyID: s.id, annotationSignature: base64.StdEncoding.EncodeToString(signature)}
	return signature
}

func layerRequested(fr *fakeRegistry, f fixture) bool {
	archive, _ := os.ReadFile(f.path("cache/app.jsa"))
	for _, r := range fr.requests {
		if strings.Contains(r, "/blobs/"+digestOf(archive)) {
			return true
		}
	}
	return false
}

func keysEnv(entries ...string) string {
	return envSigningPublicKeys + "=" + strings.Join(entries, ",")
}

func TestSignedArchiveIsFetchedWithItsSignatureFileAndTheManifestBytesUnchanged(t *testing.T) {
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	s := newTestSigner(1)
	signature := signRegistry(t, f, fr, s, "team/app")
	l, args, stdout, stderr := fetchHarness(t, f, fr, keysEnv(s.entry()))
	code, done := l.nativeFetch(args, false)
	if !done || code != 0 || !strings.Contains(stdout.String(), "Archive: ") {
		t.Fatalf("done=%v code=%d stdout=%q stderr=%q", done, code, stdout, stderr)
	}
	want, _ := signatureFileText(s.id, signature, "team/app")
	if got, _ := os.ReadFile(f.path("out/" + signatureFile)); string(got) != want {
		t.Fatalf("signature file %q, want %q", got, want)
	}
	cfg, _ := os.ReadFile(f.path("cache/mnemo-warmup.manifest.json"))
	if got, _ := os.ReadFile(f.path("out/" + manifestFile)); !bytes.Equal(got, cfg) {
		t.Fatal("the manifest must be written byte for byte as the registry served it")
	}
}

// Without a key list nothing is required, and a signed archive still leaves its signature so a later run that is given keys can check.
func TestWithoutKeysSignedAndUnsignedArchivesAreAccepted(t *testing.T) {
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	l, args, _, stderr := fetchHarness(t, f, fr)
	if code, done := l.nativeFetch(args, false); !done || code != 0 {
		t.Fatalf("unsigned: done=%v code=%d %s", done, code, stderr)
	}
	if _, err := os.Stat(f.path("out/" + signatureFile)); err == nil {
		t.Fatal("an unsigned archive must not leave a signature file")
	}
	signRegistry(t, f, fr, newTestSigner(1), "team/app")
	if code, done := l.nativeFetch(args, false); !done || code != 0 {
		t.Fatalf("signed: done=%v code=%d %s", done, code, stderr)
	}
	if _, err := os.Stat(f.path("out/" + signatureFile)); err != nil {
		t.Fatal("a signed archive keeps its signature even when nothing is enforced")
	}
}

func TestEveryKindOfUntrustedArchiveIsRefusedBeforeItIsDownloaded(t *testing.T) {
	cases := map[string]struct {
		prepare func(t *testing.T, f fixture, fr *fakeRegistry) []string // returns the key list the pod is given
		reason  string
	}{
		"unsigned": {func(t *testing.T, f fixture, fr *fakeRegistry) []string { return []string{newTestSigner(1).entry()} }, "not signed"},
		"signed by a key that is not listed": {func(t *testing.T, f fixture, fr *fakeRegistry) []string {
			signRegistry(t, f, fr, newTestSigner(9), "team/app")
			return []string{newTestSigner(1).entry()}
		}, "not trusted"},
		"a tampered signature": {func(t *testing.T, f fixture, fr *fakeRegistry) []string {
			s := newTestSigner(1)
			sig := signRegistry(t, f, fr, s, "team/app")
			sig[3] ^= 1
			fr.annotations[annotationSignature] = base64.StdEncoding.EncodeToString(sig)
			return []string{s.entry()}
		}, "does not match"},
		// the design's replay: a signature made for team/billing, served from team/app
		"signed for another repository": {func(t *testing.T, f fixture, fr *fakeRegistry) []string {
			s := newTestSigner(1)
			signRegistry(t, f, fr, s, "team/billing")
			return []string{s.entry()}
		}, "does not match"},
		// a list was given but nothing in it is usable (a typo): everything is refused, the check is never switched off
		"a key list with nothing usable": {func(t *testing.T, f fixture, fr *fakeRegistry) []string {
			signRegistry(t, f, fr, newTestSigner(1), "team/app")
			return []string{"typo-not-a-key"}
		}, "not trusted"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			fr := newFakeRegistry(t, f)
			entries := c.prepare(t, f, fr)
			// stale files from an earlier fetch must be withdrawn
			os.MkdirAll(f.path("out"), 0o755)
			os.WriteFile(f.path("out/"+flagsFile), []byte("-XX:SharedArchiveFile=/old.jsa\n"), 0o644)
			os.WriteFile(f.path("out/"+signatureFile), []byte("stale"), 0o644)
			l, args, stdout, stderr := fetchHarness(t, f, fr, keysEnv(entries...))
			code, done := l.nativeFetch(args, false)
			if !done || code != 0 || !strings.Contains(stdout.String(), "No usable archive") || !strings.Contains(stderr.String(), c.reason) {
				t.Fatalf("done=%v code=%d stdout=%q stderr=%q (want %q)", done, code, stdout, stderr, c.reason)
			}
			if layerRequested(fr, f) {
				t.Fatalf("the archive must not be requested after the signature is refused: %v", fr.requests)
			}
			for _, leftover := range []string{flagsFile, manifestFile, signatureFile, "app.jsa"} {
				if _, err := os.Stat(f.path("out/" + leftover)); err == nil {
					t.Fatalf("%s must not survive a refused archive", leftover)
				}
			}
			if code, done := l.nativeFetch(args, true); !done || code != 1 {
				t.Fatalf("--strict: done=%v code=%d, want 1", done, code)
			}
		})
	}
}

func TestBadKeyListEntriesAreReportedNotFatal(t *testing.T) {
	f := newFixture(t)
	fr := newFakeRegistry(t, f)
	s := newTestSigner(1)
	signRegistry(t, f, fr, s, "team/app")
	l, args, stdout, stderr := fetchHarness(t, f, fr, keysEnv("garbage", s.entry()))
	if code, done := l.nativeFetch(args, false); !done || code != 0 || !strings.Contains(stdout.String(), "Archive: ") {
		t.Fatalf("done=%v code=%d stdout=%q stderr=%q", done, code, stdout, stderr)
	}
	if !strings.Contains(stderr.String(), "ignoring a signing public key entry") {
		t.Fatalf("the bad entry must be reported: %q", stderr)
	}
}

// ---- the native planner re-verifies the files on disk ----

func signFixtureDir(t *testing.T, f fixture, s testSigner, repository string) {
	t.Helper()
	manifest, _ := os.ReadFile(f.path("cache/mnemo-warmup.manifest.json"))
	archive, _ := os.ReadFile(f.path("cache/app.jsa"))
	m, _ := parseManifest(manifest)
	signature := s.sign(t, signedStatement{fingerprint(m), digestOf(manifest), digestOf(archive), int64(len(archive)), repository})
	text, ok := signatureFileText(s.id, signature, repository)
	if !ok {
		t.Fatal("cannot write the signature file")
	}
	if err := os.WriteFile(f.path("cache/"+signatureFile), []byte(text), 0o644); err != nil {
		t.Fatal(err)
	}
}

func (f fixture) planWith(extra ...string) planResult {
	return nativePlan(f.cmd, append(append([]string{}, f.env...), extra...))
}

func TestNativePlanUsesASignedDirectoryAndIgnoresSignaturesWithoutKeys(t *testing.T) {
	f := newFixture(t)
	s := newTestSigner(1)
	signFixtureDir(t, f, s, "team/app")
	if got := f.planWith(keysEnv(s.entry())); !got.decided || len(got.argv) != len(f.cmd)+1 {
		t.Fatalf("a signed directory must be used: %+v", got)
	}
	if got := f.plan(); !got.decided || len(got.argv) != len(f.cmd)+1 {
		t.Fatalf("with no key list nothing is required: %+v", got)
	}
	// a blank list is still a list (nothing is trusted), so it is not "no key list": see TestAPresentButBlankKeyListRefusesEverything
	if got := f.planWith(envSigningPublicKeys + "=   "); got.decided {
		t.Fatalf("a present but blank key list trusts nothing and must not use the directory: %+v", got)
	}
}

// Anything that is not a clean pass goes to the Java planner, which repeats the check and explains it in the log.
func TestNativePlanLeavesEveryUntrustedDirectoryToJava(t *testing.T) {
	cases := map[string]func(t *testing.T, f fixture, s testSigner) []string{
		"no signature file": func(t *testing.T, f fixture, s testSigner) []string { return []string{keysEnv(s.entry())} },
		"signed by a key that is not listed": func(t *testing.T, f fixture, s testSigner) []string {
			signFixtureDir(t, f, newTestSigner(9), "team/app")
			return []string{keysEnv(s.entry())}
		},
		"an unreadable signature file": func(t *testing.T, f fixture, s testSigner) []string {
			os.WriteFile(f.path("cache/"+signatureFile), []byte("garbage"), 0o644)
			return []string{keysEnv(s.entry())}
		},
		// the attack: swap the archive and rewrite the manifest so its hash matches; every digest check passes
		"a swapped archive with a rewritten manifest": func(t *testing.T, f fixture, s testSigner) []string {
			signFixtureDir(t, f, s, "team/app")
			old, _ := os.ReadFile(f.path("cache/app.jsa"))
			evil := bytes.Repeat([]byte("E"), len(old))
			os.WriteFile(f.path("cache/app.jsa"), evil, 0o644)
			f.edit(t, "cache/mnemo-warmup.manifest.json", func(m string) string {
				return strings.Replace(m, strings.TrimPrefix(digestOf(old), "sha256:"), strings.TrimPrefix(digestOf(evil), "sha256:"), 1)
			})
			return []string{keysEnv(s.entry())}
		},
		"a manifest edited by one byte": func(t *testing.T, f fixture, s testSigner) []string {
			signFixtureDir(t, f, s, "team/app")
			f.edit(t, "cache/mnemo-warmup.manifest.json", func(m string) string { return m + "\n " })
			return []string{keysEnv(s.entry())}
		},
		"a signature file naming another repository": func(t *testing.T, f fixture, s testSigner) []string {
			signFixtureDir(t, f, s, "team/app")
			f.edit(t, "cache/"+signatureFile, func(x string) string { return strings.Replace(x, "team/app", "team/other", 1) })
			return []string{keysEnv(s.entry())}
		},
		"a key list with nothing usable": func(t *testing.T, f fixture, s testSigner) []string {
			signFixtureDir(t, f, s, "team/app")
			return []string{envSigningPublicKeys + "=typo-not-a-key"}
		},
	}
	for name, setup := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			extra := setup(t, f, newTestSigner(1))
			if got := f.planWith(extra...); got.decided {
				t.Fatalf("the native planner must not use this directory: %+v", got)
			}
		})
	}
}

// A key list that is present but blank is still a key list: nothing is trusted, so nothing is used. An empty value from a template or a
// typo must never switch the check off (it would have, before, because blank was read as "not set").
func TestAPresentButBlankKeyListRefusesEverything(t *testing.T) {
	for name, blank := range map[string]string{"empty": "", "spaces": "   ", "a comma": ",", "garbage": "typo-not-a-key"} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			fr := newFakeRegistry(t, f)
			signRegistry(t, f, fr, newTestSigner(1), "team/app")
			l, args, stdout, stderr := fetchHarness(t, f, fr, envSigningPublicKeys+"="+blank)
			code, done := l.nativeFetch(args, false)
			if !done || code != 0 || !strings.Contains(stdout.String(), "No usable archive") || !strings.Contains(stderr.String(), "not trusted") {
				t.Fatalf("done=%v code=%d stdout=%q stderr=%q", done, code, stdout, stderr)
			}
			if layerRequested(fr, f) {
				t.Fatal("the archive must not be requested")
			}
			if !strings.Contains(stderr.String(), "lists no usable key") {
				t.Fatalf("the operator must be told the list is unusable: %q", stderr)
			}
			// and the planner, given the same blank list, leaves the directory to Java instead of using it
			fx := newFixture(t)
			signFixtureDir(t, fx, newTestSigner(1), "team/app")
			if got := fx.planWith(envSigningPublicKeys + "=" + blank); got.decided {
				t.Fatalf("the native planner must not use a signed directory when nothing is trusted: %+v", got)
			}
		})
	}
}

// The signature file, the flags file and the manifest sit in a volume other processes may write: a huge file, or a named pipe that would
// block forever, must send the decision to Java, never exhaust memory or hang the application's start.
func TestOversizedOrSpecialFilesInTheCacheDirectoryAreNotRead(t *testing.T) {
	huge := strings.Repeat("x", maxManifestBytes+1)
	cases := map[string]func(t *testing.T, f fixture){
		"a huge signature file": func(t *testing.T, f fixture) {
			signFixtureDir(t, f, newTestSigner(1), "team/app")
			os.WriteFile(f.path("cache/"+signatureFile), []byte(strings.Repeat("x", maxSignatureBytes+1)), 0o644)
		},
		"a signature file that is a named pipe": func(t *testing.T, f fixture) {
			signFixtureDir(t, f, newTestSigner(1), "team/app")
			os.Remove(f.path("cache/" + signatureFile))
			if err := syscall.Mkfifo(f.path("cache/"+signatureFile), 0o644); err != nil {
				t.Skip("cannot make a named pipe here:", err)
			}
		},
		"a huge flags file": func(t *testing.T, f fixture) {
			os.WriteFile(f.path("cache/"+flagsFile), []byte(strings.Repeat("x", maxFlagsBytes+1)), 0o644)
		},
		"a huge manifest": func(t *testing.T, f fixture) {
			os.WriteFile(f.path("cache/"+manifestFile), []byte(huge), 0o644)
		},
	}
	for name, setup := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			setup(t, f)
			done := make(chan planResult, 1)
			go func() { done <- f.planWith(keysEnv(newTestSigner(1).entry())) }()
			select {
			case got := <-done:
				if got.decided {
					t.Fatalf("the native planner must not use this directory: %+v", got)
				}
			case <-time.After(5 * time.Second):
				t.Fatal("the planner hung on a special file")
			}
		})
	}
}

// The archive is hashed from one open file: a path that has become a named pipe, or a file whose size is not the declared one, is refused
// and never waited for.
func TestTheArchiveIsHashedFromOneOpenFile(t *testing.T) {
	dir := t.TempDir()
	good := dir + "/good"
	os.WriteFile(good, []byte("0123456789"), 0o644)
	want := digestOf([]byte("0123456789"))[len("sha256:"):]
	if got, err := sha256Regular(good, 10); err != nil || got != want {
		t.Fatalf("the declared size must hash: %q %v", got, err)
	}
	if _, err := sha256Regular(good, 9); err == nil {
		t.Fatal("a file longer than declared must be refused")
	}
	if _, err := sha256Regular(good, 11); err == nil {
		t.Fatal("a file shorter than declared must be refused")
	}
	if _, err := sha256Regular(dir, 0); err == nil {
		t.Fatal("a directory must be refused")
	}
	if _, err := sha256Regular(dir+"/missing", 1); err == nil {
		t.Fatal("a missing file must be refused")
	}
	pipe := dir + "/pipe"
	if err := syscall.Mkfifo(pipe, 0o644); err != nil {
		t.Skip("cannot make a named pipe here:", err)
	}
	done := make(chan error, 1)
	go func() { _, err := sha256Regular(pipe, 10); done <- err }()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("a named pipe must be refused")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("hashing waited on a named pipe")
	}
}

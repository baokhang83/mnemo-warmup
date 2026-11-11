package main

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestMain lets the test binary stand in for `java`: a symlink named java points at it, and when
// FAKE_JAVA is set it records its arguments, prints FAKE_JAVA_ARGV as NUL-terminated arguments and
// exits with FAKE_JAVA_EXIT, instead of running the tests.
func TestMain(m *testing.M) {
	if os.Getenv("FAKE_JAVA") == "1" {
		if f := os.Getenv("FAKE_JAVA_ARGS_OUT"); f != "" {
			// one record per call, so a test can tell the order of a fetch and a plan in the same process
			record := strings.Join(os.Args[1:], "\n")
			if prev, err := os.ReadFile(f); err == nil && len(prev) > 0 {
				record = string(prev) + "\n=== next call ===\n" + record
			}
			os.WriteFile(f, []byte(record), 0o644)
		}
		if argv := os.Getenv("FAKE_JAVA_ARGV"); argv != "" {
			for _, a := range strings.Split(argv, "|") {
				os.Stdout.WriteString(a + "\x00")
			}
		}
		code, _ := strconv.Atoi(os.Getenv("FAKE_JAVA_EXIT"))
		os.Exit(code)
	}
	os.Exit(m.Run())
}

type harness struct {
	l      launcher
	stderr *bytes.Buffer
	execed *[]string
	args   string // file the fake java records its arguments in
	java   string // path of the fake java, when there is one
}

// newHarness builds a launcher whose PATH holds only a fake java (or nothing). Pairs of
// key, value arguments are set in the environment BEFORE the launcher snapshots it for its children.
func newHarness(t *testing.T, withJava bool, env ...string) harness {
	t.Helper()
	dir := t.TempDir()
	if withJava {
		self, err := os.Executable()
		if err != nil {
			t.Fatal(err)
		}
		if err := os.Symlink(self, filepath.Join(dir, "java")); err != nil {
			t.Fatal(err)
		}
		t.Setenv("FAKE_JAVA", "1")
	}
	t.Setenv("PATH", dir)
	t.Setenv("JAVA_HOME", "")
	args := filepath.Join(t.TempDir(), "args")
	t.Setenv("FAKE_JAVA_ARGS_OUT", args)
	for i := 0; i+1 < len(env); i += 2 {
		t.Setenv(env[i], env[i+1])
	}
	var stderr bytes.Buffer
	var execed []string
	return harness{
		l: launcher{
			locator: systemLocator(), root: "/tools", stdout: &bytes.Buffer{}, stderr: &stderr, environ: os.Environ(),
			exec: func(path string, argv, env []string) error {
				execed = append([]string{path}, argv...)
				return nil
			},
			probeTimeout: 5 * time.Second,
		},
		stderr: &stderr, execed: &execed, args: args, java: filepath.Join(dir, "java"),
	}
}

func TestParseNul(t *testing.T) {
	cases := []struct {
		in   string
		want []string
	}{
		{"java\x00-jar\x00a.jar\x00", []string{"java", "-jar", "a.jar"}},
		{"java\x00\x00x y\x00", []string{"java", "", "x y"}},
		{"", nil},
		{"java\x00-jar", nil}, // truncated: not terminated
		{"\x00", nil},         // empty program
	}
	for _, c := range cases {
		if got := parseNul([]byte(c.in)); !reflect.DeepEqual(got, c.want) {
			t.Errorf("parseNul(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestFetch(t *testing.T) {
	t.Run("no java exits 0, or fails under --strict", func(t *testing.T) {
		h := newHarness(t, false)
		if got := h.l.fetch([]string{"--out", "/c"}); got != 0 {
			t.Fatalf("exit %d, want 0", got)
		}
		if got := h.l.fetch([]string{"--strict"}); got != 127 {
			t.Fatalf("strict exit %d, want 127", got)
		}
	})
	t.Run("runs the jar with the fetch arguments", func(t *testing.T) {
		h := newHarness(t, true)
		if got := h.l.fetch([]string{"--out", "/c"}); got != 0 {
			t.Fatalf("exit %d", got)
		}
		b, _ := os.ReadFile(h.args)
		if want := "-jar\n/tools/mnemo-warmup.jar\nfetch\n--out\n/c"; string(b) != want {
			t.Fatalf("java got %q, want %q", b, want)
		}
	})
	t.Run("reports how long the helper took only when MNEMO_WARMUP_TIMING=1", func(t *testing.T) {
		quiet := newHarness(t, true)
		quiet.l.fetch(nil)
		if strings.Contains(quiet.stderr.String(), "timing:") {
			t.Fatalf("timing printed without MNEMO_WARMUP_TIMING: %q", quiet.stderr.String())
		}
		timed := newHarness(t, true)
		timed.l.environ = append(timed.l.environ, "MNEMO_WARMUP_TIMING=1")
		timed.l.fetch(nil)
		if !strings.Contains(timed.stderr.String(), "mnemo-launcher: timing: fetch-process=") {
			t.Fatalf("no timing line: %q", timed.stderr.String())
		}
	})
	t.Run("a failing jar is swallowed unless --strict, which keeps its status", func(t *testing.T) {
		h := newHarness(t, true, "FAKE_JAVA_EXIT", "3")
		if got := h.l.fetch(nil); got != 0 {
			t.Fatalf("exit %d, want 0", got)
		}
		if got := h.l.fetch([]string{"--strict"}); got != 3 {
			t.Fatalf("strict exit %d, want 3", got)
		}
	})
}

func TestExecApp(t *testing.T) {
	orig := []string{"java", "-Xmx1g", "-jar", "/app/a.jar"}
	t.Run("uses the command the jar prints", func(t *testing.T) {
		h := newHarness(t, true, "MNEMO_WARMUP_NATIVE_PLAN", "0", "FAKE_JAVA_ARGV", "java|-XX:SharedArchiveFile=/c/a.jsa|-Xmx1g|-jar|/app/a.jar")
		h.l.execApp(orig)
		want := []string{h.java, "java", "-XX:SharedArchiveFile=/c/a.jsa", "-Xmx1g", "-jar", "/app/a.jar"}
		if !reflect.DeepEqual(*h.execed, want) {
			t.Fatalf("exec %q, want %q", *h.execed, want)
		}
		b, _ := os.ReadFile(h.args)
		if !strings.HasPrefix(string(b), "-jar\n/tools/mnemo-warmup.jar\nrun\n--print\n--format\nnul\n--\njava") {
			t.Fatalf("helper got %q", b)
		}
	})
	for name, env := range map[string][]string{
		"helper prints nothing": {"MNEMO_WARMUP_NATIVE_PLAN", "0"},
		"helper fails":          {"MNEMO_WARMUP_NATIVE_PLAN", "0", "FAKE_JAVA_EXIT", "1", "FAKE_JAVA_ARGV", "java|x"},
	} {
		t.Run("starts the original when "+name, func(t *testing.T) {
			h := newHarness(t, true, env...)
			h.l.execApp(orig)
			if got := (*h.execed)[1:]; !reflect.DeepEqual(got, orig) {
				t.Fatalf("exec %q, want %q", got, orig)
			}
		})
	}
	t.Run("with no java anywhere the command cannot start and exits 127", func(t *testing.T) {
		h := newHarness(t, false)
		if got := h.l.execApp(orig); got != 127 {
			t.Fatalf("exit %d, want 127", got)
		}
		if !strings.Contains(h.stderr.String(), "cannot find java") || len(*h.execed) != 0 {
			t.Fatalf("stderr %q, exec %q", h.stderr, *h.execed)
		}
	})
	t.Run("an unsupported command is started unchanged without running the helper", func(t *testing.T) {
		h := newHarness(t, true, "FAKE_JAVA_ARGV", "node|app.js")
		h.l.execApp([]string{"/usr/bin/node", "server.js"})
		if !reflect.DeepEqual((*h.execed)[1:], []string{"/usr/bin/node", "server.js"}) {
			t.Fatalf("exec %q", *h.execed)
		}
		if _, err := os.Stat(h.args); err == nil {
			t.Fatal("helper must not run for a non-java command")
		}
	})
	t.Run("a command that cannot be executed exits 127", func(t *testing.T) {
		h := newHarness(t, false)
		h.l.exec = func(string, []string, []string) error { return errors.New("exec format error") }
		if got := h.l.execApp([]string{"/nope/app"}); got != 127 {
			t.Fatalf("exit %d, want 127", got)
		}
	})
}

func TestExecFetchesFirstWhenGivenFetchOptions(t *testing.T) {
	orig := []string{"java", "-Xmx1g", "-jar", "/app/a.jar"}
	opts := []string{"--out", "/c", "--jar", "/app/a.jar", "--store", "oci://r/x"}
	t.Run("fetch runs, then the application replaces the launcher", func(t *testing.T) {
		h := newHarness(t, true, "MNEMO_WARMUP_NATIVE_FETCH", "0", "MNEMO_WARMUP_NATIVE_PLAN", "0")
		code := h.l.run(append(append([]string{"exec"}, opts...), append([]string{"--"}, orig...)...))
		if code != 0 {
			t.Fatalf("exit %d: %s", code, h.stderr)
		}
		b, _ := os.ReadFile(h.args)
		fetchAt, planAt := strings.Index(string(b), "mnemo-warmup.jar\nfetch\n--out\n/c"), strings.Index(string(b), "mnemo-warmup.jar\nrun\n--print")
		if fetchAt < 0 || planAt < 0 || fetchAt > planAt {
			t.Fatalf("expected the fetch and then the planner; fake java was called with %q", b)
		}
		if got := (*h.execed)[1:]; !reflect.DeepEqual(got, orig) {
			t.Fatalf("exec %q, want %q", got, orig)
		}
	})
	t.Run("a fetch problem never stops the application", func(t *testing.T) {
		h := newHarness(t, true, "MNEMO_WARMUP_NATIVE_FETCH", "0", "MNEMO_WARMUP_NATIVE_PLAN", "0", "FAKE_JAVA_EXIT", "7")
		h.l.run(append(append([]string{"exec"}, opts...), append([]string{"--"}, orig...)...))
		if got := (*h.execed)[1:]; !reflect.DeepEqual(got, orig) {
			t.Fatalf("exec %q, want the original command %q", got, orig)
		}
	})
	t.Run("--strict can make the fetch fail the container", func(t *testing.T) {
		h := newHarness(t, true, "MNEMO_WARMUP_NATIVE_FETCH", "0", "MNEMO_WARMUP_NATIVE_PLAN", "0", "FAKE_JAVA_EXIT", "7")
		code := h.l.run(append(append([]string{"exec", "--strict"}, opts...), append([]string{"--"}, orig...)...))
		if code != 7 || len(*h.execed) != 0 {
			t.Fatalf("exit %d exec %q, want 7 and no exec", code, *h.execed)
		}
	})
	t.Run("without fetch options it behaves as before", func(t *testing.T) {
		h := newHarness(t, true, "MNEMO_WARMUP_NATIVE_PLAN", "0")
		h.l.run(append([]string{"exec", "--"}, orig...))
		if _, err := os.Stat(h.args); err != nil {
			t.Fatal("the planner should have run")
		}
		b, _ := os.ReadFile(h.args)
		if strings.Contains(string(b), "fetch") {
			t.Fatalf("no fetch expected: %q", b)
		}
	})
	t.Run("a command with no -- is still accepted, and nothing after exec is a usage error", func(t *testing.T) {
		h := newHarness(t, false)
		if got := h.l.run([]string{"exec"}); got != 2 {
			t.Fatalf("exit %d, want 2", got)
		}
		if got := h.l.run([]string{"exec", "--out", "/c", "--"}); got != 2 {
			t.Fatalf("exit %d, want 2 for fetch options and no command", got)
		}
	})
}

func TestInstallCopiesTheTreeAndKeepsModes(t *testing.T) {
	src, dst := t.TempDir(), filepath.Join(t.TempDir(), "tools")
	os.MkdirAll(filepath.Join(src, "bin"), 0o755)
	os.WriteFile(filepath.Join(src, "mnemo-warmup.jar"), []byte("jar"), 0o644)
	os.WriteFile(filepath.Join(src, "bin", "mnemo-launcher"), []byte("elf"), 0o755)
	os.Symlink("mnemo-warmup.jar", filepath.Join(src, "link")) // skipped
	h := newHarness(t, false)
	h.l.root = src
	for i := 0; i < 2; i++ { // twice: an init container may restart
		if got := h.l.install(dst); got != 0 {
			t.Fatalf("exit %d: %s", got, h.stderr)
		}
	}
	if b, _ := os.ReadFile(filepath.Join(dst, "mnemo-warmup.jar")); string(b) != "jar" {
		t.Fatalf("jar = %q", b)
	}
	if info, err := os.Stat(filepath.Join(dst, "bin", "mnemo-launcher")); err != nil || info.Mode().Perm() != 0o755 {
		t.Fatalf("launcher mode: %v %v", info, err)
	}
	if _, err := os.Lstat(filepath.Join(dst, "link")); err == nil {
		t.Fatal("symlink should be skipped")
	}
	entries, _ := os.ReadDir(dst)
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".mnemo-install-") {
			t.Fatalf("temporary file left behind: %s", e.Name())
		}
	}
}

func TestInstallReportsAFailure(t *testing.T) {
	h := newHarness(t, false)
	h.l.root = filepath.Join(t.TempDir(), "missing")
	if got := h.l.install(t.TempDir()); got != 1 {
		t.Fatalf("exit %d, want 1", got)
	}
}

func TestJarToolReplacesTheProcessWithTheJar(t *testing.T) {
	h := newHarness(t, true)
	h.l.jarTool([]string{"train", "--store", "oci://r/x"})
	want := []string{h.java, h.java, "-jar", "/tools/mnemo-warmup.jar", "train", "--store", "oci://r/x"}
	if !reflect.DeepEqual(*h.execed, want) {
		t.Fatalf("exec %q, want %q", *h.execed, want)
	}
	h = newHarness(t, false)
	if got := h.l.jarTool([]string{"train"}); got != 127 {
		t.Fatalf("exit %d, want 127 without java", got)
	}
}

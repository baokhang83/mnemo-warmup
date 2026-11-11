package main

import (
	"archive/zip"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

// The fixture in testdata/compat is written by the real Java classes (testdata/compat/Gen.java), so a pass here means Go and Java
// agree on the fingerprint and on every check. Each test copies it, puts the modification times back (git does not keep them)
// and replaces @ROOT@ with the copy's path.

var fixtureTime = time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)

type fixture struct {
	root string
	cmd  []string
	env  []string
}

func newFixture(t *testing.T) fixture {
	t.Helper()
	root := t.TempDir()
	src := filepath.Join("testdata", "compat", "fixture")
	err := filepath.Walk(src, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, p)
		dst := filepath.Join(root, rel)
		if info.IsDir() {
			return os.MkdirAll(dst, 0o755)
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		if strings.HasSuffix(p, ".json") || strings.HasSuffix(p, ".flags") {
			b = []byte(strings.ReplaceAll(string(b), "@ROOT@", root))
		}
		return os.WriteFile(dst, b, info.Mode())
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, j := range []string{"app/app.jar", "app/lib/dep.jar"} {
		if err := os.Chtimes(filepath.Join(root, j), fixtureTime, fixtureTime); err != nil {
			t.Fatal(err)
		}
	}
	return fixture{
		root: root,
		cmd:  []string{filepath.Join(root, "javahome/bin/java"), "-Xmx512m", "-jar", filepath.Join(root, "app/app.jar")},
		env:  []string{"MNEMO_WARMUP_CACHE_DIR=" + filepath.Join(root, "cache"), "MNEMO_WARMUP_IMAGE_DIGEST=sha256:abc"},
	}
}

func (f fixture) plan() planResult { return nativePlan(f.cmd, f.env) }

func (f fixture) path(rel string) string { return filepath.Join(f.root, rel) }

func (f fixture) edit(t *testing.T, rel string, fn func(string) string) {
	t.Helper()
	b, err := os.ReadFile(f.path(rel))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(f.path(rel), []byte(fn(string(b))), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestNativePlanUsesAnArchiveThatFits(t *testing.T) {
	f := newFixture(t)
	got := f.plan()
	if !got.decided {
		t.Fatal("the fixture written by Java was not accepted: the fingerprint or a check disagrees with Java")
	}
	flag := "-XX:SharedArchiveFile=" + f.path("cache/app.jsa")
	want := []string{f.cmd[0], flag, "-Xmx512m", "-jar", f.cmd[3]}
	if !reflect.DeepEqual(got.argv, want) {
		t.Fatalf("argv %q, want %q", got.argv, want)
	}
	if len(got.notes) != 1 || got.notes[0] != "using APP_CDS archive "+f.path("cache/app.jsa") {
		t.Fatalf("notes %q", got.notes)
	}
}

func TestNativePlanReportsTimingWhenAsked(t *testing.T) {
	f := newFixture(t)
	f.env = append(f.env, "MNEMO_WARMUP_TIMING=1")
	got := f.plan()
	if !got.decided || len(got.notes) != 2 || !strings.HasPrefix(got.notes[1], "timing: read-manifest=") {
		t.Fatalf("notes %q", got.notes)
	}
}

func TestNativePlanLeavesEverythingElseToJava(t *testing.T) {
	cases := map[string]func(t *testing.T, f *fixture){
		"archive with a byte changed, same length": func(t *testing.T, f *fixture) {
			f.edit(t, "cache/app.jsa", func(s string) string { return "A" + s[1:] })
		},
		"archive truncated": func(t *testing.T, f *fixture) {
			f.edit(t, "cache/app.jsa", func(s string) string { return s[:10] })
		},
		"archive missing": func(t *testing.T, f *fixture) {
			if err := os.Remove(f.path("cache/app.jsa")); err != nil {
				t.Fatal(err)
			}
		},
		"classpath JAR changed, same length": func(t *testing.T, f *fixture) {
			f.edit(t, "app/lib/dep.jar", func(s string) string { return s[:100] + "Z" + s[101:] })
			os.Chtimes(f.path("app/lib/dep.jar"), fixtureTime, fixtureTime)
		},
		"a different heap size": func(t *testing.T, f *fixture) { f.cmd[1] = "-Xmx1g" },
		"a different image digest": func(t *testing.T, f *fixture) {
			f.env[1] = "MNEMO_WARMUP_IMAGE_DIGEST=sha256:other"
		},
		"no image digest where one was recorded": func(t *testing.T, f *fixture) { f.env = f.env[:1] },
		"JAR modification time differs": func(t *testing.T, f *fixture) {
			later := fixtureTime.Add(3 * time.Second)
			os.Chtimes(f.path("app/app.jar"), later, later)
		},
		"trained from another directory": func(t *testing.T, f *fixture) {
			f.edit(t, "cache/mnemo-warmup.manifest.json", func(s string) string { return strings.ReplaceAll(s, f.root+"/app", "/elsewhere/app") })
		},
		"another JDK build": func(t *testing.T, f *fixture) {
			f.edit(t, "javahome/release", func(s string) string { return strings.ReplaceAll(s, "21.0.13+11-LTS", "21.0.14+7-LTS") })
		},
		"another JDK vendor": func(t *testing.T, f *fixture) {
			f.edit(t, "javahome/release", func(s string) string { return strings.ReplaceAll(s, "Test Vendor", "Other") })
		},
		"no release file to read": func(t *testing.T, f *fixture) { os.Remove(f.path("javahome/release")) },
		"manifest edited after the fingerprint was written": func(t *testing.T, f *fixture) {
			f.edit(t, "cache/mnemo-warmup.manifest.json", func(s string) string { return strings.Replace(s, `"sizeBytes": 339`, `"sizeBytes": 340`, 1) })
		},
		"manifest with a field this tool does not know": func(t *testing.T, f *fixture) {
			f.edit(t, "cache/mnemo-warmup.manifest.json", func(s string) string {
				return strings.Replace(s, `"schemaVersion": 1,`, `"schemaVersion": 1, "future": true,`, 1)
			})
		},
		"manifest that is not JSON": func(t *testing.T, f *fixture) {
			f.edit(t, "cache/mnemo-warmup.manifest.json", func(string) string { return "nope" })
		},
		"no manifest next to the flags": func(t *testing.T, f *fixture) { os.Remove(f.path("cache/mnemo-warmup.manifest.json")) },
		"flags that do not match the cache type": func(t *testing.T, f *fixture) {
			f.edit(t, "cache/mnemo-warmup.flags", func(s string) string { return strings.Replace(s, "SharedArchiveFile", "AOTCache", 1) })
		},
		"flags with two options": func(t *testing.T, f *fixture) {
			f.edit(t, "cache/mnemo-warmup.flags", func(s string) string { return strings.TrimSpace(s) + " -Xshare:on\n" })
		},
		"the command already sets an archive": func(t *testing.T, f *fixture) {
			f.cmd = append([]string{f.cmd[0], "-XX:SharedArchiveFile=/x.jsa"}, f.cmd[1:]...)
		},
		"a classpath launch": func(t *testing.T, f *fixture) { f.cmd = []string{f.cmd[0], "-cp", "x", "Main"} },
		"not a java command": func(t *testing.T, f *fixture) { f.cmd = []string{"/usr/bin/node", "-jar", "x.js"} },
		"a Class-Path entry that is missing": func(t *testing.T, f *fixture) {
			os.Remove(f.path("app/lib/dep.jar"))
		},
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			mutate(t, &f)
			if got := f.plan(); got.decided {
				t.Fatalf("decided natively (argv %q, notes %q); it must be left to the Java planner", got.argv, got.notes)
			}
		})
	}
}

func TestNativePlanSaysSoWhenNothingWasFetched(t *testing.T) {
	f := newFixture(t)
	os.Remove(f.path("cache/mnemo-warmup.flags"))
	got := f.plan()
	if !got.decided || !reflect.DeepEqual(got.argv, f.cmd) {
		t.Fatalf("decided=%v argv %q, want the original command", got.decided, got.argv)
	}
	if want := "no archive was fetched into " + f.path("cache") + "; starting without one"; len(got.notes) != 1 || got.notes[0] != want {
		t.Fatalf("notes %q, want %q", got.notes, want)
	}
	// an empty flags file means the same
	os.WriteFile(f.path("cache/mnemo-warmup.flags"), []byte("\n"), 0o644)
	if got := f.plan(); !got.decided || !reflect.DeepEqual(got.argv, f.cmd) {
		t.Fatalf("empty flags: decided=%v argv %q", got.decided, got.argv)
	}
}

func TestSpringBootFatJarIsLeftToJava(t *testing.T) {
	write := func(t *testing.T, manifest string) string {
		p := filepath.Join(t.TempDir(), "boot.jar")
		out, err := os.Create(p)
		if err != nil {
			t.Fatal(err)
		}
		zw := zip.NewWriter(out)
		w, _ := zw.Create("META-INF/MANIFEST.MF")
		io.WriteString(w, manifest)
		zw.Close()
		out.Close()
		return p
	}
	for manifest, wantFat := range map[string]bool{
		"Manifest-Version: 1.0\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\n\n": true,
		"Manifest-Version: 1.0\nSpring-Boot-Version: 3.4.0\nMain-Class: com.example.App\n\n":        true,
		"Manifest-Version: 1.0\nSpring-Boot-Version: 3.4.0\nClass-Path: lib/a.jar\n\n":              false, // already extracted
		"Manifest-Version: 1.0\nMain-Class: com.example.App\n\n":                                    false,
		"Manifest-Version: 1.0\nmain-class: org.springframework.boot.loader.JarLauncher\n\n":        true, // names are case-insensitive
	} {
		fat, ok := isFatJar(write(t, manifest))
		if !ok || fat != wantFat {
			t.Errorf("isFatJar(%q) = %v, %v; want %v", manifest, fat, ok, wantFat)
		}
	}
	if _, ok := isFatJar(filepath.Join(t.TempDir(), "missing.jar")); ok {
		t.Error("a missing JAR must not be decided")
	}
}

func TestJarManifestContinuationLines(t *testing.T) {
	got := parseMainAttributes("Manifest-Version: 1.0\nClass-Path: lib/one.jar lib/tw\n o.jar\nMain-Class: M\n\nName: x\nClass-Path: no\n")
	if got["class-path"] != "lib/one.jar lib/two.jar" || got["main-class"] != "M" {
		t.Fatalf("attributes %v", got)
	}
}

func TestRelevantJvmFlagsMatchTheJavaRules(t *testing.T) {
	env := map[string]string{"JAVA_TOOL_OPTIONS": "-Xmx256m -XX:+UseZGC -Dfoo=bar", "JDK_JAVA_OPTIONS": "-XX:-UseCompressedOops"}
	got := relevantJvmFlags([]string{"-Xmx512m", "-Xms64m", "-XX:+UseG1GC", "-Dx=y", "-XX:MaxRAMPercentage=50"}, env)
	// JvmFlags.relevant: the command line, then JDK_JAVA_OPTIONS, then JAVA_TOOL_OPTIONS; the last occurrence of a key wins;
	// ordered by key; options that cannot invalidate an archive are dropped.
	want := []string{"-XX:MaxRAMPercentage=50", "-XX:-UseCompressedOops", "-XX:+UseG1GC", "-XX:+UseZGC", "-Xms64m", "-Xmx256m"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("flags %q, want %q", got, want)
	}
}

func TestQuoteJSONMatchesJava(t *testing.T) {
	var sb strings.Builder
	quoteJSON(&sb, "a\"b\\c\n\r\t\b\f\x01é/\x7f")
	if want := "\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001é/\x7f\""; sb.String() != want {
		t.Fatalf("got %q, want %q", sb.String(), want)
	}
}

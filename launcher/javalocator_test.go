package main

import "testing"

func fakeLocator(env map[string]string, executables ...string) javaLocator {
	present := map[string]bool{}
	for _, e := range executables {
		present[e] = true
	}
	return javaLocator{
		getenv:       func(k string) string { return env[k] },
		isExecutable: func(p string) bool { return present[p] },
	}
}

func TestFind(t *testing.T) {
	cases := []struct {
		name  string
		env   map[string]string
		files []string
		hint  string
		want  string
	}{
		{"explicit path in hint wins", map[string]string{"PATH": "/usr/bin"}, []string{"/opt/jdk/bin/java", "/usr/bin/java"}, "/opt/jdk/bin/java", "/opt/jdk/bin/java"},
		{"explicit path that is missing does not fall back to another JDK", map[string]string{"PATH": "/usr/bin"}, []string{"/usr/bin/java"}, "/opt/jdk/bin/java", ""},
		{"bare java resolves through PATH", map[string]string{"PATH": "/a:/b"}, []string{"/b/java"}, "java", "/b/java"},
		{"no hint searches PATH for java", map[string]string{"PATH": "/a"}, []string{"/a/java"}, "", "/a/java"},
		{"PATH beats JAVA_HOME", map[string]string{"PATH": "/a", "JAVA_HOME": "/jh"}, []string{"/a/java", "/jh/bin/java"}, "", "/a/java"},
		{"JAVA_HOME used when PATH has none", map[string]string{"PATH": "/a", "JAVA_HOME": "/jh"}, []string{"/jh/bin/java"}, "", "/jh/bin/java"},
		{"well-known path when PATH is empty", map[string]string{}, []string{"/opt/java/openjdk/bin/java"}, "", "/opt/java/openjdk/bin/java"},
		{"empty PATH entries are skipped, not read as the current directory", map[string]string{"PATH": ":"}, []string{"java"}, "", ""},
		{"nothing found", map[string]string{"PATH": "/a"}, nil, "", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := fakeLocator(c.env, c.files...).find(c.hint); got != c.want {
				t.Fatalf("find(%q) = %q, want %q", c.hint, got, c.want)
			}
		})
	}
}

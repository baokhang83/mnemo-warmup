package main

import (
	"os"
	"path/filepath"
	"strings"
)

// wellKnownJava lists where common Java images put the launcher binary when neither PATH nor
// JAVA_HOME points at it. Distroless and many minimal images run the application with an explicit
// path and an empty or short PATH, so PATH alone is not enough.
var wellKnownJava = []string{
	"/usr/bin/java",
	"/opt/java/openjdk/bin/java",
	"/usr/lib/jvm/default-java/bin/java",
	"/usr/local/openjdk-21/bin/java",
}

// javaLocator finds the `java` executable to run the warmup jar with. Its inputs are injected so
// the search order can be tested without a real filesystem.
type javaLocator struct {
	getenv       func(string) string
	isExecutable func(path string) bool
}

func systemLocator() javaLocator {
	return javaLocator{getenv: os.Getenv, isExecutable: isExecutableFile}
}

func isExecutableFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular() && info.Mode().Perm()&0o111 != 0
}

// find returns the java to use, or "" when none is found (the caller then takes its fallback).
//
// An archive is valid only for the JDK that runs the application, so the tool must run on that same
// JDK. The application's own launch command therefore wins: hint is its first element (for example
// "java" or "/opt/java/openjdk/bin/java"); pass "" when there is none, as in the fetch step.
// Order: an explicit path in hint, then PATH (which is how a bare "java" in the command resolves),
// then JAVA_HOME, then the well-known locations.
func (l javaLocator) find(hint string) string {
	if strings.ContainsRune(hint, '/') {
		if l.isExecutable(hint) {
			return hint
		}
		return ""
	}
	name := "java"
	if hint != "" {
		name = hint
	}
	for _, dir := range filepath.SplitList(l.getenv("PATH")) {
		if dir == "" {
			continue
		}
		if p := filepath.Join(dir, name); l.isExecutable(p) {
			return p
		}
	}
	if home := l.getenv("JAVA_HOME"); home != "" {
		if p := filepath.Join(home, "bin", "java"); l.isExecutable(p) {
			return p
		}
	}
	for _, p := range wellKnownJava {
		if l.isExecutable(p) {
			return p
		}
	}
	return ""
}

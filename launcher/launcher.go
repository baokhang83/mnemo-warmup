package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"time"
)

// defaultProbeTimeout bounds the helper JVM that decides the final command. Starting the
// application must never wait on an optimisation, so past this the original command is used.
const defaultProbeTimeout = 60 * time.Second

// launcher holds everything the subcommands touch outside this process, so tests can replace it.
type launcher struct {
	locator      javaLocator
	root         string // holds bin/ and mnemo-warmup.jar
	stdout       io.Writer
	stderr       io.Writer
	environ      []string
	exec         func(path string, argv, env []string) error // syscall.Exec in production
	probeTimeout time.Duration
}

func (l launcher) jar() string { return filepath.Join(l.root, "mnemo-warmup.jar") }

func (l launcher) note(format string, a ...any) {
	fmt.Fprintf(l.stderr, "mnemo-launcher: "+format+"\n", a...)
}

func (l launcher) run(args []string) int {
	if len(args) == 0 {
		return l.usage()
	}
	switch args[0] {
	case "install":
		if len(args) != 2 {
			return l.usage()
		}
		return l.install(args[1])
	case "fetch":
		return l.fetch(args[1:])
	case "exec":
		cmd := args[1:]
		if len(cmd) > 0 && cmd[0] == "--" {
			cmd = cmd[1:]
		}
		if len(cmd) == 0 {
			return l.usage()
		}
		return l.execApp(cmd)
	case "which-java":
		if java := l.locator.find(""); java != "" {
			fmt.Fprintln(l.stdout, java)
			return 0
		}
		l.note("no java found")
		return 1
	}
	return l.usage()
}

func (l launcher) usage() int {
	fmt.Fprintln(l.stderr, "usage: mnemo-launcher install <dir> | fetch [options] | exec -- <command> [args...] | which-java")
	return 2
}

// install copies the tools into dir (the shared volume). Unlike the other steps it cannot fall
// back: without the tools the later containers have nothing to run, so a failure is reported.
func (l launcher) install(dir string) int {
	if err := copyTree(l.root, dir); err != nil {
		l.note("install into %s failed: %v", dir, err)
		return 1
	}
	return 0
}

// fetch runs `java -jar mnemo-warmup.jar fetch <args>` and then gets out of the way. A cache that
// cannot be fetched must not stop the pod, so every failure exits 0 unless --strict was given.
func (l launcher) fetch(args []string) int {
	strict := slices.Contains(args, "--strict")
	java := l.locator.find("")
	if java == "" {
		return l.fetchFailed(strict, 127, "no java found; run this inside the application image (Java 21+)")
	}
	cmd := exec.Command(java, append([]string{"-jar", l.jar(), "fetch"}, args...)...)
	cmd.Stdout, cmd.Stderr, cmd.Env = l.stdout, l.stderr, l.environ
	err := cmd.Run()
	if err == nil {
		return 0
	}
	var exit *exec.ExitError
	if errors.As(err, &exit) {
		code := exit.ExitCode()
		if code <= 0 {
			code = 1
		}
		return l.fetchFailed(strict, code, fmt.Sprintf("fetch exited with status %d", code))
	}
	return l.fetchFailed(strict, 127, "cannot run java: "+err.Error())
}

func (l launcher) fetchFailed(strict bool, code int, why string) int {
	if strict {
		l.note("%s (--strict: failing)", why)
		return code
	}
	l.note("%s; continuing without an archive", why)
	return 0
}

// execApp replaces this process with the application. Replacing (not spawning) makes the JVM
// PID 1: the container runtime's SIGTERM reaches it directly and its exit status is the pod's.
func (l launcher) execApp(cmd []string) int {
	return l.replaceWith(l.finalArgv(cmd))
}

// finalArgv asks the jar whether an archive fits this exact launch and returns the command to
// start: the archive-enabled one, or the original on any doubt.
func (l launcher) finalArgv(cmd []string) []string {
	if filepath.Base(cmd[0]) != "java" {
		l.note("%s is not a java command; starting it unchanged", cmd[0])
		return cmd
	}
	java := l.locator.find(cmd[0])
	if java == "" {
		l.note("no java found for the helper; starting the command unchanged")
		return cmd
	}
	ctx, cancel := context.WithTimeout(context.Background(), l.probeTimeout)
	defer cancel()
	probe := exec.CommandContext(ctx, java, append([]string{"-jar", l.jar(), "run", "--print", "--format", "nul", "--"}, cmd...)...)
	var out bytes.Buffer
	probe.Stdout, probe.Stderr, probe.Env = &out, l.stderr, l.environ
	if err := probe.Run(); err != nil {
		l.note("helper failed (%v); starting the command unchanged", err)
		return cmd
	}
	argv := parseNul(out.Bytes())
	if argv == nil {
		return cmd
	}
	return argv
}

// parseNul reads NUL-terminated arguments. It returns nil for empty or malformed output, which
// callers treat as "no replacement command".
func parseNul(b []byte) []string {
	if len(b) == 0 || b[len(b)-1] != 0 {
		return nil
	}
	argv := []string{}
	for _, part := range bytes.Split(b[:len(b)-1], []byte{0}) {
		argv = append(argv, string(part))
	}
	if argv[0] == "" {
		return nil
	}
	return argv
}

func (l launcher) replaceWith(argv []string) int {
	path := argv[0]
	if !containsSlash(path) {
		if p, err := exec.LookPath(path); err == nil {
			path = p
		} else if filepath.Base(path) == "java" {
			path = l.locator.find(path)
		}
	}
	if path == "" || !containsSlash(path) {
		l.note("cannot find %s", argv[0])
		return 127
	}
	err := l.exec(path, argv, l.environ)
	if err == nil {
		return 0 // only a test double returns without error; syscall.Exec replaces the process
	}
	l.note("cannot start %s: %v", path, err)
	if errors.Is(err, os.ErrPermission) {
		return 126
	}
	return 127
}

func containsSlash(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] == '/' {
			return true
		}
	}
	return false
}

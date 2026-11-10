// Command mnemo-launcher is the shell-free entry point for the install, fetch and application-start
// steps of mnemo-warmup. It is a static binary so it runs in distroless and scratch-based images.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
)

func main() {
	root, err := toolsRoot()
	if err != nil {
		fmt.Fprintln(os.Stderr, "mnemo-launcher: cannot locate itself:", err)
		os.Exit(1)
	}
	l := launcher{
		locator:      systemLocator(),
		root:         root,
		stdout:       os.Stdout,
		stderr:       os.Stderr,
		environ:      os.Environ(),
		exec:         syscall.Exec,
		probeTimeout: defaultProbeTimeout,
	}
	os.Exit(l.run(os.Args[1:]))
}

// toolsRoot is the directory that holds bin/ and mnemo-warmup.jar: the parent of the directory
// this executable lives in, wherever the tools were installed.
func toolsRoot() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	return filepath.Dir(filepath.Dir(exe)), nil
}

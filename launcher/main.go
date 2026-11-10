// Command mnemo-launcher is the shell-free entry point for the install, fetch and application-start
// steps of mnemo-warmup. It is a static binary so it runs in distroless and scratch-based images.
package main

import (
	"fmt"
	"os"
)

func main() { os.Exit(run(os.Args[1:], systemLocator())) }

func run(args []string, locator javaLocator) int {
	if len(args) == 1 && args[0] == "which-java" {
		java := locator.find("")
		if java == "" {
			fmt.Fprintln(os.Stderr, "mnemo-launcher: no java found")
			return 1
		}
		fmt.Println(java)
		return 0
	}
	fmt.Fprintln(os.Stderr, "usage: mnemo-launcher which-java")
	return 2
}

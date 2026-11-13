// Local benchmark-only TCP proxy. It delays connections and limits response throughput.
// It must only be used with the isolated, anonymous kind registry; it does not implement TLS.
package main

import (
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"time"
)

func positive(name string) int64 {
	v, err := strconv.ParseInt(os.Getenv(name), 10, 64)
	if err != nil || v <= 0 {
		panic(name + " must be positive")
	}
	return v
}

func main() {
	upstream := os.Getenv("BENCH_REGISTRY_UPSTREAM")
	if upstream == "" {
		panic("BENCH_REGISTRY_UPSTREAM is required")
	}
	delay := time.Duration(positive("BENCH_DELAY_MS")) * time.Millisecond
	rate := positive("BENCH_BYTES_PER_SECOND")
	listener, err := net.Listen("tcp", ":5000")
	if err != nil {
		panic(err)
	}
	defer listener.Close()
	for {
		client, err := listener.Accept()
		if err != nil {
			fmt.Fprintln(os.Stderr, "listener failed:", err)
			return
		}
		go func() {
			defer client.Close()
			time.Sleep(delay)
			server, err := net.DialTimeout("tcp", upstream, 10*time.Second)
			if err != nil {
				return
			}
			defer server.Close()
			go func() { _, _ = io.Copy(server, client); _ = server.Close() }()
			// Limit each connection's server-to-client stream. This includes HTTP headers.
			buf := make([]byte, 32*1024)
			start := time.Now()
			var total int64
			for {
				n, readErr := server.Read(buf)
				if n > 0 {
					total += int64(n)
					due := time.Duration(float64(total) / float64(rate) * float64(time.Second))
					if wait := due - time.Since(start); wait > 0 {
						time.Sleep(wait)
					}
					if _, err := client.Write(buf[:n]); err != nil {
						break
					}
				}
				if readErr != nil {
					break
				}
			}
		}()
	}
}

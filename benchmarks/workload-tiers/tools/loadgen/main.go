package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

func main() {
	target := flag.String("url", "", "HTTP URL to request")
	workers := flag.Int("workers", 64, "number of persistent request workers")
	gate := flag.String("gate-file", "/run/mnemo-benchload/enabled", "requests run only while this file exists")
	flag.Parse()
	if *target == "" || *workers < 1 || *workers > 512 {
		fmt.Fprintln(os.Stderr, "usage: mnemo-benchload --url <url> [--workers 1..512] [--gate-file <path>]")
		os.Exit(2)
	}
	transport := &http.Transport{
		MaxIdleConns:        *workers,
		MaxIdleConnsPerHost: *workers,
		MaxConnsPerHost:     *workers,
		IdleConnTimeout:     90 * time.Second,
	}
	client := &http.Client{Transport: transport, Timeout: 15 * time.Second}
	var requests, failures atomic.Uint64
	var classes sync.Map
	countFailure := func(class string) {
		counter, _ := classes.LoadOrStore(class, new(atomic.Uint64))
		counter.(*atomic.Uint64).Add(1)
		failures.Add(1)
	}
	for i := 0; i < *workers; i++ {
		go func() {
			for {
				if _, err := os.Stat(*gate); err != nil {
					time.Sleep(10 * time.Millisecond)
					continue
				}
				resp, err := client.Get(*target)
				if err != nil {
					var netErr net.Error
					switch {
					case errors.As(err, &netErr) && netErr.Timeout():
						countFailure("network_timeout")
					case errors.Is(err, syscall.ECONNREFUSED):
						countFailure("connection_refused")
					case errors.Is(err, syscall.ECONNRESET):
						countFailure("connection_reset")
					case errors.As(err, new(*net.DNSError)):
						countFailure("dns_error")
					default:
						countFailure("network_other")
					}
					continue
				}
				_, copyErr := io.Copy(io.Discard, resp.Body)
				closeErr := resp.Body.Close()
				requests.Add(1)
				if copyErr != nil || closeErr != nil {
					countFailure("response_body_error")
				} else if resp.StatusCode < 200 || resp.StatusCode >= 300 {
					countFailure(fmt.Sprintf("http_%d", resp.StatusCode))
				}
			}
		}()
	}
	fmt.Fprintln(os.Stdout, "benchload-ready workers", *workers)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	var lastRequests, lastFailures uint64
	for range ticker.C {
		currentRequests, currentFailures := requests.Load(), failures.Load()
		fmt.Fprintf(os.Stdout, "benchload requests_per_second=%d failures_per_second=%d totals=%d/%d\n",
			currentRequests-lastRequests, currentFailures-lastFailures, currentRequests, currentFailures)
		classes.Range(func(k, v any) bool {
			fmt.Fprintf(os.Stdout, "benchload failure_class=%s total=%d\n", k, v.(*atomic.Uint64).Load())
			return true
		})
		lastRequests, lastFailures = currentRequests, currentFailures
	}
}

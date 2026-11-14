// Command mnemo-nodecache runs the optional, authenticated node cache service.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/baokhang83/mnemo-warmup/launcher/nodecache"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "mnemo-nodecache:", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string) error {
	flags := flag.NewFlagSet("mnemo-nodecache", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	path := flags.String("config", "", "path to node cache JSON configuration")
	if err := flags.Parse(args); err != nil || *path == "" || flags.NArg() != 0 {
		return errors.New("usage: mnemo-nodecache --config <file>")
	}
	cfg, err := nodecache.ReadServerConfig(*path)
	if err != nil {
		return err
	}
	server, err := nodecache.NewServer(cfg)
	if err != nil {
		return err
	}
	listener, err := net.Listen("tcp", server.Addr)
	if err != nil {
		return errors.New("cannot listen for node cache requests")
	}
	return serve(ctx, server, listener)
}

// serve drains active requests on shutdown, then force-closes them if the
// grace period expires. The cache directory is private to this service process.
func serve(ctx context.Context, server *http.Server, listener net.Listener) error {
	done := make(chan error, 1)
	go func() { done <- server.ServeTLS(listener, "", "") }()
	select {
	case err := <-done:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return errors.New("node cache server stopped unexpectedly")
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdown); err != nil {
			_ = server.Close()
			<-done
			return errors.New("node cache shutdown did not complete")
		}
		err := <-done
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			return errors.New("node cache server stopped unexpectedly")
		}
		return nil
	}
}

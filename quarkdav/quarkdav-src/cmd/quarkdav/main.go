package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"quarkdav/internal/app"
	"quarkdav/internal/dav"
	"quarkdav/internal/quarkcookie"
	"quarkdav/internal/remotefs"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "QuarkDav fatal: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	rt, err := app.LoadFromFlags(os.Args[1:])
	if err != nil {
		return err
	}
	if strings.TrimSpace(rt.Config.Cookie.Cookie) == "" {
		return errors.New("cookie is empty")
	}
	if strings.TrimSpace(rt.Config.TempDir) == "" {
		return errors.New("temp_dir is empty")
	}
	if err := os.MkdirAll(rt.Config.TempDir, 0700); err != nil {
		return fmt.Errorf("create temp dir: %w", err)
	}

	// Android's java.lang.Process API does not expose the child PID. Publish it
	// over stdout so the supervising service can persist it for stale-process cleanup.
	fmt.Printf("QUARKDAV_PID %d\n", os.Getpid())

	logger := log.New(os.Stdout, "QuarkDav: ", log.LstdFlags)
	drv := quarkcookie.New(&rt.Config.Cookie, rt.Save)
	initCtx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	err = drv.Init(initCtx)
	cancel()
	if err != nil {
		return fmt.Errorf("driver init failed: %w", err)
	}

	rfs := remotefs.New(drv, time.Duration(rt.Config.CacheTTLSeconds)*time.Second, rt.Config.TempDir)
	handler := &dav.Handler{
		Prefix:   rt.Config.Prefix,
		FS:       rfs,
		Locks:    dav.NewLockSystem(),
		Username: rt.Config.Username,
		Password: rt.Config.Password,
		NoAuth:   rt.Config.NoAuth,
		Logger:   logger,
	}
	mux := http.NewServeMux()
	mux.Handle(rt.Config.Prefix+"/", handler)
	mux.Handle(rt.Config.Prefix, handler)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, rt.Config.Prefix+"/", http.StatusFound)
	})

	listener, err := net.Listen("tcp", rt.Config.Listen)
	if err != nil {
		return fmt.Errorf("listen %s: %w", rt.Config.Listen, err)
	}
	server := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 30 * time.Second,
		IdleTimeout:       5 * time.Minute,
	}
	url := publicURL(listener.Addr().String(), rt.Config.Prefix)
	logger.Printf("driver %s initialized; root=%s; writable=%v", drv.Name(), drv.RootID(), drv.Writable())
	fmt.Printf("QUARKDAV_READY %s\n", url)

	errCh := make(chan error, 1)
	go func() {
		serveErr := server.Serve(listener)
		if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			errCh <- serveErr
			return
		}
		errCh <- nil
	}()

	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	select {
	case sig := <-sigCh:
		logger.Printf("received %s, shutting down", sig)
	case serveErr := <-errCh:
		return serveErr
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer shutdownCancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		_ = server.Close()
		return err
	}
	return nil
}

func publicURL(addr, prefix string) string {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "http://" + addr + strings.TrimRight(prefix, "/") + "/"
	}
	if host == "" || host == "::" || host == "[::]" || host == "0.0.0.0" {
		host = bestLANAddress()
	}
	return "http://" + net.JoinHostPort(strings.Trim(host, "[]"), port) + strings.TrimRight(prefix, "/") + "/"
}

func bestLANAddress() string {
	interfaces, err := net.Interfaces()
	if err != nil {
		return "127.0.0.1"
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			var ip net.IP
			switch value := address.(type) {
			case *net.IPNet:
				ip = value.IP
			case *net.IPAddr:
				ip = value.IP
			}
			if ip != nil && !ip.IsLoopback() && ip.To4() != nil {
				return ip.String()
			}
		}
	}
	return "127.0.0.1"
}

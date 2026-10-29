package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// Scan each candidate through a separate genuine DNSTT session. DNS-only
// preflights are never marked valid. The Android scan service bounds the number
// of worker processes, and each worker runs at most one DNSTT child at a time.
func scanResolvers(ctx context.Context, cfg engineConfig, resolvers []string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	dir, err := os.MkdirTemp(".", ".slowdns-scan-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	dir, err = filepath.Abs(dir)
	if err != nil {
		return err
	}
	valid := 0
	for index, resolver := range resolvers {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		fmt.Printf("WD_SCAN event=testing resolver=%s transport=udp\n", resolver)
		ok := scanOne(ctx, exe, dir, cfg, resolver)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if ok {
			valid++
			fmt.Printf("WD_SCAN event=valid resolver=%s\n", resolver)
		} else {
			fmt.Printf("WD_SCAN event=rejected resolver=%s\n", resolver)
		}
		fmt.Printf("WD_PROGRESS phase=mtu completed=%d total=%d valid=%d rejected=%d\n", index+1, len(resolvers), valid, index+1-valid)
	}
	fmt.Printf("WD_SCAN event=complete total=%d valid=%d rejected=%d\n", len(resolvers), valid, len(resolvers)-valid)
	return nil
}

func scanOne(ctx context.Context, exe, dir string, cfg engineConfig, resolver string) bool {
	// The app scan worker is sequential; choosing a fresh ephemeral port avoids
	// collisions between the app's independent worker processes.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return false
	}
	cfg.ListenPort = ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	cfg.ListenHost = "127.0.0.1"
	// Scan is explicitly for the profile's primary zone, not an implicit pool.
	cfg.Domains = nil
	cfg.BootstrapResolvers = nil
	cfg.ServerAddresses = nil
	cfg.LocalUsername = ""
	cfg.LocalPassword = ""
	cfg.StartupTimeoutSec = 25
	data, err := json.Marshal(cfg)
	if err != nil {
		return false
	}
	config := filepath.Join(dir, "config.json")
	list := filepath.Join(dir, "resolver.txt")
	if os.WriteFile(config, data, 0600) != nil || os.WriteFile(list, []byte(resolver), 0600) != nil {
		return false
	}
	runCtx, cancel := context.WithTimeout(ctx, 35*time.Second)
	defer cancel()
	cmd := exec.CommandContext(runCtx, exe, "-config", config, "-resolvers", list)
	output, err := cmd.StdoutPipe()
	if err != nil {
		return false
	}
	cmd.Stderr = cmd.Stdout
	if cmd.Start() != nil {
		return false
	}
	passed := false
	scanner := bufio.NewScanner(output)
	scanner.Buffer(make([]byte, 4096), 65536)
	for scanner.Scan() {
		if scanner.Text() == "WD_PROGRESS phase=connected percent=100" {
			passed = true
			_ = cmd.Process.Signal(os.Interrupt)
			break
		}
	}
	// Closing the pipe is necessary if a descendant keeps stdout open.
	_ = output.Close()
	_ = cmd.Wait()
	return passed && ctx.Err() == nil
}

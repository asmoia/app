package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// A pool distributes individual TCP connections over independently verified
// tunnels. It does not stripe a single stream across unrelated servers.
type tunnelPool struct {
	mu      sync.Mutex
	ready   map[int]string
	active  map[net.Conn]struct{}
	counter atomic.Uint64
	sem     chan struct{}
	workers sync.WaitGroup
}

func newTunnelPool() *tunnelPool {
	return &tunnelPool{ready: map[int]string{}, active: map[net.Conn]struct{}{}, sem: make(chan struct{}, 256)}
}
func (p *tunnelPool) set(slot int, addr string) int {
	p.mu.Lock()
	defer p.mu.Unlock()
	if addr == "" {
		delete(p.ready, slot)
	} else {
		p.ready[slot] = addr
	}
	return len(p.ready)
}
func (p *tunnelPool) count() int { p.mu.Lock(); defer p.mu.Unlock(); return len(p.ready) }
func (p *tunnelPool) choose() (string, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.ready) == 0 {
		return "", false
	}
	keys := make([]int, 0, len(p.ready))
	for i := 0; i < 3; i++ {
		if _, ok := p.ready[i]; ok {
			keys = append(keys, i)
		}
	}
	if len(keys) == 0 {
		return "", false
	}
	idx := p.counter.Add(1) - 1
	return p.ready[keys[int(idx%uint64(len(keys)))]], true
}
func (p *tunnelPool) track(c net.Conn, add bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if add {
		p.active[c] = struct{}{}
	} else {
		delete(p.active, c)
	}
}
func (p *tunnelPool) shutdown() {
	p.mu.Lock()
	for c := range p.active {
		_ = c.Close()
	}
	p.mu.Unlock()
	p.workers.Wait()
}
func (p *tunnelPool) serve(ctx context.Context, ln net.Listener) error {
	done := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = ln.Close()
		case <-done:
		}
	}()
	defer close(done)
	defer p.shutdown()
	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		select {
		case p.sem <- struct{}{}:
		default:
			_ = c.Close()
			continue
		}
		p.track(c, true)
		p.workers.Add(1)
		go func() {
			defer p.workers.Done()
			defer func() { <-p.sem; p.track(c, false); _ = c.Close() }()
			addr, ok := p.choose()
			if !ok {
				return
			}
			up, err := net.DialTimeout("tcp", addr, 3*time.Second)
			if err != nil {
				return
			}
			p.track(up, true)
			defer func() { p.track(up, false); _ = up.Close() }()
			done := make(chan struct{}, 2)
			pipe := func(dst, src net.Conn) {
				_, _ = io.Copy(dst, src)
				if tcp, ok := dst.(*net.TCPConn); ok {
					_ = tcp.CloseWrite()
				}
				done <- struct{}{}
			}
			go pipe(up, c)
			go pipe(c, up)
			<-done
			<-done
		}()
	}
}
func runPool(ctx context.Context, cfg engineConfig, resolvers []string) error {
	if len(cfg.Domains) > 20 {
		return errors.New("too many server domains")
	}
	size := cfg.PoolSize
	if size < 1 {
		size = 2
	}
	if size > 3 {
		size = 3
	}
	if size > len(cfg.Domains) {
		size = len(cfg.Domains)
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	if len(resolvers) > 8 {
		fmt.Println("WD_PROGRESS phase=selecting percent=10")
		resolvers = preflightResolvers(ctx, resolvers, 6*time.Second)
	}
	dir, err := os.MkdirTemp(".", ".slowdns-pool-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	dir, err = filepath.Abs(dir)
	if err != nil {
		return err
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	p := newTunnelPool()
	var slotWG sync.WaitGroup
	defer func() { cancel(); slotWG.Wait() }()
	for slot := 0; slot < size; slot++ {
		slotWG.Add(1)
		go func(slot int) { defer slotWG.Done(); poolSlot(ctx, p, exe, dir, slot, size, cfg, resolvers) }(slot)
	}
	listener, err := net.Listen("tcp", net.JoinHostPort(cfg.ListenHost, strconv.Itoa(cfg.ListenPort)))
	if err != nil {
		return err
	}
	done := make(chan error, 1)
	go func() { done <- p.serve(ctx, listener) }()
	fmt.Println("WD_PROGRESS phase=authenticating percent=15")
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	startup := time.Now().Add(100 * time.Second)
	var wasReady bool
	var lastReady time.Time
	for {
		select {
		case <-ctx.Done():
			return nil
		case err := <-done:
			return fmt.Errorf("pool SOCKS listener stopped: %v", err)
		case now := <-ticker.C:
			if p.count() > 0 {
				lastReady = now
				if !wasReady {
					wasReady = true
					fmt.Println("WD_PROGRESS phase=connected percent=100")
				}
			} else if !wasReady && now.After(startup) {
				return errors.New("no server passed authenticated tunnel verification")
			} else if wasReady && !lastReady.IsZero() && now.Sub(lastReady) > 30*time.Second {
				return errors.New("all SlowDNS server sessions are unavailable")
			}
		}
	}
}
func poolSlot(ctx context.Context, p *tunnelPool, exe, dir string, slot, stride int, cfg engineConfig, resolvers []string) {
	iteration := 0
	for ctx.Err() == nil {
		zone := cfg.Domains[(slot+iteration*stride)%len(cfg.Domains)]
		iteration++
		d := filepath.Join(dir, fmt.Sprintf("slot-%d", slot))
		if os.MkdirAll(d, 0700) != nil {
			return
		}
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return
		}
		port := ln.Addr().(*net.TCPAddr).Port
		_ = ln.Close()
		childCfg := cfg
		childCfg.Domain = zone
		childCfg.Domains = nil
		childCfg.ListenHost = "127.0.0.1"
		childCfg.ListenPort = port
		childCfg.StartupTimeoutSec = 35
		childCfg.SkipPreflight = true
		data, err := json.Marshal(childCfg)
		if err != nil {
			return
		}
		config := filepath.Join(d, "config.json")
		list := filepath.Join(d, "resolvers.txt")
		if os.WriteFile(config, data, 0600) != nil || os.WriteFile(list, []byte(joinLines(resolvers)), 0600) != nil {
			return
		}
		cmd := exec.CommandContext(ctx, exe, "-config", config, "-resolvers", list)
		output, err := cmd.StdoutPipe()
		if err != nil {
			return
		}
		cmd.Stderr = cmd.Stdout
		if err = cmd.Start(); err != nil {
			select {
			case <-ctx.Done():
				return
			case <-time.After(2 * time.Second):
				continue
			}
		}
		var ready atomic.Bool
		timer := time.AfterFunc(45*time.Second, func() {
			if !ready.Load() {
				_ = cmd.Process.Kill()
			}
		})
		scanner := bufio.NewScanner(output)
		scanner.Buffer(make([]byte, 4096), 65536)
		for scanner.Scan() {
			if scanner.Text() == "WD_PROGRESS phase=connected percent=100" {
				ready.Store(true)
				timer.Stop()
				count := p.set(slot, net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
				fmt.Printf("SLOWDNS_POOL ready=%d server=%s\n", count, zone)
			}
		}
		timer.Stop()
		_ = output.Close()
		_ = cmd.Wait()
		if ready.Load() {
			count := p.set(slot, "")
			fmt.Printf("SLOWDNS_POOL ready=%d server_stopped=%s\n", count, zone)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(2 * time.Second):
		}
	}
}
func joinLines(items []string) string { return strings.Join(items, "\n") + "\n" }

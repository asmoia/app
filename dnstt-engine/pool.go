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
	"syscall"
	"time"
)

// A pool distributes individual TCP connections over independently verified
// tunnels. It does not stripe a single stream across unrelated servers.
type tunnelPool struct {
	mu        sync.Mutex
	ready     map[int]string
	active    map[net.Conn]struct{}
	counter   atomic.Uint64
	sem       chan struct{}
	workers   sync.WaitGroup
	localUser string
	localPass string
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

type poolPath struct {
	slot int
	addr string
}

// Take a round-robin snapshot once per client. A failed CONNECT can be retried
// on another verified session before any SOCKS success is sent to the client.
func (p *tunnelPool) orderedPaths() []poolPath {
	p.mu.Lock()
	defer p.mu.Unlock()
	paths := make([]poolPath, 0, len(p.ready))
	for i := 0; i < 3; i++ {
		if addr, ok := p.ready[i]; ok {
			paths = append(paths, poolPath{slot: i, addr: addr})
		}
	}
	if len(paths) < 2 {
		return paths
	}
	start := int((p.counter.Add(1) - 1) % uint64(len(paths)))
	return append(append([]poolPath(nil), paths[start:]...), paths[:start]...)
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
			_ = p.handleClient(c)
		}()
	}
}

// Accept SOCKS once and retry the original CONNECT on another session only
// before the client sees a successful reply; active byte streams are never
// moved between independent servers.
func (p *tunnelPool) handleClient(c net.Conn) error {
	request, err := acceptLocalSocks(c, p.localUser, p.localPass)
	if err != nil {
		return err
	}
	paths := p.orderedPaths()
	for _, path := range paths {
		up, err := net.DialTimeout("tcp", path.addr, 3*time.Second)
		if err != nil {
			continue
		}
		p.track(up, true)
		_ = up.SetDeadline(time.Now().Add(10 * time.Second))
		err = authenticateLocalUpstream(up, p.localUser, p.localPass)
		if err == nil {
			_, err = up.Write(request)
		}
		var reply []byte
		if err == nil {
			reply, err = readSocksReply(up)
		}
		if err != nil || len(reply) < 2 || reply[1] != 0 {
			p.track(up, false)
			_ = up.Close()
			fmt.Printf("SLOWDNS_POOL connect_retry slot=%d\n", path.slot)
			continue
		}
		if _, err = c.Write(reply); err != nil {
			p.track(up, false)
			_ = up.Close()
			return err
		}
		_ = c.SetDeadline(time.Time{})
		_ = up.SetDeadline(time.Time{})
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
		p.track(up, false)
		_ = up.Close()
		return nil
	}
	_, _ = c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
	return errors.New("all available SlowDNS sessions failed CONNECT")
}

func authenticateLocalUpstream(c net.Conn, user, pass string) error {
	if user != "" {
		return upstreamAuth(c, user, pass)
	}
	if _, err := c.Write([]byte{5, 1, 0}); err != nil {
		return err
	}
	reply, err := readBytes(c, 2)
	if err != nil {
		return err
	}
	if !equal(reply, []byte{5, 0}) {
		return errors.New("child SOCKS method refused")
	}
	return nil
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
	p.localUser, p.localPass = cfg.LocalUsername, cfg.LocalPassword
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
	startup := time.Now().Add(65 * time.Second)
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
		childCfg.StartupTimeoutSec = 27
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
		// Each slot and its native client share a private process group, so a
		// stalled or killed slot cannot leave the client running as an orphan.
		cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
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
		watchCtx, stopWatch := context.WithCancel(ctx)
		var watchWG sync.WaitGroup
		fmt.Printf("SLOWDNS_SERVER trying=%s\n", zone)
		timer := time.AfterFunc(32*time.Second, func() {
			if !ready.Load() {
				killSlotGroup(cmd)
			}
		})
		scanner := bufio.NewScanner(output)
		scanner.Buffer(make([]byte, 4096), 65536)
		for scanner.Scan() {
			if scanner.Text() == "WD_PROGRESS phase=connected percent=100" && ready.CompareAndSwap(false, true) {
				timer.Stop()
				addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
				count := p.set(slot, addr)
				fmt.Printf("SLOWDNS_POOL ready=%d server=%s\n", count, zone)
				watchWG.Add(1)
				go func() {
					defer watchWG.Done()
					watchSlotHealth(watchCtx, p, slot, addr, cfg, cmd)
				}()
			} else if strings.HasPrefix(scanner.Text(), "SLOWDNS_PATH ") || strings.HasPrefix(scanner.Text(), "SLOWDNS_PROBE ") {
				fmt.Printf("SLOWDNS_SERVER server=%s %s\n", zone, scanner.Text())
			} else if _, reason, ok := strings.Cut(scanner.Text(), "SLOWDNS_ERROR:"); ok {
				fmt.Printf("SLOWDNS_SERVER server=%s failed=%s\n", zone, strings.TrimSpace(reason))
			}
		}
		timer.Stop()
		stopWatch()
		watchWG.Wait()
		_ = output.Close()
		// Kill remaining descendants before reaping the group leader; the PID
		// cannot be recycled while it is still waiting to be reaped.
		killSlotGroup(cmd)
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
func killSlotGroup(child *exec.Cmd) {
	if child.Process != nil {
		_ = syscall.Kill(-child.Process.Pid, syscall.SIGKILL)
	}
}

func watchSlotHealth(ctx context.Context, p *tunnelPool, slot int, addr string, cfg engineConfig, child *exec.Cmd) {
	verifyURL := cfg.VerifyURL
	if verifyURL == "" {
		verifyURL = "https://example.com/"
	}
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()
	failures := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			probeCtx, cancel := context.WithTimeout(ctx, 12*time.Second)
			err := verifyTunnel(probeCtx, addr, cfg.LocalUsername, cfg.LocalPassword, verifyURL)
			cancel()
			if ctx.Err() != nil {
				return
			}
			if err == nil {
				failures = 0
				continue
			}
			failures++
			fmt.Printf("SLOWDNS_HEALTH slot=%d failed=%d\n", slot, failures)
			if failures >= 2 {
				count := p.set(slot, "")
				fmt.Printf("SLOWDNS_HEALTH slot=%d restarting=true ready=%d\n", slot, count)
				killSlotGroup(child)
				return
			}
		}
	}
}
func joinLines(items []string) string { return strings.Join(items, "\n") + "\n" }

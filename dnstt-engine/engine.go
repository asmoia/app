package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type engineConfig struct {
	Domain             string            `json:"domain"`
	Domains            []string          `json:"domains"`
	PoolSize           int               `json:"poolSize"`
	SkipPreflight      bool              `json:"skipPreflight"`
	BootstrapResolvers []string          `json:"bootstrapResolvers"`
	ServerAddresses    map[string]string `json:"serverAddresses"`
	PublicKey          string            `json:"publicKey"`
	Report             string            `json:"report"`
	ClientBinary       string            `json:"clientBinary"`
	Username           string            `json:"username"`
	Password           string            `json:"password"`
	ListenHost         string            `json:"listenHost"`
	ListenPort         int               `json:"listenPort"`
	LocalUsername      string            `json:"localUsername"`
	LocalPassword      string            `json:"localPassword"`
	VerifyURL          string            `json:"verifyUrl"`
	StartupTimeoutSec  int               `json:"startupTimeoutSec"`
}

func (c engineConfig) validate() error {
	if c.Domain == "" || c.PublicKey == "" || c.ClientBinary == "" || c.Username == "" {
		return errors.New("domain, key, compatible client and remote credentials are required")
	}
	if net.ParseIP(c.ListenHost) == nil || !net.ParseIP(c.ListenHost).IsLoopback() {
		return errors.New("SlowDNS SOCKS must bind to loopback")
	}
	if c.ListenPort < 1 || c.ListenPort > 65535 {
		return errors.New("invalid listen port")
	}
	if !filepath.IsAbs(c.ClientBinary) {
		return errors.New("client executable must use an absolute path")
	}
	if len(c.Username) > 255 || len(c.Password) > 255 || len(c.LocalUsername) > 255 || len(c.LocalPassword) > 255 {
		return errors.New("SOCKS credentials exceed 255 bytes")
	}
	if (c.LocalUsername == "") != (c.LocalPassword == "") {
		return errors.New("local SOCKS authentication needs user and password")
	}
	if c.VerifyURL != "" {
		u, err := url.Parse(c.VerifyURL)
		if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil {
			return errors.New("verification URL must be HTTPS")
		}
	}
	return nil
}

func expandResolvers(text string, max int) ([]string, error) {
	out := make([]string, 0)
	seen := map[string]bool{}
	add := func(addr netip.Addr, port string) error {
		if !addr.IsValid() || addr.IsUnspecified() || addr.IsMulticast() {
			return errors.New("invalid resolver IP")
		}
		key := net.JoinHostPort(addr.String(), port)
		if !seen[key] {
			if len(out) >= max {
				return errors.New("too many resolvers")
			}
			seen[key] = true
			out = append(out, key)
		}
		return nil
	}
	for _, line := range strings.Fields(text) {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		host := line
		port := "53"
		if h, p, e := net.SplitHostPort(line); e == nil {
			host, port = h, p
		}
		pn, e := strconv.Atoi(port)
		if e != nil || pn < 1 || pn > 65535 {
			return nil, fmt.Errorf("invalid resolver port")
		}
		if prefix, e := netip.ParsePrefix(host); e == nil {
			prefix = prefix.Masked()
			bits := prefix.Addr().BitLen() - prefix.Bits()
			if bits > 16 {
				return nil, errors.New("resolver subnet too large")
			}
			for addr := prefix.Addr(); prefix.Contains(addr); addr = addr.Next() {
				if e := add(addr, port); e != nil {
					return nil, e
				}
			}
			continue
		}
		addr, e := netip.ParseAddr(host)
		if e != nil {
			return nil, fmt.Errorf("invalid resolver address %q", host)
		}
		if e = add(addr.Unmap(), port); e != nil {
			return nil, e
		}
	}
	if len(out) == 0 {
		return nil, errors.New("no resolvers configured")
	}
	return out, nil
}
func main() {
	config := flag.String("config", "", "engine JSON config")
	resolvers := flag.String("resolvers", "", "resolver list")
	scanOnly := flag.Bool("scan-only", false, "verify each resolver with an authenticated tunnel")
	flag.Parse()
	if *config == "" || *resolvers == "" {
		log.Fatal("-config and -resolvers are required")
	}
	data, err := os.ReadFile(*config)
	if err != nil {
		log.Fatal(err)
	}
	var cfg engineConfig
	if err = json.Unmarshal(data, &cfg); err != nil {
		log.Fatal("invalid SlowDNS configuration")
	}
	if err = cfg.validate(); err != nil {
		log.Fatal(err)
	}
	data, err = os.ReadFile(*resolvers)
	if err != nil {
		log.Fatal(err)
	}
	addresses, err := expandResolvers(string(data), 65536)
	if err != nil {
		log.Fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if *scanOnly {
		if err = scanResolvers(ctx, cfg, addresses); err != nil {
			log.Printf("SLOWDNS_ERROR: %s", err)
			os.Exit(1)
		}
		return
	}
	if len(cfg.Domains) > 1 {
		err = runPool(ctx, cfg, addresses)
	} else {
		err = runEngine(ctx, cfg, addresses)
	}
	if err != nil {
		log.Printf("SLOWDNS_ERROR: %s", err)
		os.Exit(1)
	}
}

// Explicit bootstrap paths are included only when Android selected its default
// resolver profile. A custom resolver list and authenticated scanner stay exact.
func prioritizedResolvers(cfg engineConfig, addresses []string) []string {
	if len(cfg.BootstrapResolvers) == 0 {
		return addresses
	}
	out := make([]string, 0, len(addresses)+len(cfg.BootstrapResolvers)+1)
	seen := make(map[string]bool, len(addresses))
	add := func(a string) {
		if !seen[a] {
			seen[a] = true
			out = append(out, a)
		}
	}
	candidates := append([]string(nil), cfg.BootstrapResolvers...)
	if direct := cfg.ServerAddresses[cfg.Domain]; direct != "" {
		candidates = append(candidates, direct)
	}
	for _, candidate := range candidates {
		if parsed, err := expandResolvers(candidate, 1); err == nil && len(parsed) == 1 {
			add(parsed[0])
		}
	}
	for _, a := range addresses {
		add(a)
	}
	return out
}

func runEngine(ctx context.Context, cfg engineConfig, addresses []string) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	addresses = prioritizedResolvers(cfg, addresses)
	if len(addresses) > 8 {
		fmt.Printf("SLOWDNS_PATH discovering_tunnel_resolvers candidates=%d\n", len(addresses))
	}
	relay, err := newRelay(addresses, cfg.Domain, 1800*time.Millisecond)
	if err != nil {
		return err
	}
	dns, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return err
	}
	dnsDone := make(chan error, 1)
	go func() { dnsDone <- relay.serve(ctx, dns) }()
	// Let the OS allocate unique loopback ports. Never expose DNSTT's raw TCP
	// endpoint or the DNS bridge on the LAN.
	temp, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	upstream := temp.Addr().String()
	_ = temp.Close()
	child := exec.CommandContext(ctx, cfg.ClientBinary, "-udp", dns.LocalAddr().String(), "-report", cfg.Report, "-pubkey", cfg.PublicKey, cfg.Domain, upstream)
	// The child receives no SOCKS credentials in argv or environment. Preserve
	// its diagnostics so timeouts and crashes are visible to the app.
	child.Stdout = os.Stdout
	child.Stderr = os.Stderr
	child.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
	if err = child.Start(); err != nil {
		return fmt.Errorf("compatible DNSTT client could not start: %w", err)
	}
	childDone := make(chan error, 1)
	go func() { childDone <- child.Wait() }()
	defer func() {
		cancel()
		select {
		case <-childDone:
		case <-time.After(3 * time.Second):
			_ = child.Process.Kill()
		}
	}()
	listen, err := net.Listen("tcp", net.JoinHostPort(cfg.ListenHost, strconv.Itoa(cfg.ListenPort)))
	if err != nil {
		return err
	}
	proxy := newSocksRelay(upstream, cfg.Username, cfg.Password, cfg.LocalUsername, cfg.LocalPassword)
	go reportSlowDNSMetrics(ctx, relay, proxy)
	proxyDone := make(chan error, 1)
	go func() { proxyDone <- proxy.serve(ctx, listen) }()
	fmt.Println("WD_PROGRESS phase=authenticating percent=15")
	verifyURL := cfg.VerifyURL
	if verifyURL == "" {
		verifyURL = "https://example.com/"
	}
	timeout := time.Duration(cfg.StartupTimeoutSec) * time.Second
	if timeout < 10*time.Second || timeout > 180*time.Second {
		timeout = 90 * time.Second
	}
	deadline := time.Now().Add(timeout)
	probeCtx, probeCancel := context.WithDeadline(ctx, deadline)
	defer probeCancel()
	attempt := 0
	for {
		if time.Now().After(deadline) {
			return errors.New("authenticated tunnel verification timed out")
		}
		select {
		case e := <-childDone:
			return fmt.Errorf("DNSTT client exited before verification: %v", e)
		case e := <-dnsDone:
			return fmt.Errorf("DNS bridge stopped: %v", e)
		case e := <-proxyDone:
			return fmt.Errorf("SOCKS relay stopped: %v", e)
		case <-ctx.Done():
			return nil
		default:
		}
		attempt++
		if e := verifyTunnel(probeCtx, listen.Addr().String(), cfg.LocalUsername, cfg.LocalPassword, verifyURL); e == nil {
			break
		} else {
			fmt.Printf("SLOWDNS_PROBE attempt=%d error=%v\n", attempt, e)
		}
		if ctx.Err() != nil {
			return nil
		}
		if probeCtx.Err() != nil {
			return errors.New("authenticated tunnel verification timed out")
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(time.Second):
		}
	}
	fmt.Println("WD_PROGRESS phase=connected percent=100")
	select {
	case <-ctx.Done():
		return nil
	case e := <-childDone:
		return fmt.Errorf("DNSTT client stopped: %v", e)
	case e := <-dnsDone:
		return fmt.Errorf("DNS bridge stopped: %v", e)
	case e := <-proxyDone:
		return fmt.Errorf("SOCKS relay stopped: %v", e)
	}
}

func verifyTunnel(ctx context.Context, proxy, user, pass, url string) error {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	transport := &http.Transport{DisableKeepAlives: true, Proxy: nil, DialContext: func(ctx context.Context, network, target string) (net.Conn, error) {
		d := net.Dialer{}
		c, err := d.DialContext(ctx, "tcp", proxy)
		if err != nil {
			return nil, err
		}
		_ = c.SetDeadline(time.Now().Add(15 * time.Second))
		fail := func(e error) (net.Conn, error) { _ = c.Close(); return nil, e }
		method := byte(0)
		if user != "" {
			method = 2
		}
		if _, err = c.Write([]byte{5, 1, method}); err != nil {
			return fail(err)
		}
		reply, err := readBytes(c, 2)
		if err != nil || len(reply) != 2 || reply[0] != 5 || reply[1] != method {
			return fail(errors.New("local SOCKS method failed"))
		}
		if method == 2 {
			a := append([]byte{1, byte(len(user))}, []byte(user)...)
			a = append(a, byte(len(pass)))
			a = append(a, []byte(pass)...)
			if _, err = c.Write(a); err != nil {
				return fail(err)
			}
			r, e := readBytes(c, 2)
			if e != nil || !equal(r, []byte{1, 0}) {
				return fail(errors.New("local SOCKS auth failed"))
			}
		}
		host, port, err := net.SplitHostPort(target)
		if err != nil || len(host) > 255 {
			return fail(errors.New("invalid probe destination"))
		}
		p, err := strconv.Atoi(port)
		if err != nil || p < 1 || p > 65535 {
			return fail(errors.New("invalid probe port"))
		}
		req := append([]byte{5, 1, 0, 3, byte(len(host))}, []byte(host)...)
		req = append(req, byte(p>>8), byte(p))
		if _, err = c.Write(req); err != nil {
			return fail(err)
		}
		r, e := readSocksReply(c)
		if e != nil || r[1] != 0 {
			return fail(errors.New("SOCKS CONNECT failed"))
		}
		_ = c.SetDeadline(time.Time{})
		return c, nil
	}}
	defer transport.CloseIdleConnections()
	client := http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	response, err := client.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	_, err = io.CopyN(io.Discard, bufio.NewReader(response.Body), 1)
	if err != nil && err != io.EOF {
		return err
	}
	if response.StatusCode < 200 || response.StatusCode >= 400 {
		return fmt.Errorf("probe HTTP status %d", response.StatusCode)
	}
	return nil
}

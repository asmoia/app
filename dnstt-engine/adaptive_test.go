package main

import (
	"context"
	"fmt"
	"golang.org/x/net/dns/dnsmessage"
	"net"
	storm "stormdns-go/internal/client"
	"testing"
	"time"
)

func tunnelResolver(t *testing.T, withAnswer bool) string {
	t.Helper()
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	go func() {
		buf := make([]byte, 4096)
		for {
			n, peer, e := c.ReadFromUDP(buf)
			if e != nil {
				return
			}
			var m dnsmessage.Message
			if m.Unpack(buf[:n]) != nil {
				continue
			}
			m.Header.Response = true
			if withAnswer {
				m.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: m.Questions[0].Name, Type: dnsmessage.TypeTXT, Class: dnsmessage.ClassINET, TTL: 1}, Body: &dnsmessage.TXTResource{TXT: []string{"tunnel"}}}}
			}
			out, e := m.Pack()
			if e == nil {
				_, _ = c.WriteToUDP(out, peer)
			}
		}
	}()
	return c.LocalAddr().String()
}
func TestAdaptiveOnlyActivatesTunnelAnswers(t *testing.T) {
	bad := tunnelResolver(t, false)
	good := tunnelResolver(t, true)
	addrs := []string{bad, good}
	for i := 0; i < 8; i++ {
		addrs = append(addrs, fmt.Sprintf("127.0.0.1:%d", 22000+i))
	}
	r, err := newRelay(addrs, "test.invalid", 80*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if r.bal.ValidCount() != 0 {
		t.Fatal("unverified DNS path started active")
	}
	first, err := r.exchange(query(t, "probe.test.invalid."))
	if err != nil || len(first) == 0 {
		t.Fatalf("tunnel response: %v", err)
	}
	if _, err = r.exchange(query(t, "next.test.invalid.")); err != nil {
		t.Fatal(err)
	}
	r.mu.Lock()
	_, badActive := r.activePaths[bad]
	_, goodActive := r.activePaths[good]
	r.mu.Unlock()
	if badActive || !goodActive {
		t.Fatalf("bad=%v good=%v", badActive, goodActive)
	}
}
func TestAdaptiveRescanRechecksPreferredAndDoesNotSkipUndispatched(t *testing.T) {
	addrs := []string{}
	for i := 0; i < 4; i++ {
		addrs = append(addrs, tunnelResolver(t, true))
	}
	for i := 0; i < 28; i++ {
		addrs = append(addrs, fake(t, false, false))
	}
	for i := 0; i < 1200; i++ {
		addrs = append(addrs, fmt.Sprintf("127.0.0.1:%d", 30000+i))
	}
	r, err := newRelay(addrs, "test.invalid", 100*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = r.exchange(query(t, "first.test.invalid.")); err != nil {
		t.Fatal(err)
	}
	r.scanMu.Lock()
	done := r.scanDone
	r.scanMu.Unlock()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("scan did not stop")
	}
	r.scanMu.Lock()
	offset := r.scanOffset
	r.scanMu.Unlock()
	if offset >= 200 {
		t.Fatalf("cursor jumped over unsent candidates: %d", offset)
	}
	r.mu.Lock()
	r.activePaths = map[string]storm.Connection{}
	r.updateActiveLocked()
	r.mu.Unlock()
	if _, err = r.exchange(query(t, "retry.test.invalid.")); err != nil {
		t.Fatal("preferred paths were not retried:", err)
	}
}

func TestCustomResolversRemainExact(t *testing.T) {
	orig := []string{"1.2.3.4:53"}
	out := prioritizedResolvers(engineConfig{Domain: "test.invalid", ServerAddresses: map[string]string{"test.invalid": "5.6.7.8:53"}}, orig)
	if len(out) != 1 || out[0] != orig[0] {
		t.Fatalf("custom resolver was changed: %v", out)
	}
	out = prioritizedResolvers(engineConfig{Domain: "test.invalid", BootstrapResolvers: []string{"9.9.9.9", "1.2.3.4"}, ServerAddresses: map[string]string{"test.invalid": "5.6.7.8:53"}}, orig)
	if len(out) != 3 || out[0] != "9.9.9.9:53" || out[1] != "1.2.3.4:53" || out[2] != "5.6.7.8:53" {
		t.Fatalf("unexpected ordered/deduplicated resolvers: %v", out)
	}
}
func TestTunnelProbeHonorsDeadline(t *testing.T) {
	a := fake(t, false, false)
	r, _ := newRelay([]string{a}, "test.invalid", 300*time.Millisecond)
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Millisecond)
	defer cancel()
	start := time.Now()
	if _, err := r.tunnelProbe(ctx, a, query(t, "probe.test.invalid.")); err == nil {
		t.Fatal("unexpected response")
	}
	if time.Since(start) > 250*time.Millisecond {
		t.Fatal("deadline ignored")
	}
}

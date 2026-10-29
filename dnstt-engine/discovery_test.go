package main

import (
	"context"
	"fmt"
	"golang.org/x/net/dns/dnsmessage"
	"net"
	"testing"
	"time"
)

func TestPreflightBoundedAndFallback(t *testing.T) {
	c, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	go func() {
		b := make([]byte, 4096)
		for {
			n, addr, err := c.ReadFromUDP(b)
			if err != nil {
				return
			}
			var m dnsmessage.Message
			if m.Unpack(b[:n]) != nil {
				continue
			}
			m.Header.Response = true
			m.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: m.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 30}, Body: &dnsmessage.AResource{A: [4]byte{1, 2, 3, 4}}}}
			r, err := m.Pack()
			if err == nil {
				c.WriteToUDP(r, addr)
			}
		}
	}()
	candidates := []string{c.LocalAddr().String()}
	for i := 1; i < 4096; i++ {
		candidates = append(candidates, fmt.Sprintf("127.0.0.1:%d", 20000+i))
	}
	start := time.Now()
	got := preflightResolvers(context.Background(), candidates, 1200*time.Millisecond)
	if time.Since(start) > 2*time.Second {
		t.Fatal("unbounded preflight")
	}
	if len(got) != 2 || got[0] != candidates[0] || got[1] == candidates[0] {
		t.Fatalf("expected responsive resolver and one unverified fallback; got=%v", got)
	}
	allResponsive := make([]string, 64)
	copy(allResponsive, candidates[:64])
	selected := retainUnverifiedCandidates(allResponsive, candidates)
	if len(selected) != 64 || selected[63] == candidates[63] {
		t.Fatalf("expected a bounded sample that reserves unverified paths: %v", selected)
	}
	none := candidates[1:20]
	got = preflightResolvers(context.Background(), none, time.Second)
	if len(got) != len(none) {
		t.Fatal("no-response case should retain original candidates")
	}
}

package main

import (
	"net"
	"sync/atomic"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func TestResolveAMServerUsesBoundedExplicitBootstrap(t *testing.T) {
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	var requests atomic.Int32
	go func() {
		b := make([]byte, 2048)
		for {
			n, addr, e := c.ReadFromUDP(b)
			if e != nil {
				return
			}
			requests.Add(1)
			var m dnsmessage.Message
			if m.Unpack(b[:n]) != nil || len(m.Questions) != 1 {
				continue
			}
			m.Header.Response = true
			m.Header.RecursionAvailable = true
			name := m.Questions[0].Name
			if m.Questions[0].Type == dnsmessage.TypeA {
				m.Answers = []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.AResource{A: [4]byte{1, 2, 3, 4}}}}
			}
			out, e := m.Pack()
			if e == nil {
				_, _ = c.WriteToUDP(out, addr)
			}
		}
	}()
	addr := c.LocalAddr().String()
	if got := resolveAMServer("slsa1.supaghost.cc", []string{addr}); got != "1.2.3.4" {
		t.Fatalf("got %q", got)
	}
	before := requests.Load()
	for _, bad := range []string{"slsa0.supaghost.cc", "slsa51.supaghost.cc", "slsa01.supaghost.cc", "slsa1.evil.test"} {
		if got := resolveAMServer(bad, []string{addr}); got != "" {
			t.Fatalf("accepted %q: %q", bad, got)
		}
	}
	if requests.Load() != before {
		t.Fatal("invalid zones should not cause lookups")
	}
}

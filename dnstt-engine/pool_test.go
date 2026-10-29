package main

import (
	"context"
	"io"
	"net"
	"testing"
	"time"
)

func poolEndpoint(t *testing.T, marker byte) string {
	t.Helper()
	ln, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, e := ln.Accept()
			if e != nil {
				return
			}
			go func() { defer c.Close(); _, _ = c.Write([]byte{marker}); _, _ = io.Copy(io.Discard, c) }()
		}
	}()
	return ln.Addr().String()
}
func TestPoolDistributesSeparateConnections(t *testing.T) {
	p := newTunnelPool()
	p.set(0, poolEndpoint(t, 'A'))
	p.set(1, poolEndpoint(t, 'B'))
	ln, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go p.serve(ctx, ln)
	seen := map[byte]int{}
	for i := 0; i < 6; i++ {
		c, e := net.Dial("tcp", ln.Addr().String())
		if e != nil {
			t.Fatal(e)
		}
		c.SetDeadline(time.Now().Add(time.Second))
		b := make([]byte, 1)
		_, e = io.ReadFull(c, b)
		c.Close()
		if e != nil {
			t.Fatal(e)
		}
		seen[b[0]]++
	}
	if seen['A'] != 3 || seen['B'] != 3 {
		t.Fatalf("unexpected distribution: %v", seen)
	}
	p.set(0, "")
	p.set(1, "")
	if _, ok := p.choose(); ok {
		t.Fatal("selected stopped server")
	}
}

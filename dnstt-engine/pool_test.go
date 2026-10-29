package main

import (
	"context"
	"io"
	"net"
	"testing"
	"time"
)

func poolEndpoint(t *testing.T, marker byte, user, pass string) string {
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
			go func() {
				defer c.Close()
				if _, e := acceptLocalSocks(c, user, pass); e != nil {
					return
				}
				if _, e := c.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 80}); e != nil {
					return
				}
				_, _ = c.Write([]byte{marker})
				_, _ = io.Copy(io.Discard, c)
			}()
		}
	}()
	return ln.Addr().String()
}
func startTestPool(t *testing.T, p *tunnelPool) string {
	t.Helper()
	ln, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go p.serve(ctx, ln)
	return ln.Addr().String()
}
func TestPoolDistributesSeparateConnections(t *testing.T) {
	p := newTunnelPool()
	p.set(0, poolEndpoint(t, 'A', "", ""))
	p.set(1, poolEndpoint(t, 'B', "", ""))
	addr := startTestPool(t, p)
	seen := map[byte]int{}
	for i := 0; i < 6; i++ {
		c, e := connectLocal(t, addr, "", "")
		if e != nil {
			t.Fatal(e)
		}
		_ = c.SetDeadline(time.Now().Add(time.Second))
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
func TestPoolRetriesOtherServerBeforeConnectReply(t *testing.T) {
	dead, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	deadAddr := dead.Addr().String()
	dead.Close()
	p := newTunnelPool()
	p.localUser = "local"
	p.localPass = "pw"
	p.set(0, deadAddr)
	p.set(1, poolEndpoint(t, 'B', "local", "pw"))
	addr := startTestPool(t, p)
	c, e := connectLocal(t, addr, "local", "pw")
	if e != nil {
		t.Fatal("fallback did not reach second server:", e)
	}
	defer c.Close()
	b := make([]byte, 1)
	if _, e = io.ReadFull(c, b); e != nil || b[0] != 'B' {
		t.Fatalf("wrong server or reply: %q %v", b, e)
	}
}
func TestPoolRejectsBadLocalCredentials(t *testing.T) {
	p := newTunnelPool()
	p.localUser = "local"
	p.localPass = "pw"
	p.set(0, poolEndpoint(t, 'A', "local", "pw"))
	addr := startTestPool(t, p)
	if c, e := connectLocal(t, addr, "local", "wrong"); e == nil {
		c.Close()
		t.Fatal("accepted wrong credentials")
	}
}

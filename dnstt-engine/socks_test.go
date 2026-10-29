package main

import (
	"context"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

func fakeSocks(t *testing.T, acceptAuth bool) (string, *sync.WaitGroup) {
	t.Helper()
	ln, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { ln.Close() })
	var wg sync.WaitGroup
	go func() {
		for {
			c, e := ln.Accept()
			if e != nil {
				return
			}
			wg.Add(1)
			go func() {
				defer wg.Done()
				defer c.Close()
				c.SetDeadline(time.Now().Add(2 * time.Second))
				g, e := readBytes(c, 3)
				if e != nil || !equal(g, []byte{5, 1, 2}) {
					return
				}
				c.Write([]byte{5, 2})
				hdr, e := readBytes(c, 2)
				if e != nil {
					return
				}
				u, e := readBytes(c, int(hdr[1]))
				if e != nil {
					return
				}
				plen, e := readBytes(c, 1)
				if e != nil {
					return
				}
				p, e := readBytes(c, int(plen[0]))
				if e != nil {
					return
				}
				if !acceptAuth || string(u) != "remote" || string(p) != "secret" {
					c.Write([]byte{1, 1})
					return
				}
				c.Write([]byte{1, 0})
				_, e = readSocksRequest(c)
				if e != nil {
					return
				}
				for _, b := range []byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 80} {
					if _, e = c.Write([]byte{b}); e != nil {
						return
					}
				}
				io.Copy(c, c)
			}()
		}
	}()
	return ln.Addr().String(), &wg
}
func connectLocal(t *testing.T, addr, user, pass string) (net.Conn, error) {
	t.Helper()
	c, e := net.DialTimeout("tcp", addr, time.Second)
	if e != nil {
		return nil, e
	}
	c.SetDeadline(time.Now().Add(4 * time.Second))
	method := byte(0)
	if user != "" {
		method = 2
	}
	c.Write([]byte{5, 1, method})
	r, e := readBytes(c, 2)
	if e != nil || r[1] != method {
		c.Close()
		return nil, io.ErrUnexpectedEOF
	}
	if method == 2 {
		msg := append([]byte{1, byte(len(user))}, []byte(user)...)
		msg = append(msg, byte(len(pass)))
		msg = append(msg, []byte(pass)...)
		c.Write(msg)
		r, e = readBytes(c, 2)
		if e != nil || r[1] != 0 {
			c.Close()
			return nil, io.ErrUnexpectedEOF
		}
	}
	c.Write([]byte{5, 1, 0, 3, 11, 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm', 0, 80})
	r, e = readSocksReply(c)
	if e != nil || r[1] != 0 {
		c.Close()
		return nil, io.ErrUnexpectedEOF
	}
	return c, nil
}
func relayForTest(t *testing.T, upstream string, lu, lp string) string {
	t.Helper()
	ln, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	s := newSocksRelay(upstream, "remote", "secret", lu, lp)
	go s.serve(ctx, ln)
	return ln.Addr().String()
}
func TestSocksRelayAuthenticationAndFragmentedReply(t *testing.T) {
	up, _ := fakeSocks(t, true)
	addr := relayForTest(t, up, "local", "pw")
	c, e := connectLocal(t, addr, "local", "pw")
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	c.Write([]byte("hello"))
	b := make([]byte, 5)
	if _, e = io.ReadFull(c, b); e != nil || string(b) != "hello" {
		t.Fatalf("echo=%q error=%v", b, e)
	}
}
func TestSocksRejectsWrongLocalAndRemoteAuth(t *testing.T) {
	up, _ := fakeSocks(t, true)
	addr := relayForTest(t, up, "local", "pw")
	if c, e := connectLocal(t, addr, "local", "wrong"); e == nil {
		c.Close()
		t.Fatal("wrong local auth accepted")
	}
	up2, _ := fakeSocks(t, false)
	addr2 := relayForTest(t, up2, "", "")
	if c, e := connectLocal(t, addr2, "", ""); e == nil {
		c.Close()
		t.Fatal("wrong remote auth accepted")
	}
}
func TestExpandManyResolvers(t *testing.T) {
	result, e := expandResolvers("10.2.0.0/20\n1.1.1.1\n1.1.1.1:53", 5000)
	if e != nil || len(result) != 4097 {
		t.Fatalf("count=%d err=%v", len(result), e)
	}
	if _, e = expandResolvers("10.0.0.0/8", 65536); e == nil {
		t.Fatal("oversize CIDR accepted")
	}
	if _, e = expandResolvers("10.2.0.0/20", 10); e == nil {
		t.Fatal("limit ignored")
	}
	if _, e = expandResolvers("not-an-ip", 10); e == nil {
		t.Fatal("hostname accepted")
	}
}
func TestRejectBadVerificationSettings(t *testing.T) {
	c := engineConfig{Domain: "t.test", PublicKey: "key", ClientBinary: "/bin/true", Username: "u", ListenHost: "127.0.0.1", ListenPort: 1000, VerifyURL: "http://example.com"}
	if e := c.validate(); e == nil || !strings.Contains(e.Error(), "HTTPS") {
		t.Fatal("insecure verification accepted", e)
	}
	c.VerifyURL = "https://example.com"
	c.ListenHost = "0.0.0.0"
	if e := c.validate(); e == nil {
		t.Fatal("non-loopback accepted")
	}
}

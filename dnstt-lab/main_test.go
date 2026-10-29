package main

import (
	"context"
	"encoding/binary"
	"net"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func query(t *testing.T, name string) []byte {
	t.Helper()
	n, err := dnsmessage.NewName(name)
	if err != nil {
		t.Fatal(err)
	}
	msg := dnsmessage.Message{Header: dnsmessage.Header{ID: 42, RecursionDesired: true}, Questions: []dnsmessage.Question{{Name: n, Type: dnsmessage.TypeTXT, Class: dnsmessage.ClassINET}}}
	data, err := msg.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return data
}
func fake(t *testing.T, respond bool, corrupt bool) string {
	t.Helper()
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	go func() {
		b := make([]byte, 4096)
		for {
			n, addr, e := c.ReadFromUDP(b)
			if e != nil {
				return
			}
			if !respond {
				continue
			}
			out := append([]byte(nil), b[:n]...)
			out[2] |= 0x80
			if corrupt {
				binary.BigEndian.PutUint16(out, 43)
			}
			c.WriteToUDP(out, addr)
		}
	}()
	return c.LocalAddr().String()
}
func TestRoundRobinAndRestrictedZone(t *testing.T) {
	a, b := fake(t, true, false), fake(t, true, false)
	r, err := newRelay([]string{a, b}, "test.invalid", 100*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 4; i++ {
		if _, err = r.exchange(query(t, "a.test.invalid.")); err != nil {
			t.Fatal(err)
		}
	}
	if r.successes[a] != 2 || r.successes[b] != 2 {
		t.Fatalf("unbalanced results: %v", r.successes)
	}
	if _, err = r.exchange(query(t, "outside.invalid.")); err == nil {
		t.Fatal("outside zone accepted")
	}
}
func TestTimeoutFailover(t *testing.T) {
	a, b := fake(t, false, false), fake(t, true, false)
	r, err := newRelay([]string{a, b}, "test.invalid", 40*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := r.exchange(query(t, "test.invalid.")); err != nil {
		t.Fatal(err)
	}
	if r.sends[a] != 1 || r.successes[b] != 1 {
		t.Fatalf("no failover: sends %v success %v", r.sends, r.successes)
	}
}
func TestRejectBadTransactionID(t *testing.T) {
	a := fake(t, true, true)
	r, _ := newRelay([]string{a}, "test.invalid", 40*time.Millisecond)
	if _, err := r.exchange(query(t, "test.invalid.")); err == nil {
		t.Fatal("wrong id accepted")
	}
}
func TestServerLoop(t *testing.T) {
	a := fake(t, true, false)
	r, _ := newRelay([]string{a}, "test.invalid", time.Second)
	conn, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go r.serve(ctx, conn)
	c, err := net.Dial("udp", conn.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(time.Second))
	c.Write(query(t, "abc.test.invalid."))
	b := make([]byte, 4096)
	if _, err = c.Read(b); err != nil {
		t.Fatal(err)
	}
}

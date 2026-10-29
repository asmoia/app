package main

import (
	"context"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

func delayedResolver(t *testing.T, delay time.Duration, authoritative bool) string {
	t.Helper()
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	go func() {
		b := make([]byte, 4096)
		for {
			n, peer, e := c.ReadFromUDP(b)
			if e != nil {
				return
			}
			data := append([]byte(nil), b[:n]...)
			go func() {
				time.Sleep(delay)
				data[2] |= 0x80
				if authoritative {
					data[2] |= 0x04
				}
				binary.BigEndian.PutUint16(data[:2], 42)
				_, _ = c.WriteToUDP(data, peer)
			}()
		}
	}()
	return c.LocalAddr().String()
}

func TestHedgingDeliversBothDistinctValidReplies(t *testing.T) {
	slow := delayedResolver(t, 850*time.Millisecond, true)
	fast := delayedResolver(t, 0, false)
	r, e := newRelay([]string{slow, fast}, "test.invalid", 1200*time.Millisecond)
	if e != nil {
		t.Fatal(e)
	}
	var replies [][]byte
	var firstAt time.Duration
	start := time.Now()
	e = r.exchangeAll(query(t, "x.test.invalid."), func(p []byte) {
		if len(replies) == 0 {
			firstAt = time.Since(start)
		}
		replies = append(replies, p)
	})
	if e != nil {
		t.Fatal(e)
	}
	if firstAt < 600*time.Millisecond || firstAt > 850*time.Millisecond {
		t.Fatalf("first response not delivered promptly: %v", firstAt)
	}
	if len(replies) != 2 || replies[0][2]&0x04 != 0 || replies[1][2]&0x04 == 0 {
		t.Fatalf("both valid replies must be delivered: %d", len(replies))
	}
}
func TestFastPrimaryDoesNotDuplicate(t *testing.T) {
	a := delayedResolver(t, 0, false)
	b := delayedResolver(t, 0, false)
	r, _ := newRelay([]string{a, b}, "test.invalid", time.Second)
	if _, e := r.exchange(query(t, "x.test.invalid.")); e != nil {
		t.Fatal(e)
	}
	r.mu.Lock()
	sent := r.sends[b]
	r.mu.Unlock()
	if sent != 0 {
		t.Fatal("unnecessary duplicate sent to secondary")
	}
}

func TestBridgeForwardsLateReplyOnLocalUDP(t *testing.T) {
	a := delayedResolver(t, 850*time.Millisecond, true)
	b := delayedResolver(t, 0, false)
	r, err := newRelay([]string{a, b}, "test.invalid", 1200*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	server, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.serve(ctx, server) }()
	t.Cleanup(func() { cancel(); <-done })
	client, err := net.DialUDP("udp", nil, server.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err = client.Write(query(t, "x.test.invalid.")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4096)
	for i := 0; i < 2; i++ {
		n, err := client.Read(buf)
		if err != nil {
			t.Fatal(err)
		}
		if n < 3 || (buf[2]&0x04 != 0) != (i == 1) {
			t.Fatalf("wrong reply %d", i)
		}
	}
}

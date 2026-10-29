package main

import (
	"encoding/binary"
	"net"
	"testing"
	"time"
)

func delayedResolver(t *testing.T, delay time.Duration) string {
	t.Helper()
	c, e := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { c.Close() })
	go func() {
		b := make([]byte, 4096)
		for {
			n, a, e := c.ReadFromUDP(b)
			if e != nil {
				return
			}
			data := append([]byte(nil), b[:n]...)
			go func() {
				time.Sleep(delay)
				data[2] |= 0x80
				binary.BigEndian.PutUint16(data[:2], 42)
				_, _ = c.WriteToUDP(data, a)
			}()
		}
	}()
	return c.LocalAddr().String()
}
func TestHedgingUsesFastAlternateWithoutPenalizingCancelledPath(t *testing.T) {
	slow := delayedResolver(t, 2*time.Second)
	fast := delayedResolver(t, 0)
	r, e := newRelay([]string{slow, fast}, "test.invalid", 1500*time.Millisecond)
	if e != nil {
		t.Fatal(e)
	}
	started := time.Now()
	if _, e = r.exchange(query(t, "x.test.invalid.")); e != nil {
		t.Fatal(e)
	}
	elapsed := time.Since(started)
	if elapsed < 250*time.Millisecond || elapsed > 900*time.Millisecond {
		t.Fatalf("hedge did not run after bounded delay: %v", elapsed)
	}
	time.Sleep(30 * time.Millisecond)
	r.mu.Lock()
	failure := r.failures[slow]
	sent := r.sends[fast]
	r.mu.Unlock()
	if failure != 0 || sent != 1 {
		t.Fatalf("cancelled loser was penalized: failure=%d backup_sends=%d", failure, sent)
	}
}
func TestFastPrimaryDoesNotDuplicate(t *testing.T) {
	a := delayedResolver(t, 0)
	b := delayedResolver(t, 0)
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

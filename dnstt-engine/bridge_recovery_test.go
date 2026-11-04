package main

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"syscall"
	"testing"
	"time"
)

type testBridgeConn struct {
	failures int
	readErr  error
	incoming chan []byte
	replies  chan []byte
	closed   chan struct{}
	once     sync.Once
}

func newTestBridgeConn(failures int, err error) *testBridgeConn {
	return &testBridgeConn{failures: failures, readErr: err, incoming: make(chan []byte, 1), replies: make(chan []byte, 1), closed: make(chan struct{})}
}
func (c *testBridgeConn) ReadFromUDP(buf []byte) (int, *net.UDPAddr, error) {
	if c.failures > 0 {
		c.failures--
		return 0, nil, &net.OpError{Op: "read", Net: "udp", Err: c.readErr}
	}
	select {
	case packet := <-c.incoming:
		return copy(buf, packet), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 10000}, nil
	case <-c.closed:
		return 0, nil, net.ErrClosed
	}
}
func (c *testBridgeConn) WriteToUDP(p []byte, _ *net.UDPAddr) (int, error) {
	c.replies <- append([]byte(nil), p...)
	return len(p), nil
}
func (c *testBridgeConn) Close() error { c.once.Do(func() { close(c.closed) }); return nil }

func TestBridgeRecoversAfterTransientReadErrors(t *testing.T) {
	r, _ := newRelay([]string{fake(t, true, false)}, "test.invalid", time.Second)
	conn := newTestBridgeConn(2, syscall.ECONNABORTED)
	conn.incoming <- query(t, "x.test.invalid.")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.serve(ctx, conn) }()
	select {
	case <-conn.replies:
	case err := <-done:
		cancel()
		t.Fatalf("bridge exited on recoverable errors: %v", err)
	case <-time.After(3 * time.Second):
		cancel()
		t.Fatal("bridge did not recover")
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("bridge did not stop")
	}
}
func TestBridgeRejectsPersistentOrFatalReadErrors(t *testing.T) {
	r, _ := newRelay([]string{fake(t, true, false)}, "test.invalid", time.Second)
	for _, tc := range []struct {
		count   int
		err     error
		timeout time.Duration
	}{{1, io.EOF, time.Second}, {20, syscall.ECONNABORTED, 6 * time.Second}} {
		ctx, cancel := context.WithCancel(context.Background())
		c := newTestBridgeConn(tc.count, tc.err)
		done := make(chan error, 1)
		go func() { done <- r.serve(ctx, c) }()
		select {
		case err := <-done:
			if !errors.Is(err, tc.err) {
				t.Fatalf("wrong error: %v", err)
			}
		case <-time.After(tc.timeout):
			t.Fatal("bridge retry limit did not stop")
		}
		cancel()
	}
}

type flakyRealUDP struct {
	*net.UDPConn
	failCount int
}

func (c *flakyRealUDP) ReadFromUDP(p []byte) (int, *net.UDPAddr, error) {
	if c.failCount > 0 {
		c.failCount--
		return 0, nil, &net.OpError{Op: "read", Net: "udp", Err: syscall.ECONNABORTED}
	}
	return c.UDPConn.ReadFromUDP(p)
}
func TestBridgeRecoversOnRealUDPAfterInjectedAbort(t *testing.T) {
	r, err := newRelay([]string{fake(t, true, false)}, "test.invalid", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	actual, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.serve(ctx, &flakyRealUDP{UDPConn: actual, failCount: 2}) }()
	client, err := net.DialUDP("udp", nil, actual.LocalAddr().(*net.UDPAddr))
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err = client.Write(query(t, "x.test.invalid.")); err != nil {
		cancel()
		t.Fatal(err)
	}
	buf := make([]byte, 4096)
	if _, err = client.Read(buf); err != nil {
		cancel()
		t.Fatal(err)
	}
	cancel()
	select {
	case err = <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("bridge did not stop")
	}
}

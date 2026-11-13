package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"testing"
	"time"
)

func TestSuperDNSModeValidation(t *testing.T) {
	cfg := engineConfig{Domain: "slsa1.supaghost.cc", PublicKey: "prince", ClientBinary: "/app/client", ListenHost: "127.0.0.1", ListenPort: 12345, UpstreamMode: "shadowsocks-none"}
	if err := cfg.validate(); err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(cfg)
	var decoded engineConfig
	if err := json.Unmarshal(encoded, &decoded); err != nil || decoded.UpstreamMode != cfg.UpstreamMode {
		t.Fatalf("mode lost: %s %v", encoded, err)
	}
	cfg.UpstreamMode = "socks5"
	if cfg.validate() == nil {
		t.Fatal("BD mode must still require credentials")
	}
	cfg.UpstreamMode = "bogus"
	if cfg.validate() == nil {
		t.Fatal("unknown upstream mode accepted")
	}
}

func TestSuperDNSSocksBridgeUsesRawDestination(t *testing.T) {
	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Close()
	remoteDone := make(chan error, 1)
	go func() {
		conn, e := upstream.Accept()
		if e != nil {
			remoteDone <- e
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		header := make([]byte, 1+1+11+2)
		if _, e = io.ReadFull(conn, header); e != nil {
			remoteDone <- e
			return
		}
		expected := append([]byte{3, 11}, []byte("example.com")...)
		expected = append(expected, 1, 187)
		if !bytes.Equal(header, expected) {
			remoteDone <- &badHeaderError{}
			return
		}
		b := make([]byte, 4)
		if _, e = io.ReadFull(conn, b); e == nil && string(b) == "ping" {
			_, e = conn.Write([]byte("pong"))
		}
		remoteDone <- e
	}()
	relay := newSocksRelay(upstream.Addr().String(), "", "", "", "")
	relay.upstreamMode = "shadowsocks-none"
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- relay.serve(ctx, listener) }()
	conn, err := net.DialTimeout("tcp", listener.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err = conn.Write([]byte{5, 1, 0}); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 2)
	if _, err = io.ReadFull(conn, reply); err != nil || !bytes.Equal(reply, []byte{5, 0}) {
		t.Fatalf("greeting %v %v", reply, err)
	}
	req := append([]byte{5, 1, 0, 3, 11}, []byte("example.com")...)
	req = append(req, 1, 187)
	if _, err = conn.Write(req); err != nil {
		t.Fatal(err)
	}
	ack := make([]byte, 10)
	if _, err = io.ReadFull(conn, ack); err != nil || ack[1] != 0 {
		t.Fatalf("reply %v %v", ack, err)
	}
	if _, err = conn.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	result := make([]byte, 4)
	if _, err = io.ReadFull(conn, result); err != nil || string(result) != "pong" {
		t.Fatalf("payload %q %v", result, err)
	}
	if err = <-remoteDone; err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("shutdown stalled")
	}
}

type badHeaderError struct{}

func (*badHeaderError) Error() string { return "unexpected shadowsocks destination header" }

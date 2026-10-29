package main

import (
	"context"
	"crypto/subtle"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

// All connections to the remote endpoint pass through the dnstt local TCP
// forwarder. The far end is a SOCKS5 server requiring RFC 1929 credentials.
type socksRelay struct {
	upstream               string
	remoteUser, remotePass string
	localUser, localPass   string
	maxClients             chan struct{}
	mu                     sync.Mutex
	open                   map[net.Conn]struct{}
	wg                     sync.WaitGroup
}

func newSocksRelay(upstream, user, pass, localUser, localPass string) *socksRelay {
	return &socksRelay{upstream: upstream, remoteUser: user, remotePass: pass, localUser: localUser, localPass: localPass, maxClients: make(chan struct{}, 256), open: map[net.Conn]struct{}{}}
}
func (s *socksRelay) track(c net.Conn, add bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if add {
		s.open[c] = struct{}{}
	} else {
		delete(s.open, c)
	}
}
func (s *socksRelay) shutdown() {
	s.mu.Lock()
	for c := range s.open {
		_ = c.Close()
	}
	s.mu.Unlock()
	s.wg.Wait()
}
func (s *socksRelay) serve(ctx context.Context, ln net.Listener) error {
	done := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = ln.Close()
		case <-done:
		}
	}()
	defer close(done)
	defer s.shutdown()
	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		select {
		case s.maxClients <- struct{}{}:
		default:
			_ = c.Close()
			continue
		}
		s.track(c, true)
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			defer func() { <-s.maxClients; s.track(c, false); _ = c.Close() }()
			_ = s.handle(c)
		}()
	}
}
func readBytes(c net.Conn, n int) ([]byte, error) {
	buf := make([]byte, n)
	_, err := io.ReadFull(c, buf)
	return buf, err
}
func equal(a, b []byte) bool { return len(a) == len(b) && subtle.ConstantTimeCompare(a, b) == 1 }
func (s *socksRelay) handle(c net.Conn) error {
	_ = c.SetDeadline(time.Now().Add(45 * time.Second))
	h, err := readBytes(c, 2)
	if err != nil {
		return err
	}
	if h[0] != 5 || h[1] == 0 {
		return errors.New("invalid SOCKS greeting")
	}
	methods, err := readBytes(c, int(h[1]))
	if err != nil {
		return err
	}
	method := byte(0)
	if s.localUser != "" {
		method = 2
	}
	present := false
	for _, m := range methods {
		if m == method {
			present = true
		}
	}
	if !present {
		_, _ = c.Write([]byte{5, 255})
		return errors.New("no supported local auth")
	}
	if _, err = c.Write([]byte{5, method}); err != nil {
		return err
	}
	if method == 2 {
		hdr, e := readBytes(c, 2)
		if e != nil {
			return e
		}
		if hdr[0] != 1 || hdr[1] == 0 {
			return errors.New("bad local auth")
		}
		u, e := readBytes(c, int(hdr[1]))
		if e != nil {
			return e
		}
		plen, e := readBytes(c, 1)
		if e != nil {
			return e
		}
		p, e := readBytes(c, int(plen[0]))
		if e != nil {
			return e
		}
		ok := equal(u, []byte(s.localUser)) && equal(p, []byte(s.localPass))
		reply := byte(1)
		if ok {
			reply = 0
		}
		_, _ = c.Write([]byte{1, reply})
		if !ok {
			return errors.New("local auth refused")
		}
	}
	request, err := readSocksRequest(c)
	if err != nil {
		return err
	}
	upstream, err := net.DialTimeout("tcp", s.upstream, 15*time.Second)
	if err != nil {
		_, _ = c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return err
	}
	s.track(upstream, true)
	defer func() { s.track(upstream, false); _ = upstream.Close() }()
	_ = upstream.SetDeadline(time.Now().Add(45 * time.Second))
	if err = upstreamAuth(upstream, s.remoteUser, s.remotePass); err != nil {
		_, _ = c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return err
	}
	if _, err = upstream.Write(request); err != nil {
		return err
	}
	reply, err := readSocksReply(upstream)
	if err != nil {
		return err
	}
	if _, err = c.Write(reply); err != nil {
		return err
	}
	if reply[1] != 0 {
		return fmt.Errorf("upstream connect failed: %d", reply[1])
	}
	_ = c.SetDeadline(time.Time{})
	_ = upstream.SetDeadline(time.Time{})
	done := make(chan struct{}, 2)
	pipe := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		if tcp, ok := dst.(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
		done <- struct{}{}
	}
	go pipe(upstream, c)
	go pipe(c, upstream)
	<-done
	<-done
	return nil
}
func readSocksRequest(c net.Conn) ([]byte, error) {
	h, e := readBytes(c, 4)
	if e != nil {
		return nil, e
	}
	if h[0] != 5 || h[1] != 1 || h[2] != 0 {
		return nil, errors.New("only SOCKS5 CONNECT is supported")
	}
	return readAddress(c, h)
}
func readSocksReply(c net.Conn) ([]byte, error) {
	h, e := readBytes(c, 4)
	if e != nil {
		return nil, e
	}
	if h[0] != 5 || h[2] != 0 {
		return nil, errors.New("bad upstream reply")
	}
	return readAddress(c, h)
}
func readAddress(c net.Conn, h []byte) ([]byte, error) {
	n := 0
	switch h[3] {
	case 1:
		n = 4
	case 4:
		n = 16
	case 3:
		b, e := readBytes(c, 1)
		if e != nil {
			return nil, e
		}
		if b[0] == 0 {
			return nil, errors.New("empty domain")
		}
		h = append(h, b[0])
		n = int(b[0])
	default:
		return nil, errors.New("invalid address type")
	}
	tail, e := readBytes(c, n+2)
	if e != nil {
		return nil, e
	}
	return append(h, tail...), nil
}
func upstreamAuth(c net.Conn, user, pass string) error {
	if len(user) == 0 || len(user) > 255 || len(pass) > 255 {
		return errors.New("invalid upstream credentials")
	}
	if _, err := c.Write([]byte{5, 1, 2}); err != nil {
		return err
	}
	m, err := readBytes(c, 2)
	if err != nil {
		return err
	}
	if !equal(m, []byte{5, 2}) {
		return errors.New("upstream rejected password authentication")
	}
	buf := append([]byte{1, byte(len(user))}, []byte(user)...)
	buf = append(buf, byte(len(pass)))
	buf = append(buf, []byte(pass)...)
	if _, err = c.Write(buf); err != nil {
		return err
	}
	reply, err := readBytes(c, 2)
	if err != nil {
		return err
	}
	if !equal(reply, []byte{1, 0}) {
		return errors.New("upstream authentication failed")
	}
	return nil
}

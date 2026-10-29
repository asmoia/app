package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	storm "stormdns-go/internal/client"
)

// discover uses the actual, zone-restricted DNSTT question produced by the
// client, not an unrelated example.com query. Parallelism and time are bounded.
// Responses only establish a candidate DNS path; authenticated HTTPS remains
// mandatory before reporting that the tunnel is connected.
func (r *relay) discover(packet []byte) ([]byte, error) {
	r.scanMu.Lock()
	if r.bal.ValidCount() > 0 {
		r.scanMu.Unlock()
		return nil, nil
	}
	if r.scanRunning {
		ready, done := r.scanReady, r.scanDone
		r.scanMu.Unlock()
		select {
		case <-ready:
			return nil, nil
		case <-done:
			if r.bal.ValidCount() > 0 {
				return nil, nil
			}
			return nil, errors.New("no resolver responded to a tunnel query")
		}
	}
	r.scanRunning = true
	r.scanDone = make(chan struct{})
	r.scanReady = make(chan struct{})
	done, ready := r.scanDone, r.scanReady
	first := make(chan []byte, 1)
	// Cap each sweep; next attempt resumes farther into the original list.
	count := min(512, len(r.candidates))
	start := r.scanOffset
	r.scanMu.Unlock()
	go r.scanTunnelPacket(bytes.Clone(packet), start, count, first, ready, done)
	select {
	case reply := <-first:
		return reply, nil
	case <-done:
		select {
		case reply := <-first:
			return reply, nil
		default:
		}
		if r.bal.ValidCount() > 0 {
			return nil, nil
		}
		return nil, errors.New("no resolver responded to a tunnel query")
	}
}
func (r *relay) scanTunnelPacket(packet []byte, start, count int, first chan []byte, ready, done chan struct{}) {
	priority := min(8, len(r.candidates))
	// Every sweep retries the preferred network/public resolvers. Only advance
	// the remaining-list cursor for jobs actually sent, not the full cap when
	// a fast successful scan is cancelled early.
	if start < priority || start >= len(r.candidates) {
		start = priority
	}
	nextOffset := start
	defer func() {
		r.scanMu.Lock()
		r.scanOffset = nextOffset
		r.scanRunning = false
		close(done)
		r.scanMu.Unlock()
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	jobs := make(chan storm.Connection)
	var wg sync.WaitGroup
	var readyOnce sync.Once
	workers := min(32, count)
	wg.Add(workers)
	for i := 0; i < workers; i++ {
		go func() {
			defer wg.Done()
			for candidate := range jobs {
				started := time.Now()
				reply, err := r.tunnelProbe(ctx, candidate.Key, packet)
				if err != nil {
					continue
				}
				r.mu.Lock()
				if len(r.activePaths) >= 4 {
					r.mu.Unlock()
					cancel()
					return
				}
				if _, already := r.activePaths[candidate.Key]; !already {
					candidate.IsValid = true
					r.activePaths[candidate.Key] = candidate
					delete(r.failures, candidate.Key)
					delete(r.cooldownUntil, candidate.Key)
					r.updateActiveLocked()
					fmt.Printf("SLOWDNS_PATH valid=%s dns_ms=%d\n", candidate.Key, time.Since(started).Milliseconds())
				}
				n := len(r.activePaths)
				r.mu.Unlock()
				readyOnce.Do(func() { first <- reply; close(ready) })
				if n >= 4 {
					cancel()
					return
				}
			}
		}()
	}
	for i := 0; i < count; i++ {
		idx := i
		if i >= priority {
			idx = priority + (start-priority+i-priority)%(len(r.candidates)-priority)
		}
		select {
		case jobs <- r.candidates[idx]:
			if i >= priority {
				nextOffset = priority + (idx-priority+1)%(len(r.candidates)-priority)
			}
		case <-ctx.Done():
			close(jobs)
			wg.Wait()
			return
		}
	}
	close(jobs)
	wg.Wait()
	if r.bal.ValidCount() == 0 {
		fmt.Printf("SLOWDNS_PATH no_tunnel_response tested_up_to=%d\n", count)
	}
}
func (r *relay) updateActiveLocked() {
	conns := make([]*storm.Connection, 0, len(r.activePaths))
	// Preserve configured ordering, so Storm's round-robin is predictable.
	for _, candidate := range r.candidates {
		if c, ok := r.activePaths[candidate.Key]; ok {
			copy := c
			conns = append(conns, &copy)
		}
	}
	r.bal.SetConnections(conns)
}
func (r *relay) tunnelProbe(ctx context.Context, address string, packet []byte) ([]byte, error) {
	req, q, err := question(packet, false)
	if err != nil {
		return nil, err
	}
	d := net.Dialer{Timeout: r.timeout}
	conn, err := d.DialContext(ctx, "udp", address)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	deadline := time.Now().Add(r.timeout)
	if end, ok := ctx.Deadline(); ok && end.Before(deadline) {
		deadline = end
	}
	_ = conn.SetDeadline(deadline)
	if _, err = conn.Write(packet); err != nil {
		return nil, err
	}
	r.dnsUpBytes.Add(uint64(len(packet)))
	r.dnsUpPackets.Add(1)
	buf := make([]byte, maxPacket)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	r.dnsDownBytes.Add(uint64(n))
	r.dnsDownPackets.Add(1)
	reply := buf[:n]
	hdr, rq, err := question(reply, true)
	var parsed dnsmessage.Message
	if err != nil || parsed.Unpack(reply) != nil || hdr.ID != req.ID || hdr.RCode != dnsmessage.RCodeSuccess || hdr.Truncated || !strings.EqualFold(rq.Name.String(), q.Name.String()) || rq.Type != q.Type || rq.Class != q.Class {
		return nil, errors.New("invalid tunnel DNS response")
	}
	hasAnswer := false
	for _, answer := range parsed.Answers {
		if answer.Header.Type == q.Type && strings.EqualFold(answer.Header.Name.String(), q.Name.String()) {
			hasAnswer = true
			break
		}
	}
	if !hasAnswer {
		return nil, errors.New("DNS response has no tunnel answer")
	}
	return bytes.Clone(reply), nil
}

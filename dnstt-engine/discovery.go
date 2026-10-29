package main

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"net"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// DNS preflight is only a ranking hint; a DNS response is NOT proof that the
// resolver can carry this tunnel. The actual authenticated transfer probe is
// mandatory before reporting connection readiness.
func preflightResolvers(parent context.Context, candidates []string, limit time.Duration) []string {
	if len(candidates) <= 8 {
		return candidates
	}
	ctx, cancel := context.WithTimeout(parent, limit)
	defer cancel()
	jobs := make(chan string)
	results := make(chan string, 128)
	var wg sync.WaitGroup
	workers := 64
	if len(candidates) < workers {
		workers = len(candidates)
	}
	wg.Add(workers)
	for i := 0; i < workers; i++ {
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case addr, ok := <-jobs:
					if !ok {
						return
					}
					if probeResolver(ctx, addr) {
						select {
						case results <- addr:
						case <-ctx.Done():
							return
						}
					}
				}
			}
		}()
	}
	go func() {
		defer close(jobs)
		for _, addr := range candidates {
			select {
			case jobs <- addr:
			case <-ctx.Done():
				return
			}
		}
	}()
	finished := make(chan struct{})
	go func() { wg.Wait(); close(finished) }()
	valid := make([]string, 0, 64)
	for {
		select {
		case addr := <-results:
			valid = append(valid, addr)
			if len(valid) >= 64 {
				return retainUnverifiedCandidates(valid, candidates)
			}
		case <-finished:
			// Results may still be buffered when all workers have finished.
			for {
				select {
				case addr := <-results:
					valid = append(valid, addr)
				default:
					if len(valid) == 0 {
						return candidates
					}
					return retainUnverifiedCandidates(valid, candidates)
				}
			}
		case <-ctx.Done():
			if len(valid) == 0 {
				return candidates
			}
			return retainUnverifiedCandidates(valid, candidates)
		}
	}
}

// Keep a small, evenly sampled set of unverified paths: some networks only
// resolve tunnel zones and would fail the example.com ranking query. This is
// not an exhaustive search; use the authenticated scanner for a large list.
func retainUnverifiedCandidates(valid, candidates []string) []string {
	if len(valid) == 0 {
		return candidates
	}
	reserve := min(8, max(1, len(valid)/4))
	if len(valid) > 64-reserve {
		valid = valid[:64-reserve]
	}
	selected := make(map[string]bool, len(valid)+reserve)
	for _, v := range valid {
		selected[v] = true
	}
	out := append([]string(nil), valid...)
	for i := 0; i < reserve && len(candidates) > 0; i++ {
		idx := (2*i + 1) * len(candidates) / (2 * reserve)
		// Move past a selected address, without searching more than one round.
		for step := 0; step < len(candidates); step++ {
			candidate := candidates[(idx+step)%len(candidates)]
			if !selected[candidate] {
				out = append(out, candidate)
				selected[candidate] = true
				break
			}
		}
	}
	return out
}

func probeResolver(ctx context.Context, addr string) bool {
	var id [2]byte
	if _, err := rand.Read(id[:]); err != nil {
		return false
	}
	name, _ := dnsmessage.NewName("example.com.")
	message := dnsmessage.Message{Header: dnsmessage.Header{ID: binary.BigEndian.Uint16(id[:]), RecursionDesired: true}, Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
	packet, err := message.Pack()
	if err != nil {
		return false
	}
	d := net.Dialer{Timeout: 500 * time.Millisecond}
	c, err := d.DialContext(ctx, "udp", addr)
	if err != nil {
		return false
	}
	defer c.Close()
	deadline := time.Now().Add(650 * time.Millisecond)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	_ = c.SetDeadline(deadline)
	if _, err = c.Write(packet); err != nil {
		return false
	}
	buf := make([]byte, 4096)
	n, err := c.Read(buf)
	if err != nil {
		return false
	}
	var m dnsmessage.Message
	if m.Unpack(buf[:n]) != nil {
		return false
	}
	return m.Header.ID == binary.BigEndian.Uint16(id[:]) && m.Header.Response && m.Header.RCode == dnsmessage.RCodeSuccess && len(m.Questions) == 1 && m.Questions[0].Name == name && len(m.Answers) > 0
}

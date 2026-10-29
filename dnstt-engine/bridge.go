// DNS datagram bridge using StormDNS's balancer, without altering DNSTT framing.
package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	storm "stormdns-go/internal/client"
)

const maxPacket = 4096

type relay struct {
	bal           *storm.Balancer
	timeout       time.Duration
	zone          string
	sem           chan struct{}
	mu            sync.Mutex
	sends         map[string]int
	successes     map[string]int
	failures      map[string]int
	cooldownUntil map[string]time.Time
}

func newRelay(upstreams []string, zone string, timeout time.Duration) (*relay, error) {
	if len(upstreams) == 0 {
		return nil, errors.New("no upstreams")
	}
	if zone == "" {
		return nil, errors.New("zone is required")
	}
	parsed, err := dnsmessage.NewName(strings.TrimSuffix(strings.ToLower(zone), ".") + ".")
	if err != nil {
		return nil, fmt.Errorf("invalid zone: %w", err)
	}
	r := &relay{bal: storm.NewBalancer(storm.BalancingRoundRobin), timeout: timeout, zone: parsed.String(), sem: make(chan struct{}, 128), sends: map[string]int{}, successes: map[string]int{}, failures: map[string]int{}, cooldownUntil: map[string]time.Time{}}
	var conns []*storm.Connection
	seen := map[string]bool{}
	for _, address := range upstreams {
		address = strings.TrimSpace(address)
		host, port, err := net.SplitHostPort(address)
		if err != nil || net.ParseIP(host) == nil || port == "" {
			return nil, fmt.Errorf("upstream must be IP:port: %q", address)
		}
		addr, err := net.ResolveUDPAddr("udp", address)
		if err != nil || addr.Port < 1 {
			return nil, fmt.Errorf("invalid upstream %q", address)
		}
		key := addr.String()
		if seen[key] {
			return nil, fmt.Errorf("duplicate upstream %q", key)
		}
		seen[key] = true
		conns = append(conns, &storm.Connection{Key: key, Resolver: host, ResolverPort: addr.Port, IsValid: true})
	}
	r.bal.SetConnections(conns)
	return r, nil
}

func question(packet []byte, wantResponse bool) (dnsmessage.Header, dnsmessage.Question, error) {
	var p dnsmessage.Parser
	h, err := p.Start(packet)
	if err != nil {
		return h, dnsmessage.Question{}, err
	}
	if h.Response != wantResponse || len(packet) < 12 || binary.BigEndian.Uint16(packet[4:6]) != 1 {
		return h, dnsmessage.Question{}, errors.New("unexpected DNS header")
	}
	q, err := p.Question()
	return h, q, err
}

func (r *relay) cooling(key string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return time.Now().Before(r.cooldownUntil[key])
}

func (r *relay) failed(key string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.failures[key]++
	if r.failures[key] >= 3 {
		shift := min(r.failures[key]-3, 5)
		r.cooldownUntil[key] = time.Now().Add(time.Duration(1<<shift) * time.Second)
	}
}

func (r *relay) succeeded(key string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.successes[key]++
	delete(r.failures, key)
	delete(r.cooldownUntil, key)
}

func (r *relay) exchange(packet []byte) ([]byte, error) {
	req, q, err := question(packet, false)
	if err != nil {
		return nil, err
	}
	name := strings.ToLower(q.Name.String())
	zone := r.zone
	if name != zone && !strings.HasSuffix(name, "."+zone) {
		return nil, errors.New("question outside allowed zone")
	}
	// Select one primary per query. GetUniqueConnections(2) advances Storm's
	// round-robin counter by two, which can pin the primary with two paths.
	primary, ok := r.bal.GetBestConnection()
	if !ok {
		return nil, errors.New("no resolver available")
	}
	if r.cooling(primary.Key) {
		if alternative, found := r.bal.GetBestConnectionExcluding(primary.Key); found && !r.cooling(alternative.Key) {
			primary = alternative
		}
	}
	for i := 0; i < 2; i++ {
		c := primary
		if i == 1 {
			// Only advance the balancer for fallback after the primary fails.
			var found bool
			c, found = r.bal.GetBestConnectionExcluding(primary.Key)
			if !found {
				break
			}
		}
		r.mu.Lock()
		r.sends[c.Key]++
		r.mu.Unlock()
		r.bal.ReportSend(c.Key)
		started := time.Now()
		conn, err := net.DialTimeout("udp", c.Key, r.timeout)
		if err != nil {
			r.failed(c.Key)
			continue
		}
		_ = conn.SetDeadline(time.Now().Add(r.timeout))
		_, err = conn.Write(packet)
		response := make([]byte, maxPacket)
		var n int
		if err == nil {
			n, err = conn.Read(response)
		}
		_ = conn.Close()
		if err != nil {
			r.failed(c.Key)
			continue
		}
		response = response[:n]
		h, respQ, err := question(response, true)
		var parsed dnsmessage.Message
		if err != nil || parsed.Unpack(response) != nil || h.ID != req.ID || h.RCode != dnsmessage.RCodeSuccess || h.Truncated || !strings.EqualFold(respQ.Name.String(), q.Name.String()) || respQ.Type != q.Type || respQ.Class != q.Class {
			r.failed(c.Key)
			continue
		}
		r.bal.ReportSuccess(c.Key, time.Since(started))
		r.succeeded(c.Key)
		return bytes.Clone(response), nil
	}
	return nil, errors.New("no valid upstream response")
}

func (r *relay) serve(ctx context.Context, conn *net.UDPConn) error {
	var workers sync.WaitGroup
	defer workers.Wait()
	go func() { <-ctx.Done(); conn.Close() }()
	for {
		data := make([]byte, maxPacket+1)
		n, from, err := conn.ReadFromUDP(data)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		if n > maxPacket || !from.IP.IsLoopback() {
			continue
		}
		select {
		case r.sem <- struct{}{}:
		default:
			continue
		}
		workers.Add(1)
		go func(p []byte, peer *net.UDPAddr) {
			defer workers.Done()
			defer func() { <-r.sem }()
			reply, err := r.exchange(p)
			if err == nil {
				_, _ = conn.WriteToUDP(reply, peer)
			}
		}(data[:n], from)
	}
}

// DNS datagram bridge using StormDNS's balancer, without altering DNSTT framing.
package main

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	storm "stormdns-go/internal/client"
)

const maxPacket = 4096

type relay struct {
	dnsUpBytes     atomic.Uint64
	dnsDownBytes   atomic.Uint64
	dnsUpPackets   atomic.Uint64
	dnsDownPackets atomic.Uint64
	exploreCounter atomic.Uint64
	bal            *storm.Balancer
	timeout        time.Duration
	zone           string
	sem            chan struct{}
	mu             sync.Mutex
	sends          map[string]int
	successes      map[string]int
	failures       map[string]int
	cooldownUntil  map[string]time.Time
	candidates     []storm.Connection
	adaptive       bool
	activePaths    map[string]storm.Connection
	scanMu         sync.Mutex
	scanRunning    bool
	scanDone       chan struct{}
	scanReady      chan struct{}
	scanOffset     int
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
	r := &relay{bal: storm.NewBalancer(storm.BalancingLowestLatency), timeout: timeout, zone: parsed.String(), sem: make(chan struct{}, 128), sends: map[string]int{}, successes: map[string]int{}, failures: map[string]int{}, cooldownUntil: map[string]time.Time{}}
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
	r.adaptive = len(conns) > 8
	r.activePaths = make(map[string]storm.Connection)
	for _, conn := range conns {
		r.candidates = append(r.candidates, *conn)
	}
	if r.adaptive {
		// Large candidate lists are not ready merely because a resolver answered
		// ordinary DNS. Actual DNSTT traffic activates paths on the first query.
		r.bal.SetConnections(nil)
	} else {
		r.bal.SetConnections(conns)
	}
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
		if r.adaptive {
			if _, active := r.activePaths[key]; active {
				delete(r.activePaths, key)
				r.updateActiveLocked()
			}
		}
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
	var first []byte
	err := r.exchangeAll(packet, func(reply []byte) {
		if first == nil {
			first = reply
		}
	})
	return first, err
}

// A repeated DNS query can elicit different tunnel payloads. Deliver each
// valid response to the local DNSTT client instead of discarding the late one.
func (r *relay) exchangeAll(packet []byte, deliver func([]byte)) error {
	req, q, err := question(packet, false)
	if err != nil {
		return err
	}
	name := strings.ToLower(q.Name.String())
	zone := r.zone
	if name != zone && !strings.HasSuffix(name, "."+zone) {
		return errors.New("question outside allowed zone")
	}
	if r.adaptive && r.bal.ValidCount() == 0 {
		first, err := r.discover(packet)
		if err != nil {
			return err
		}
		if first != nil {
			deliver(first)
			return nil
		}
	}
	primary, ok := r.selectDNSPath()
	if !ok {
		return errors.New("no resolver available")
	}
	if r.cooling(primary.Key) {
		if alternative, found := r.bal.GetBestConnectionExcluding(primary.Key); found && !r.cooling(alternative.Key) {
			primary = alternative
		}
	}
	return r.exchangeHedged(packet, req, q, primary, deliver)
}

// Storm prefers the measured low-latency resolver. Reserve one in eight
// queries for alternatives so their measurements do not go permanently stale.
func (r *relay) selectDNSPath() (storm.Connection, bool) {
	primary, ok := r.bal.GetBestConnection()
	if !ok {
		return primary, false
	}
	seq := r.exploreCounter.Add(1)
	if seq%8 == 0 {
		valid := r.bal.GetAllValidConnections()
		alternatives := make([]storm.Connection, 0, len(valid))
		for _, candidate := range valid {
			if candidate.Key != primary.Key && !r.cooling(candidate.Key) {
				alternatives = append(alternatives, candidate)
			}
		}
		if len(alternatives) > 0 {
			primary = alternatives[(seq/8-1)%uint64(len(alternatives))]
		}
	}
	return primary, true
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
			_ = r.exchangeAll(p, func(reply []byte) {
				_, _ = conn.WriteToUDP(reply, peer)
			})
		}(data[:n], from)
	}
}

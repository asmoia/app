// Experimental DNS datagram bridge for testing dnstt with StormDNS's real
// balancer. This does not implement StormDNS framing or claim Android support.
package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	storm "stormdns-go/internal/client"
)

const maxPacket = 4096

type relay struct {
	bal       *storm.Balancer
	timeout   time.Duration
	zone      string
	sem       chan struct{}
	mu        sync.Mutex
	sends     map[string]int
	successes map[string]int
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
	r := &relay{bal: storm.NewBalancer(storm.BalancingRoundRobin), timeout: timeout, zone: parsed.String(), sem: make(chan struct{}, 128), sends: map[string]int{}, successes: map[string]int{}}
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
			continue
		}
		response = response[:n]
		h, respQ, err := question(response, true)
		if err != nil || h.ID != req.ID || h.RCode != dnsmessage.RCodeSuccess || h.Truncated || !strings.EqualFold(respQ.Name.String(), q.Name.String()) || respQ.Type != q.Type || respQ.Class != q.Class {
			continue
		}
		r.bal.ReportSuccess(c.Key, time.Since(started))
		r.mu.Lock()
		r.successes[c.Key]++
		r.mu.Unlock()
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

func main() {
	listen := flag.String("listen", "127.0.0.1:15353", "loopback UDP listen address")
	zone := flag.String("zone", "", "permitted DNS tunnel zone")
	upstreams := flag.String("resolvers", "", "comma-separated resolver IP:port addresses")
	timeout := flag.Duration("timeout", 1500*time.Millisecond, "timeout per resolver")
	flag.Parse()
	addr, err := net.ResolveUDPAddr("udp", *listen)
	if err != nil || addr.IP == nil || !addr.IP.IsLoopback() {
		log.Fatal("listen address must be loopback IP:port")
	}
	if *timeout <= 0 || *timeout > 10*time.Second {
		log.Fatal("timeout must be between 0 and 10 seconds")
	}
	r, err := newRelay(strings.Split(*upstreams, ","), *zone, *timeout)
	if err != nil {
		log.Fatal(err)
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("experimental bridge listening on %s; zone=%s; upstreams=%d", conn.LocalAddr(), r.zone, len(strings.Split(*upstreams, ",")))
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	err = r.serve(ctx, conn)
	r.mu.Lock()
	stats, _ := json.Marshal(map[string]any{"sends": r.sends, "valid_responses": r.successes})
	r.mu.Unlock()
	log.Printf("bridge_stats %s", stats)
	if err != nil {
		log.Fatal(err)
	}
}

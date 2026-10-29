package main

import (
	"context"
	"fmt"
	"time"
)

// These are process-local counters, not the mobile operator's billed usage.
// DNS bytes count UDP payload observed at this relay; IP headers, packets
// arriving after socket close, and other apps are not included. SOCKS bytes
// are TCP stream bytes after CONNECT, typically including TLS overhead.
func reportSlowDNSMetrics(ctx context.Context, dns *relay, proxy *socksRelay) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			fmt.Printf("SLOWDNS_METRICS dns_up_payload=%d dns_down_payload=%d dns_up_packets=%d dns_down_packets=%d stream_up=%d stream_down=%d\n",
				dns.dnsUpBytes.Load(), dns.dnsDownBytes.Load(), dns.dnsUpPackets.Load(), dns.dnsDownPackets.Load(),
				proxy.payloadUp.Load(), proxy.payloadDown.Load())
		}
	}
}

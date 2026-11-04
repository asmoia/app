package main

import (
	"context"
	"fmt"
	"os"
	"time"
)

// These are process-local counters, not the mobile operator's billed usage.
// DNS bytes count UDP payload observed at this relay; IP headers, packets
// arriving after socket close, and other apps are not included. SOCKS bytes
// are TCP stream bytes after CONNECT, typically including TLS overhead.
func printSlowDNSMetrics(dns *relay, proxy *socksRelay, final bool) {
	fmt.Printf("SLOWDNS_METRICS dns_up_payload=%d dns_down_payload=%d dns_up_packets=%d dns_down_packets=%d stream_up=%d stream_down=%d pid=%d final=%t\n",
		dns.dnsUpBytes.Load(), dns.dnsDownBytes.Load(), dns.dnsUpPackets.Load(), dns.dnsDownPackets.Load(),
		proxy.payloadUp.Load(), proxy.payloadDown.Load(), os.Getpid(), final)
}

func reportSlowDNSMetrics(ctx context.Context, dns *relay, proxy *socksRelay) {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			printSlowDNSMetrics(dns, proxy, false)
		}
	}
}

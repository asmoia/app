package main

import (
	"bytes"
	"context"
	"errors"
	"net"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	storm "stormdns-go/internal/client"
)

type queryResult struct {
	reply []byte
	err   error
}

// One delayed duplicate is allowed for a slow/lost query. Unlike ordinary DNS,
// repeated DNSTT questions may have different downstream data, so every valid
// reply is delivered; otherwise useful tunnel data can be discarded.
// At most two upstream requests and r.timeout of additional waiting are used.
func (r *relay) exchangeHedged(packet []byte, req dnsmessage.Header, q dnsmessage.Question, primary storm.Connection, deliver func([]byte)) error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	results := make(chan queryResult, 2)
	start := func(c storm.Connection) {
		go func() { reply, err := r.exchangeOne(ctx, packet, req, q, c); results <- queryResult{reply, err} }()
	}
	start(primary)
	pending := 1
	alternateStarted := false
	launchAlternate := func() {
		if alternateStarted {
			return
		}
		alternateStarted = true
		if other, ok := r.bal.GetBestConnectionExcluding(primary.Key); ok {
			pending++
			start(other)
		}
	}
	timer := time.NewTimer(700 * time.Millisecond)
	defer timer.Stop()
	timerC := timer.C
	succeeded := false
	for pending > 0 {
		select {
		case result := <-results:
			pending--
			if result.err == nil {
				deliver(result.reply)
				succeeded = true
			} else if !alternateStarted {
				launchAlternate()
			}
		case <-timerC:
			timerC = nil
			launchAlternate()
		}
	}
	if !succeeded {
		return errors.New("no valid upstream response")
	}
	return nil
}

func (r *relay) exchangeOne(ctx context.Context, packet []byte, req dnsmessage.Header, q dnsmessage.Question, c storm.Connection) ([]byte, error) {
	r.mu.Lock()
	r.sends[c.Key]++
	r.mu.Unlock()
	r.bal.ReportSend(c.Key)
	started := time.Now()
	d := net.Dialer{Timeout: r.timeout}
	conn, err := d.DialContext(ctx, "udp", c.Key)
	if err != nil {
		if ctx.Err() == nil {
			r.failed(c.Key)
		}
		return nil, err
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	_ = conn.SetDeadline(time.Now().Add(r.timeout))
	if _, err = conn.Write(packet); err != nil {
		if ctx.Err() == nil {
			r.failed(c.Key)
		}
		return nil, err
	}
	r.dnsUpBytes.Add(uint64(len(packet)))
	r.dnsUpPackets.Add(1)
	buf := make([]byte, maxPacket)
	n, err := conn.Read(buf)
	if err != nil {
		if ctx.Err() == nil {
			r.failed(c.Key)
		}
		return nil, err
	}
	r.dnsDownBytes.Add(uint64(n))
	r.dnsDownPackets.Add(1)
	reply := buf[:n]
	h, rq, err := question(reply, true)
	var parsed dnsmessage.Message
	if err != nil || parsed.Unpack(reply) != nil || h.ID != req.ID || h.RCode != dnsmessage.RCodeSuccess || h.Truncated || !strings.EqualFold(rq.Name.String(), q.Name.String()) || rq.Type != q.Type || rq.Class != q.Class {
		if ctx.Err() == nil {
			r.failed(c.Key)
		}
		return nil, errors.New("invalid DNS response")
	}
	r.bal.ReportSuccess(c.Key, time.Since(started))
	r.succeeded(c.Key)
	return bytes.Clone(reply), nil
}

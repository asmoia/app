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

// One delayed duplicate is allowed for a slow/lost query. This avoids waiting
// the full timeout before trying another path; max 2 requests per DNS packet.
func (r *relay) exchangeHedged(packet []byte, req dnsmessage.Header, q dnsmessage.Question, primary storm.Connection) ([]byte, error) {
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
	timer := time.NewTimer(300 * time.Millisecond)
	defer timer.Stop()
	timerC := timer.C
	for pending > 0 {
		select {
		case result := <-results:
			pending--
			if result.err == nil {
				return result.reply, nil
			}
			if !alternateStarted {
				launchAlternate()
			}
		case <-timerC:
			timerC = nil
			launchAlternate()
		}
	}
	return nil, errors.New("no valid upstream response")
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
	buf := make([]byte, maxPacket)
	n, err := conn.Read(buf)
	if err != nil {
		if ctx.Err() == nil {
			r.failed(c.Key)
		}
		return nil, err
	}
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

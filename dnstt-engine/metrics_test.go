package main

import (
	"bytes"
	"sync/atomic"
	"testing"
	"time"
)

func TestTrafficCountersArePayloadOnly(t *testing.T) {
	var dst bytes.Buffer
	var count atomic.Uint64
	w := countingWriter{&dst, &count}
	if n, err := w.Write([]byte("abc")); n != 3 || err != nil || count.Load() != 3 {
		t.Fatal("invalid stream counter")
	}
	resolver := fake(t, true, false)
	r, err := newRelay([]string{resolver}, "test.invalid", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	packet := query(t, "x.test.invalid.")
	if _, err = r.exchange(packet); err != nil {
		t.Fatal(err)
	}
	if r.dnsUpPackets.Load() != 1 || r.dnsDownPackets.Load() != 1 || r.dnsUpBytes.Load() != uint64(len(packet)) || r.dnsDownBytes.Load() != uint64(len(packet)) {
		t.Fatal("invalid DNS counters")
	}
}

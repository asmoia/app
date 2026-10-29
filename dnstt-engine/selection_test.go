package main

import (
	"testing"
	"time"
)

func TestLatencySelectionExploresAlternatives(t *testing.T) {
	fast, slow := fake(t, true, false), fake(t, true, false)
	r, err := newRelay([]string{fast, slow}, "test.invalid", time.Second)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 6; i++ {
		r.bal.ReportSuccess(fast, 25*time.Millisecond)
		r.bal.ReportSuccess(slow, 250*time.Millisecond)
	}
	counts := map[string]int{}
	for i := 0; i < 16; i++ {
		c, ok := r.selectDNSPath()
		if !ok {
			t.Fatal("no path")
		}
		counts[c.Key]++
	}
	if counts[fast] != 14 || counts[slow] != 2 {
		t.Fatalf("unexpected preference/exploration: %v", counts)
	}
	for i := 0; i < 3; i++ {
		r.failed(slow)
	}
	for i := 0; i < 16; i++ {
		c, ok := r.selectDNSPath()
		if !ok || c.Key != fast {
			t.Fatalf("selected cooling path: %v", c.Key)
		}
	}
}

package main

import (
	"testing"
	"time"
)

func TestRepeatedResolverFailureCoolsDownAndCanRecover(t *testing.T) {
	dead := fake(t, false, false)
	healthy := fake(t, true, false)
	r, err := newRelay([]string{dead, healthy}, "test.invalid", 10*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ {
		if _, err = r.exchange(query(t, "payload.test.invalid.")); err != nil {
			t.Fatal(err)
		}
	}
	r.mu.Lock()
	deadSends, healthyReplies := r.sends[dead], r.successes[healthy]
	r.mu.Unlock()
	if deadSends > 4 || healthyReplies != 20 {
		t.Fatalf("failed resolver was not damped: sends=%d healthy=%d", deadSends, healthyReplies)
	}
	// Expired paths are eligible again; a successful response resets the history.
	r.mu.Lock()
	r.cooldownUntil[dead] = time.Now().Add(-time.Second)
	r.mu.Unlock()
	if r.cooling(dead) {
		t.Fatal("expired cooldown did not permit recovery")
	}
	r.succeeded(dead)
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.failures[dead] != 0 {
		t.Fatal("success did not reset failure count")
	}
}

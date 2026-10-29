package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestScanRequiresConnectedMarker(t *testing.T) {
	dir := t.TempDir()
	script := filepath.Join(dir, "engine")
	cfg := engineConfig{Domain: "test.invalid", PublicKey: "key", ClientBinary: "/test/client", Username: "user", Password: "pass", ListenHost: "127.0.0.1", ListenPort: 12345}
	if err := os.WriteFile(script, []byte("#!/bin/sh\necho 'WD_PROGRESS phase=connected percent=100'\n"), 0700); err != nil {
		t.Fatal(err)
	}
	if !scanOne(context.Background(), script, dir, cfg, "1.1.1.1:53") {
		t.Fatal("verified tunnel marker was not accepted")
	}
	if err := os.WriteFile(script, []byte("#!/bin/sh\necho 'DNS replied but tunnel was not verified'\n"), 0700); err != nil {
		t.Fatal(err)
	}
	if scanOne(context.Background(), script, dir, cfg, "1.1.1.1:53") {
		t.Fatal("ordinary DNS response was accepted as verified tunnel")
	}
}

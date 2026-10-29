#!/usr/bin/env bash
# Build the source-available integration helper. The compatible BD NET client
# is separately licensed and must be supplied by an authorized user; it is not
# downloaded or redistributed by this script.
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
go=${GO:-go}
client=${BDNET_CLIENT_SO:-}
[[ -n "$client" && -f "$client" ]] || { echo 'Set BDNET_CLIENT_SO to an authorized arm64-compatible libxdnstt.so' >&2; exit 1; }
expected=9fb149ad83ab5f94e32c298ecc0bbc9840159ea6365413566b74f61aa7c66a86
actual=$(sha256sum "$client" | awk '{print $1}')
[[ "$actual" = "$expected" ]] || { echo 'Client SHA-256 differs from the tested binary; refusing to package an unverified executable' >&2; exit 1; }
cd "$root/dnstt-engine"
# Dependency sums are checked by go build; the StormDNS replacement is local.
CGO_ENABLED=0 GOOS=android GOARCH=arm64 "$go" build -mod=readonly -trimpath -buildvcs=false -ldflags '-s -w' -o "$root/app/src/main/jniLibs/arm64-v8a/libslowdns_engine.so" .
install -m 755 "$client" "$root/app/src/main/jniLibs/arm64-v8a/libxdnstt.so"
chmod 755 "$root/app/src/main/jniLibs/arm64-v8a/libslowdns_engine.so"
printf '%s\n' 'Built SlowDNS integration and installed the authorized arm64 client. No other ABI is supported by this supplied binary.'

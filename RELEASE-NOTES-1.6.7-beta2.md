# WhiteDNS 1.6.7-beta2 — Soar browser reliability

This signed test release addresses Soar connections that pass the startup HTTPS
check but close ordinary browser connections under concurrent TLS handshakes.
MasterDNS/StormDNS and CottenDNS engines and profile formats are unchanged.

- Bound simultaneous initial stream flights, including their window announcements,
  and scale startup concurrency to measured path capacity. A small DNS budget can
  complete connection prefaces instead of spreading partial handshakes over every
  destination. Established small writes and bulk transfers retain progress.
- Learn the usable DNS query-name size using paired authenticated probes. Common
  loss no longer permanently shrinks a proven large-query path; actual size
  filtering falls back through intermediate lengths and is retried with backoff.
- Add resolver RTT, recent loss, accepted query/answer limits and congestion-window
  details to `SOAR_PATHS` logs without recording destination URLs.
- Align new Soar defaults with an adaptive profile: begin at 101 characters, allow
  verified growth to 253, use up to 1232-byte DNS answers, a 128-query total ceiling
  and 32-query path ceiling. Existing imported limits remain explicit.

Update the APK without uninstalling the existing app, then reimport the new
adaptive link for the same panel account. Updating the server alone cannot
replace the native engine already installed on the phone.

Lantern remains the author of Soar. The original Apache-2.0 source and license,
resolver snapshots and wire vectors are retained. WhiteDNS changes are documented
in `soar-engine/third_party/soar/WHITEDNS_CHANGES.md`.

Validation covers the retained upstream protocol/crypto suite, added query-sizing
and scheduling regressions, constrained DNS/failover tests, and real Chrome HTTPS
checks through the actual engine. Public Amazon anti-bot responses are distinct
from transport success. This prerelease does not claim verified Iranian mobile
coverage or physical-device Full VPN qualification.

# WhiteDNS 1.6.7 — Stable

Stable distribution of the Soar browser reliability fixes tested in 1.6.7-beta2.
Runtime behavior is unchanged from that candidate. The version and release
channel are promoted; MasterDNS/StormDNS and CottenDNS cores remain unchanged.

- Complete initial stream flights without spreading unfinished TLS handshakes
  across too many destinations. Initial window announcements share the same
  scheduling limit; concurrency follows measured path capacity. Established
  small writes and bulk uploads retain progress.
- Learn DNS query-name capacity with paired safe-name canaries, intermediate
  limits and backed-off retries. Ordinary packet loss no longer permanently
  pins a proven large-query path to the minimum name size.
- Start new Soar profiles at a safe 101-character query name and permit verified
  growth up to 253, answers up to 1232 bytes, 128 total queries and 32 per path.
  Explicit settings in existing imported profiles remain intact.
- Include Lantern Soar v0.2.3 unused-session recovery and `SOAR_PATHS` diagnostics
  for resolver RTT, loss, accepted query/answer budgets and congestion windows.
  These diagnostics do not record browsing URLs.

## Install or upgrade

Install the signed APK over WhiteDNS 1.6.6 or 1.6.7-beta2; do not uninstall the app.
The application ID and official signing identity are preserved, and the version
code advances to 29. Use the universal APK when unsure of the phone architecture.

For Soar, use [server panel 0.2.0 or newer](https://github.com/WhiteDNS/Soar-Server),
save the adaptive profile and reimport the same account's link. Updating the APK
does not silently replace explicit limits in older imported profiles. Account
keys, quotas and expiry remain server controlled.

## Verification and scope

Official release gates run Android unit tests, the retained upstream protocol
and crypto suite, original wire vectors, scheduling/query-sizing regressions,
constrained DNS/TLS and failover tests, race detection, all four Android ABI
builds, APK signing and packaged-core verification. Lantern attribution and the
original Apache-2.0 sources, resolver snapshots and licenses are retained.

Earlier cache-free Chrome tests through the actual engine returned page
responses without failed resources. Amazon responses included anti-bot pages,
not a qualified storefront. An isolated earlier DNS-path stall remains
unexplained and is disclosed in the
[qualification report](https://github.com/WhiteDNS/Soar-Server/blob/panel-v0.2.0/docs/SOAR-WEB-RELIABILITY-2026-10-18.fa.md).
Stable release status does not certify every website, Iranian carrier coverage
or physical-device Full VPN behavior. No new physical-device or Iran-network
qualification is claimed by this promotion.

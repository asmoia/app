# WhiteDNS 1.6.8 — Account subscriptions

Import an HTTP/HTTPS subscription URL from Profiles → Connection → Import, or
scan its QR code and confirm. The new WhiteDNS Soar Panel 0.3.0 provides these
URLs beside the existing direct profile links.

Refresh subscriptions downloads updated profiles without creating duplicates.
Stable profile IDs retain selection, local connection mode and manual resolver
choices. Provider-owned keys, DNS lists and tuning update. Usage/quota, expiry
and the last successful refresh are shown as a snapshot. Refresh is available
while disconnected; due subscriptions also refresh when opening/resuming the
app without a running tunnel. There is no guarantee of background refresh while
Android has closed the app.

Failed or invalid responses retain saved profiles. HTTPS certificate validation
stays enabled and downgrades to HTTP are blocked. Unencrypted HTTP requires
explicit per-source consent. Subscription URLs are private credentials and are
excluded from diagnostic logs and direct profile exports. The importer limits
response size, profile count and redirects.

Existing direct Soar, MasterDNS/StormDNS and CottenDNS links continue to work.
This update uses the existing tunnel cores and official signing identity: install
the APK over the current official version without uninstalling or clearing data.

[Subscription guide](https://github.com/WhiteDNS/Soar-Server/blob/main/docs/SUBSCRIPTIONS.md)

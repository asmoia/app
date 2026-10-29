# WhiteDNS 1.6.10-beta1 — Soar scan workers

- Soar resolver scans now use the Workers count selected on the Scan screen, including 30 workers, instead of always starting one worker. Fewer remaining resolvers naturally use fewer workers.
- Each Soar scan worker probes one resolver at a time, so the profile's discovery concurrency does not multiply the selected scan workload.

This prerelease includes the interface redesign and permission fixes shipped in
1.6.9. Existing profiles, subscriptions and saved settings remain supported.
Connection discovery settings are preserved.

The official signed APKs can update the installed official app without clearing
data. This is a testing release; 1.6.9 remains the latest stable release.
Physical-device scan performance has not yet been qualified for this fix.

# WhiteDNS 1.6.6

- Add the Soar engine with SOCKS proxy and Full VPN modes, `soar://` profile
  import/export and advanced transport/discovery controls.
- Import and select a Soar profile's linked resolver list while preserving
  existing connection and resolver profiles.
- Default new Soar profiles to IR discovery; include current network DNS,
  country/global candidates, a 90-second startup budget and Conservative mode.
- Verify each Soar scan result with authenticated, certificate-verified HTTPS.
- Fix selecting an older connection after adding a config: tap its card or use
  its separate Use profile menu action. Editing remains separate.

Server setup: https://github.com/WhiteDNS/Soar-Server
Instructions and limits: [docs/SOAR.md](https://github.com/WhiteDNS/WhiteDNS-Android/blob/main/docs/SOAR.md).

Iran is the primary target. Operation inside Iran has not yet been field-tested.
Android interaction tests were compiled but require a device to execute.

This release uses the official WhiteDNS application ID and release signing
process. It updates the regular app. The separate experimental Soar alpha app
can coexist; export/import its profiles to move them to the official app.

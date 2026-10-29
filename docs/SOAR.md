# Soar setup and network selection

WhiteDNS 1.6.6 introduced Lantern Soar v0.2.2 alongside StormDNS and CottenDNS.
The current stable release, WhiteDNS 1.6.7, uses the attributed Soar v0.2.3 source
with the WhiteDNS transport fixes described below. Server panel 0.2.0 is available
from the [guided panel installer](https://github.com/WhiteDNS/Soar-Server).
Soar requires its own server and public key; Master/Cotten server settings
cannot be reused for this engine.

## Server / نصب سرور

Use [WhiteDNS/Soar-Server](https://github.com/WhiteDNS/Soar-Server) on Ubuntu
24.04 amd64. After logging into the VPS with SSH, run:

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/WhiteDNS/Soar-Server/main/setup.sh)
```

The terminal prompts use English and explain each step. The repository includes
a full Persian guide. Use a separate DNS subdomain to preserve existing tunnels.
After installation, `sudo soar-manager profile` creates an IR or GLOBAL link.

## Android / اتصال گوشی

۱. لینک `soar://` تولیدشده را در Profiles > Connection > Import وارد کنید.
پروفایل اتصال و ریزالورهای همراه آن خودکار انتخاب می‌شوند.

۲. در ایران کشور IR، کشف روشن و انتقال Auto را انتخاب کنید؛ در کشورهای دیگر
GLOBAL. موتور DNS شبکهٔ فعلی و نامزدهای کشف را بررسی می‌کند؛ یک DNS عمومی
ثابت الزام اتصال نیست. نامزدها باید پاسخ سرور را احراز کنند.

۳. برای اینترنت محدود، Conservative نام DNS را تا ۱۰۱ و پاسخ را تا ۵۱۲ بایت
کوچک می‌کند و هم‌زمانی را کاهش می‌دهد. شروع اتصال به‌طور پیش‌فرض تا ۹۰ ثانیه
فرصت دارد. اسکن Soar علاوه بر احراز سرور، HTTPS با گواهی معتبر را آزمایش می‌کند.

۴. برای انتخاب کانفیگ قبلی، کارت آن یا منوی سه‌نقطه > Use profile را بزنید.
انتخاب هنگام راه‌اندازی اتصال موقتاً غیرفعال است. اتصال روشن را پس از تغییر
پروفایل قطع و دوباره برقرار کنید. جابه‌جایی Wi-Fi/دادهٔ همراه هم به اتصال مجدد
نیاز دارد تا DNS شبکه به‌روز شود.

The experimental Soar alpha app used a separate package ID; the official
release updates the regular WhiteDNS app. Existing alpha profiles can be
exported and imported. Regular WhiteDNS connection profiles remain available.

## Limits

The reference server carries TCP web traffic to public destinations on ports
80/443. UDP/QUIC, all calls/games and arbitrary ports are outside this scope.
Knowing the zone and public key allows use; there are no per-user quotas.
DNS addresses from the phone network stay local and are not put in share links.

Tests on available external networks and a phone in Thailand are evidence for
those environments. Operation inside Iran, operator differences, screen-off
and network switching still need field verification. No country-wide
availability guarantee is made.


## Browser reliability update (1.6.7)

The original connectivity probe only establishes that a small HTTPS request can
cross the tunnel. It does not qualify concurrent browser handshakes or complete
pages. This update changes the Soar client transport, not the wire protocol:

- Bound simultaneous initial stream flights, with priority for small writes on
  established streams and a reserved scheduling share for bulk uploads.
- Compare long query-name probes with short-name canaries before accepting a
  size-specific downgrade. General congestion no longer pins a healthy path to
  a 101-character name for the rest of its lifetime. Probe intermediate lengths
  and retry after a temporary restriction.
- Emit `SOAR_PATHS` diagnostics with RTT, recent loss, accepted query/answer budgets
  and congestion window. These lines contain resolver state, not browsing URLs.

Install the new signed APK and reimport a freshly exported adaptive profile from
the panel. Existing explicitly capped profiles keep their limits. A fixed
MaxQName=101 profile has very little upload payload per DNS request, especially
with a long per-account zone; it should be reserved for paths that actually need
that restriction. The adaptive profile still starts at SafeQName=101.

The modified Apache-2.0 sources and their original Lantern attribution are in
`soar-engine/third_party/soar`. Upstream wire vectors and tests are retained.
This is a WhiteDNS transport patch based on Soar v0.2.3, not an upstream release.
Public browser tests must distinguish an Amazon HTTP anti-bot response from a
transport failure. Android Full VPN on a physical phone and Iranian networks
still require device/network validation; a desktop SOCKS test cannot prove them.


Opt-in browser reproduction (requires Playwright, Chrome/Chromium and a private
JSON profile export; never commit the profile or output directory):

```sh
node scripts/soar-browser-probe.cjs --engine PATH_TO_SOAR_CORE --profile PRIVATE_PROFILE.json --output PRIVATE_DIR --expected-ip SERVER_IP --url https://example.com/ --url https://amazon.co.uk/ --url https://www.mozilla.org/en-US/
```

`SOAR_PLAYWRIGHT` can point to the Playwright module and `SOAR_CHROME` to Chrome.
The test uses SOCKS remote DNS, validates HTTPS certificates, records actual
responses/resource failures and identifies challenge pages. A challenge response
is reachable transport, not proof that the site's complete service is available.

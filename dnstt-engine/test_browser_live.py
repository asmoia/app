#!/usr/bin/env python3
"""Opt-in real Chromium browsing through a local SOCKS5 proxy, no login.
Requires playwright and Chromium installed separately. No requests go directly
from Chromium: SOCKS5 proxy, QUIC off, local DNS resolution disabled.
"""
import argparse
import json
import time
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright

SITES = [
    ('example', 'https://example.com/'),
    ('wikipedia', 'https://en.wikipedia.org/wiki/Internet'),
    ('python', 'https://www.python.org/'),
    ('telegram_web', 'https://web.telegram.org/'),
]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, required=True)
    ap.add_argument('--timeout', type=int, default=45)
    ap.add_argument('--output', default='browser-results.json')
    a = ap.parse_args()
    results = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, proxy={'server': f'socks5://127.0.0.1:{a.port}'},
            args=['--disable-quic', '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1',
                  '--disable-background-networking', '--no-sandbox'])
        for name, url in SITES:
            context = browser.new_context(service_workers='block')
            page = context.new_page()
            requested, finished, failed, statuses = [], [], [], []
            page.on('request', lambda req: requested.append(req))
            page.on('requestfinished', lambda req: finished.append(req))
            page.on('requestfailed', lambda req: failed.append({'host': urlsplit(req.url).hostname, 'type': req.resource_type, 'error': req.failure}))
            page.on('response', lambda response: statuses.append({'host':urlsplit(response.url).hostname, 'type':response.request.resource_type, 'status':response.status}))
            started = time.monotonic()
            info = {'site': name, 'url': url}
            try:
                response = page.goto(url, wait_until='domcontentloaded', timeout=a.timeout * 1000)
                info['dom_seconds'] = round(time.monotonic() - started, 2)
                info['main_http_status'] = response.status if response else None
                try:
                    page.wait_for_load_state('load', timeout=20000)
                    info['load_seconds'] = round(time.monotonic() - started, 2)
                except Exception as exc:
                    info['load_error'] = type(exc).__name__
                page.wait_for_timeout(3000)
                if name == 'telegram_web':
                    try:
                        page.wait_for_function('document.body && document.body.innerText.trim().length > 25', timeout=60000)
                        info['telegram_ui_seconds'] = round(time.monotonic() - started, 2)
                    except Exception as exc:
                        info['telegram_ui_error'] = type(exc).__name__
                info['title'] = page.title()
                info['body_text_characters'] = len(page.locator('body').inner_text(timeout=3000))
                info['final_url'] = page.url
                info['images'] = page.evaluate('''() => ({total:document.images.length,
                    loaded:Array.from(document.images).filter(i => i.complete && i.naturalWidth>0).length})''')
                info['rendered'] = bool(info['body_text_characters'])
            except Exception as exc:
                info['error'] = str(exc).split('\n')[0]
                info['elapsed_seconds'] = round(time.monotonic() - started, 2)
            info['requests'] = len(requested)
            info['finished'] = len(finished)
            info['failed'] = failed
            info['http_errors'] = [s for s in statuses if s['status'] >= 400]
            info['pending_at_sample'] = max(0, len(requested)-len(finished)-len(failed))
            results.append(info)
            print(json.dumps(info, ensure_ascii=False), flush=True)
            context.close()
        browser.close()
    with open(a.output, 'w', encoding='utf-8') as f:
        json.dump({'proxy': f'socks5://127.0.0.1:{a.port}', 'results': results}, f, indent=2, ensure_ascii=False)

if __name__ == '__main__': main()

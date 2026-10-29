#!/usr/bin/env python3
"""Local-only DNSTT -> Storm balancer bridge -> two DNS paths -> DNSTT server -> HTTP.
Requires binaries passed on command line; no third-party server or credentials used.
"""
import argparse
import http.server
import json
import os
import socket
import subprocess
import tempfile
import threading
import time

MARKER = b'dnstt-storm-e2e-ok'
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Length', str(len(MARKER)))
        self.end_headers()
        self.wfile.write(MARKER)
    def log_message(self, *_): pass

def free_port(kind):
    with socket.socket(socket.AF_INET, kind) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

class FakeResolver:
    def __init__(self, server):
        self.server = server
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(('127.0.0.1', 0))
        self.sock.settimeout(.2)
        self.address = self.sock.getsockname()
        self.count = 0
        self.closed = threading.Event()
        threading.Thread(target=self.run, daemon=True).start()
    def run(self):
        while not self.closed.is_set():
            try: packet, peer = self.sock.recvfrom(4096)
            except socket.timeout: continue
            except OSError: break
            self.count += 1
            threading.Thread(target=self.forward, args=(packet, peer), daemon=True).start()
    def forward(self, packet, peer):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.settimeout(2)
                s.connect(self.server)
                s.send(packet)
                answer = s.recv(4096)
                self.sock.sendto(answer, peer)
        except OSError: pass
    def close(self): self.closed.set(); self.sock.close()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--client', required=True)
    parser.add_argument('--server', required=True)
    parser.add_argument('--bridge', required=True)
    args = parser.parse_args()
    procs = []
    paths = []
    httpd = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    with tempfile.TemporaryDirectory(prefix='dnstt-e2e-') as tmp:
        logfile = open(os.path.join(tmp, 'processes.log'), 'w+')
        try:
            priv, pub = os.path.join(tmp, 'key'), os.path.join(tmp, 'pub')
            subprocess.run([args.server, '-gen-key', '-privkey-file', priv, '-pubkey-file', pub], check=True, capture_output=True, timeout=10)
            serverport = free_port(socket.SOCK_DGRAM)
            target = '127.0.0.1:%d' % httpd.server_port
            def start(cmd):
                p = subprocess.Popen(cmd, stdout=logfile, stderr=subprocess.STDOUT)
                procs.append(p)
                return p
            start([args.server, '-udp', f'127.0.0.1:{serverport}', '-privkey-file', priv, 't.test', target])
            time.sleep(.4)
            paths = [FakeResolver(('127.0.0.1', serverport)) for _ in range(2)]
            resolverargs = ','.join(f'{host}:{port}' for host,port in (p.address for p in paths))
            bridgeport = free_port(socket.SOCK_DGRAM)
            start([args.bridge, '-listen', f'127.0.0.1:{bridgeport}', '-zone', 't.test', '-resolvers', resolverargs, '-timeout', '2s'])
            localport = free_port(socket.SOCK_STREAM)
            start([args.client, '-udp', f'127.0.0.1:{bridgeport}', '-pubkey-file', pub, 't.test', f'127.0.0.1:{localport}'])
            deadline = time.monotonic() + 10
            while True:
                try:
                    conn = socket.create_connection(('127.0.0.1', localport), timeout=1)
                    break
                except OSError:
                    if time.monotonic() > deadline: raise
                    time.sleep(.1)
            with conn:
                conn.settimeout(35)
                conn.sendall(b'GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n')
                data = b''
                while True:
                    part = conn.recv(8192)
                    if not part: break
                    data += part
                    if MARKER in data: break
            counts = [p.count for p in paths]
            if MARKER not in data or not data.startswith(b'HTTP/1.0 200'):
                raise RuntimeError('HTTP reply missing or invalid')
            if not all(counts): raise RuntimeError(f'not all paths used: {counts}')
            print(json.dumps({'status': 'PASS', 'http_status': 200, 'body_verified': True,
                              'query_counts_per_local_simulated_resolver': counts,
                              'scope': 'single local DNSTT server; not BD NET or Android'}, indent=2))
        except Exception:
            logfile.flush(); logfile.seek(0)
            print('--- process logs ---\n' + logfile.read())
            raise
        finally:
            for p in procs: p.terminate()
            for p in procs:
                try: p.wait(timeout=3)
                except subprocess.TimeoutExpired: p.kill(); p.wait()
            for p in paths: p.close()
            httpd.shutdown(); httpd.server_close(); logfile.close()

if __name__ == '__main__': main()

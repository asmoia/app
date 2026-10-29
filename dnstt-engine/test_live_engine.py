#!/usr/bin/env python3
"""Opt-in authorized live integration test. Credentials are never printed."""
import argparse
import json
import os
import socket
import ssl
import subprocess
import tempfile
import time


def receive(sock, n):
    data = b''
    while len(data) < n:
        part = sock.recv(n - len(data))
        if not part:
            raise EOFError('SOCKS socket closed')
        data += part
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--engine', required=True)
    parser.add_argument('--client', required=True, help='compatible client executable; a qemu wrapper is acceptable')
    parser.add_argument('--source', required=True, help='authorized AM-MST slowdns.json')
    parser.add_argument('--server-index', type=int, default=0)
    args = parser.parse_args()
    source = json.load(open(args.source, encoding='utf-8'))
    if isinstance(source.get('servers'), list):
        server = source['servers'][args.server_index]
        auth = {'username': source['username'], 'password': source['password']}
        pubkey, report = 'prince', source['report']
    else:
        server = source['servers']['list'][args.server_index]
        auth = source['far_end']['socks5']['auth']
        pubkey, report = source['engine']['pubkey_label'], source['engine']['report_string']
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
    cfg = {'domain': server['zone'], 'publicKey': pubkey,
           'report': report, 'clientBinary': os.path.abspath(args.client),
           'username': auth['username'], 'password': auth['password'], 'listenHost': '127.0.0.1',
           'listenPort': port, 'verifyUrl': 'https://example.com/', 'startupTimeoutSec': 90}
    with tempfile.TemporaryDirectory(prefix='whitedns-slowdns-') as tmp:
        config = os.path.join(tmp, 'config.json'); resolvers = os.path.join(tmp, 'resolvers.txt')
        with open(config, 'w', encoding='utf-8') as f: json.dump(cfg, f)
        os.chmod(config, 0o600)
        with open(resolvers, 'w', encoding='utf-8') as f: f.write('1.1.1.1\n8.8.8.8\n')
        with tempfile.TemporaryFile(mode='w+t') as logs:
            proc = subprocess.Popen([args.engine, '-config', config, '-resolvers', resolvers], stdout=logs, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 100
                while True:
                    logs.flush(); logs.seek(0); text = logs.read()
                    if 'WD_PROGRESS phase=connected' in text: break
                    if proc.poll() is not None: raise RuntimeError('engine exited before readiness: ' + text)
                    if time.monotonic() >= deadline: raise TimeoutError('engine did not become ready')
                    time.sleep(.2)
                with socket.create_connection(('127.0.0.1', port), timeout=15) as conn:
                    conn.settimeout(20); conn.sendall(b'\x05\x01\x00')
                    if receive(conn, 2) != b'\x05\x00': raise RuntimeError('local SOCKS greeting failed')
                    host=b'example.com'; conn.sendall(b'\x05\x01\x00\x03'+bytes([len(host)])+host+b'\x01\xbb')
                    header=receive(conn,4)
                    if header[:2]!=b'\x05\x00': raise RuntimeError('SOCKS CONNECT failed')
                    if header[3]==1: receive(conn,6)
                    elif header[3]==4: receive(conn,18)
                    elif header[3]==3: receive(conn,receive(conn,1)[0]+2)
                    else: raise RuntimeError('invalid SOCKS reply')
                    with ssl.create_default_context().wrap_socket(conn, server_hostname='example.com') as tls:
                        tls.settimeout(20); tls.sendall(b'GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n')
                        line=b''
                        while b'\r\n' not in line and len(line)<4096:
                            part=tls.recv(128)
                            if not part: break
                            line+=part
                        status=line.split(b'\r\n',1)[0].decode('ascii','replace')
                        if ' 200 ' not in status: raise RuntimeError('unexpected HTTP status: '+status)
                print(json.dumps({'status':'PASS', 'server':server['zone'], 'resolvers':2,
                                  'readiness':'authenticated HTTPS', 'separate_request':status,
                                  'scope':'Linux with optional qemu; not Android device'}, indent=2))
            finally:
                proc.terminate()
                try: proc.wait(timeout=8)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()

if __name__=='__main__': main()

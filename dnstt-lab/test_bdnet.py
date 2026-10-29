#!/usr/bin/env python3
"""Authorized live diagnostic. Requires a supplied compatibility client and its runtime.
Reads credentials from a local JSON config; never logs their values. Not an Android test.
"""
import argparse
import http.client
import json
import os
import socket
import ssl
import subprocess
import tempfile
import time


def recv_exact(s, length):
    result = b''
    while len(result) < length:
        data = s.recv(length - len(result))
        if not data: raise EOFError(f'connection closed after {len(result)}/{length} bytes')
        result += data
    return result

def local_port(kind):
    with socket.socket(socket.AF_INET, kind) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

def main():
    p=argparse.ArgumentParser()
    for key in ('config', 'client', 'bridge', 'qemu', 'sysroot'): p.add_argument('--'+key, required=True)
    p.add_argument('--server-index', type=int, default=0, help='zero-based index in the supplied config')
    args=p.parse_args()
    with open(args.config) as f: config=json.load(f)
    auth=config['far_end']['socks5']['auth']
    username=auth['username'].encode(); password=auth['password'].encode()
    server=config['servers']['list'][args.server_index]
    zone=server['zone']; direct=server['udp']
    trials=[('direct', None), ('bridge_direct_server', direct), ('bridge_public_resolvers', '1.1.1.1:53,8.8.8.8:53')]
    results=[]
    for name, resolvers in trials:
        procs=[]; started=time.monotonic(); stage='starting'; item={'test':name}
        with tempfile.TemporaryFile(mode='w+t') as log:
            try:
                dnsaddr=direct
                if resolvers:
                    port=local_port(socket.SOCK_DGRAM); dnsaddr=f'127.0.0.1:{port}'
                    procs.append(subprocess.Popen([args.bridge,'-listen',dnsaddr,'-zone',zone,'-resolvers',resolvers,'-timeout','2s'], stdout=log, stderr=subprocess.STDOUT))
                    time.sleep(.15)
                port=local_port(socket.SOCK_STREAM)
                env=os.environ.copy(); env['QEMU_LD_PREFIX']=args.sysroot
                procs.append(subprocess.Popen([args.qemu,args.client,'-udp',dnsaddr,'-report',config['engine']['report_string'], '-pubkey',config['engine']['pubkey_label'],zone,f'127.0.0.1:{port}'],env=env,stdout=log,stderr=subprocess.STDOUT))
                stage='local_client_listener'; deadline=time.monotonic()+10
                while True:
                    if procs[-1].poll() is not None: raise RuntimeError('client process exited before listen')
                    try:
                        s=socket.create_connection(('127.0.0.1',port),timeout=2); break
                    except OSError:
                        if time.monotonic()>deadline: raise
                        time.sleep(.2)
                with s:
                    s.settimeout(20); stage='socks_method'
                    s.sendall(b'\x05\x01\x02'); method=recv_exact(s,2)
                    if method!=b'\x05\x02': raise RuntimeError(f'SOCKS method refused: {method.hex()}')
                    stage='socks_auth'; s.sendall(b'\x01'+bytes([len(username)])+username+bytes([len(password)])+password)
                    if recv_exact(s,2)!=b'\x01\x00': raise RuntimeError('SOCKS authentication refused')
                    stage='socks_connect'; host=b'example.com'
                    s.sendall(b'\x05\x01\x00\x03'+bytes([len(host)])+host+(443).to_bytes(2,'big'))
                    h=recv_exact(s,4)
                    if h[:2]!=b'\x05\x00': raise RuntimeError('SOCKS CONNECT refused: '+h.hex())
                    if h[3]==1: recv_exact(s,6)
                    elif h[3]==4: recv_exact(s,18)
                    elif h[3]==3: recv_exact(s,recv_exact(s,1)[0]+2)
                    else: raise RuntimeError('invalid SOCKS reply address type')
                    stage='tls_https'; ctx=ssl.create_default_context()
                    with ctx.wrap_socket(s, server_hostname='example.com') as tls:
                        tls.settimeout(20)
                        tls.sendall(b'GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n')
                        response=http.client.HTTPResponse(tls)
                        response.begin()
                        if response.status != 200:
                            raise RuntimeError(f'HTTPS returned HTTP {response.status}')
                        item.update(status='PASS',http_status_line=f'HTTP {response.status} {response.reason}',tls_verified=True)
            except Exception as e:
                item.update(status='FAIL',stage=stage,error=f'{type(e).__name__}: {e}')
            finally:
                for proc in procs: proc.terminate()
                for proc in procs:
                    try: proc.wait(timeout=3)
                    except subprocess.TimeoutExpired: proc.kill(); proc.wait()
                log.seek(0); logs=log.read().splitlines()
                item['client_log_markers']=[line for line in logs if 'effective MTU' in line or 'begin session' in line or 'begin stream' in line][-5:]
                for line in logs:
                    if 'bridge_stats ' in line:
                        item['bridge_stats'] = json.loads(line.split('bridge_stats ', 1)[1])
                item['elapsed_s']=round(time.monotonic()-started,2)
                results.append(item)
    print(json.dumps({'zone':zone,'scope':'authorized live server, host Linux/qemu; not Android or Iran', 'results':results},indent=2))

if __name__=='__main__': main()

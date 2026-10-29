#!/usr/bin/env python3
"""Bounded opt-in HTTP range test, with integrity and chunk timings.
Downloads only N bytes per case; no external download manager is required.
Run only against a permitted range-enabled public resource.
"""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
import socket
import ssl
import time
from urllib.parse import urlsplit
from test_telegram_live import socks_connect


def get_range(url, start, count, proxy_port, timeout):
    u = urlsplit(url)
    if u.scheme != 'https' or not u.hostname or u.username or u.password:
        raise ValueError('HTTPS URL required')
    t0 = time.monotonic()
    c = socks_connect(proxy_port, u.hostname, u.port or 443, timeout) if proxy_port else socket.create_connection((u.hostname, u.port or 443), timeout)
    c.settimeout(timeout)
    with c, ssl.create_default_context().wrap_socket(c, server_hostname=u.hostname) as s:
        path = u.path or '/'
        if u.query: path += '?' + u.query
        s.sendall((f'GET {path} HTTP/1.1\r\nHost: {u.netloc}\r\nRange: bytes={start}-{start+count-1}\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n').encode('ascii'))
        r = http.client.HTTPResponse(s); r.begin()
        expected = f'bytes {start}-{start+count-1}/'
        if r.status != 206 or not r.getheader('Content-Range', '').startswith(expected):
            raise RuntimeError(f'server did not honor requested range: status={r.status}')
        if r.length != count: raise RuntimeError(f'unexpected length: {r.length}')
        data = bytearray(); samples=[]
        while len(data) < count:
            b = r.read(min(8192, count-len(data)))
            if not b: raise EOFError(f'incomplete range {len(data)}/{count}')
            data.extend(b); samples.append([round(time.monotonic()-t0, 2),len(data)])
        return bytes(data), samples


def run_case(label, url, n, connections, port, timeout):
    t0=time.monotonic(); parts=[(i*n//connections, (i+1)*n//connections-i*n//connections) for i in range(connections)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=connections) as exe:
        jobs=[exe.submit(get_range,url,start,count,port,timeout) for start,count in parts]
        result=[job.result() for job in jobs]
    data=b''.join(x[0] for x in result); elapsed=time.monotonic()-t0
    return {'case':label,'connections':connections,'bytes':len(data),'seconds':round(elapsed,2),'bytes_per_second':round(len(data)/elapsed),'sha256':hashlib.sha256(data).hexdigest(),'samples':[r[1] for r in result]}


def main():
    p=argparse.ArgumentParser();p.add_argument('--url',required=True);p.add_argument('--port',required=True,type=int)
    p.add_argument('--bytes',type=int,default=262144);p.add_argument('--timeout',type=int,default=40);p.add_argument('--output',default='range-results.json');a=p.parse_args()
    if not 4096<=a.bytes<=1048576:raise ValueError('test size must be between 4 KiB and 1 MiB per case')
    results=[]
    for label,conns,port in [('direct',1,None),('socks_single',1,a.port),('socks_four',4,a.port)]:
        try:
            r=run_case(label,a.url,a.bytes,conns,port,a.timeout);results.append(r)
            print(json.dumps(r),flush=True)
        except Exception as e:
            r={'case':label,'error':type(e).__name__+': '+str(e)};results.append(r);print(json.dumps(r),flush=True)
    hashes=[r['sha256'] for r in results if 'sha256' in r]
    print('hashes_match:',len(hashes)==3 and len(set(hashes))==1,flush=True)
    with open(a.output,'w') as f:json.dump(results,f,indent=2)
    if len(hashes)!=3 or len(set(hashes))!=1:raise SystemExit(1)

if __name__=='__main__':main()

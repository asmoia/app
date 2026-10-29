#!/usr/bin/env python3
"""Opt-in two-server integration test; never prints remote credentials."""
import argparse
import json
import os
import socket
import signal
import ssl
import subprocess
import tempfile
import time
from test_live_engine import receive

def request(port):
    with socket.create_connection(('127.0.0.1', port), timeout=15) as c:
        c.settimeout(30); c.sendall(b'\x05\x01\x00')
        if receive(c, 2) != b'\x05\x00': raise RuntimeError('SOCKS greeting failed')
        h=b'example.com'; c.sendall(b'\x05\x01\x00\x03'+bytes([len(h)])+h+b'\x01\xbb')
        r=receive(c,4)
        if r[:2]!=b'\x05\x00': raise RuntimeError('SOCKS CONNECT failed')
        if r[3]==1: receive(c,6)
        elif r[3]==4: receive(c,18)
        elif r[3]==3: receive(c,receive(c,1)[0]+2)
        else: raise RuntimeError('bad address')
        with ssl.create_default_context().wrap_socket(c,server_hostname='example.com') as tls:
            tls.settimeout(30);tls.sendall(b'GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n')
            line=b''
            while b'\r\n' not in line and len(line)<4096:
                chunk=tls.recv(128)
                if not chunk: break
                line+=chunk
            if b' 200 ' not in line.split(b'\r\n',1)[0]: raise RuntimeError('unexpected HTTPS response')

def main():
    p=argparse.ArgumentParser()
    for key in ('source','engine','client'): p.add_argument('--'+key,required=True)
    p.add_argument('--fault', action='store_true', help='kill one child after both sessions are ready (Linux only)')
    a=p.parse_args();x=json.load(open(a.source,encoding='utf-8')); auth=x['far_end']['socks5']['auth']
    zones=[x['servers']['list'][0]['zone'],x['servers']['list'][19]['zone']]
    with socket.socket() as sock: sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    cfg={'domain':zones[0],'domains':zones,'poolSize':2,'publicKey':x['engine']['pubkey_label'],'report':x['engine']['report_string'],
         'clientBinary':os.path.abspath(a.client),'username':auth['username'],'password':auth['password'],
         'listenHost':'127.0.0.1','listenPort':port,'verifyUrl':'https://example.com/','startupTimeoutSec':90}
    with tempfile.TemporaryDirectory(prefix='whitedns-pool-') as d, tempfile.TemporaryFile(mode='w+t') as log:
        conf=os.path.join(d,'config.json');res=os.path.join(d,'resolvers')
        with open(conf,'w',encoding='utf-8') as f:json.dump(cfg,f)
        os.chmod(conf,0o600)
        with open(res,'w',encoding='utf-8') as f:f.write('1.1.1.1\n8.8.8.8\n')
        proc=subprocess.Popen([a.engine,'-config',conf,'-resolvers',res],stdout=log,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+100
            while True:
                log.flush();log.seek(0);txt=log.read()
                if 'SLOWDNS_POOL ready=2' in txt and 'WD_PROGRESS phase=connected' in txt:break
                if proc.poll() is not None:raise RuntimeError('pool exited: '+txt)
                if time.monotonic()>deadline:raise TimeoutError('two sessions not ready: '+txt)
                time.sleep(.3)
            for i in range(4):request(port)
            if a.fault:
                children=set()
                for thread in os.listdir(f'/proc/{proc.pid}/task'):
                    try:
                        children.update(open(f'/proc/{proc.pid}/task/{thread}/children',encoding='ascii').read().split())
                    except FileNotFoundError:
                        pass
                if len(children)<2: raise RuntimeError('could not locate both engine child processes')
                os.kill(int(sorted(children)[0]),signal.SIGKILL)
                deadline=time.monotonic()+20
                while True:
                    log.flush();log.seek(0);txt=log.read()
                    if 'server_stopped=' in txt:break
                    if proc.poll() is not None or time.monotonic()>deadline:raise RuntimeError('pool did not detect killed child: '+txt)
                    time.sleep(.2)
                for i in range(2):request(port)
            print(json.dumps({'status':'PASS','servers':zones,'ready_sessions':2,'https_requests_before_fault':4,
                              'killed_child':a.fault,'new_https_requests_after_fault':2 if a.fault else 0,
                              'scope':'Linux with qemu client; not a phone'},indent=2))
        finally:
            proc.terminate()
            try:proc.wait(timeout=15)
            except subprocess.TimeoutExpired:proc.kill();proc.wait()

if __name__=='__main__':main()

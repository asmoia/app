#!/usr/bin/env python3
"""Opt-in public MTProto handshake and bounded download through a running SOCKS5 proxy.
Does not log in or require Telegram credentials. No personal messages are sent.
"""
import argparse
import http.client
import json
import os
import socket
import ssl
import struct
import time


def recv_exact(sock, length):
    out = b''
    while len(out) < length:
        part = sock.recv(length-len(out))
        if not part: raise EOFError('connection closed')
        out += part
    return out


def socks_connect(port, host, target_port, timeout):
    s=socket.create_connection(('127.0.0.1',port),timeout=timeout);s.settimeout(timeout)
    try:
        s.sendall(b'\x05\x01\x00')
        if recv_exact(s,2)!=b'\x05\x00':raise RuntimeError('SOCKS greeting refused')
        try: raw=socket.inet_pton(socket.AF_INET,host); address=b'\x01'+raw
        except OSError:
            name=host.encode('ascii');address=b'\x03'+bytes([len(name)])+name
        s.sendall(b'\x05\x01\x00'+address+struct.pack('!H',target_port))
        h=recv_exact(s,4)
        if h[0]!=5 or h[1]!=0: raise RuntimeError('SOCKS CONNECT failed: '+h.hex())
        if h[3]==1:recv_exact(s,6)
        elif h[3]==4:recv_exact(s,18)
        elif h[3]==3:recv_exact(s,recv_exact(s,1)[0]+2)
        else:raise RuntimeError('invalid SOCKS address')
        return s
    except: s.close();raise


def telegram(port,ip,timeout):
    started=time.monotonic()
    with socks_connect(port,ip,443,timeout) as s:
        nonce=os.urandom(16)
        body=struct.pack('<I',0xbe7e8ef1)+nonce # req_pq_multi
        packet=struct.pack('<QQI',0,int(time.time()*(2**32))&~3,len(body))+body
        s.sendall(b'\xef'+bytes([len(packet)//4])+packet) # abridged MTProto
        first=recv_exact(s,1)[0]
        size=(int.from_bytes(recv_exact(s,3),'little') if first==0x7f else first)*4
        if not 20<=size<=4096:raise RuntimeError('invalid MTProto frame length')
        response=recv_exact(s,size)
        if response[:8]!=b'\x00'*8 or struct.unpack('<I',response[20:24])[0]!=0x05162463 or response[24:40]!=nonce:
            raise RuntimeError('invalid resPQ or nonce mismatch')
    return {'status':'PASS','ms':round((time.monotonic()-started)*1000)}


def download(port,size,timeout):
    started=time.monotonic();host='speed.cloudflare.com'
    with socks_connect(port,host,443,timeout) as raw:
        with ssl.create_default_context().wrap_socket(raw,server_hostname=host) as s:
            s.sendall(f'GET /__down?bytes={size} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n'.encode())
            r=http.client.HTTPResponse(s);r.begin()
            if r.status!=200:raise RuntimeError('HTTP status '+str(r.status))
            data=r.read(size+1)
            if len(data)!=size:raise RuntimeError(f'incomplete payload {len(data)}/{size}')
    duration=time.monotonic()-started
    return {'status':'PASS','bytes':len(data),'seconds':round(duration,2),'bytes_per_second':round(len(data)/duration)}


def main():
    a=argparse.ArgumentParser();a.add_argument('--port',type=int,required=True);a.add_argument('--timeout',type=int,default=25);a.add_argument('--bytes',type=int,default=32768);args=a.parse_args()
    results={}
    for ip in ('149.154.167.51','149.154.175.53','149.154.167.91'):
        try:results['telegram_'+ip]=telegram(args.port,ip,args.timeout)
        except Exception as e:results['telegram_'+ip]={'status':'FAIL','error':type(e).__name__+': '+str(e)}
    try:results['download']=download(args.port,args.bytes,args.timeout)
    except Exception as e:results['download']={'status':'FAIL','error':type(e).__name__+': '+str(e)}
    print(json.dumps(results,indent=2))
    if any(x['status']!='PASS' for x in results.values()):raise SystemExit(1)

if __name__=='__main__':main()

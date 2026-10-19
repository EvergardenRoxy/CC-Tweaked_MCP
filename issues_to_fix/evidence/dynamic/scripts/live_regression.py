#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Regression against a disposable dev server. Requires .cct-repair-test-world; never prints credentials."""
import argparse,http.client,json,pathlib,socket,struct,time,subprocess,os

def until(fn,seconds=30):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(.25)
    raise AssertionError('condition did not become true before deadline')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--game',required=True,type=pathlib.Path);parser.add_argument('--output',required=True,type=pathlib.Path);parser.add_argument('--skip-dormant-check',action='store_true');args=parser.parse_args()
    game=args.game.resolve();assert (game/'.cct-repair-test-world').exists(),'not a marked disposable test world'
    home=game/'cct-live';until(lambda:(home/'endpoint.json').exists(),120)
    endpoint=json.loads((home/'endpoint.json').read_text());token=(home/'token.txt').read_text().strip()
    import urllib.parse
    parsed=urllib.parse.urlsplit(endpoint['url']);assert parsed.hostname=='127.0.0.1' and parsed.scheme=='http'
    import uuid
    sequence_event='regression_sequence_'+uuid.uuid4().hex[:12]
    report={'variant':game.parent.name,'checks':[],'dormant_checked':not args.skip_dormant_check,'sequence_event':sequence_event}
    import hashlib,datetime
    repo=pathlib.Path(__file__).resolve().parents[4]
    digest=hashlib.sha256()
    for folder in [repo/'mod/src/common/java',game.parent/'src/main/java']:
        for file in sorted(folder.rglob('*.java')):digest.update(str(file.relative_to(repo)).encode());digest.update(file.read_bytes())
    report['source_sha256']=digest.hexdigest();report['verified_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    props=dict(line.split('=',1) for line in (game/'server.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
    def exact(sock,n):
        value=b''
        while len(value)<n:
            part=sock.recv(n-len(value));assert part,'RCON connection ended';value+=part
        return value
    def rcon(command):
        with socket.create_connection(('127.0.0.1',int(props['rcon.port'])),timeout=10) as sock:
            def packet(rid,kind,text):
                body=struct.pack('<ii',rid,kind)+text.encode()+b'\0\0';sock.sendall(struct.pack('<i',len(body))+body)
            def read():
                n=struct.unpack('<i',exact(sock,4))[0];body=exact(sock,n);return struct.unpack('<ii',body[:8]),body[8:-2].decode(errors='replace')
            packet(1,3,props['rcon.password'])
            while True:
                (rid,kind),_=read();assert rid!=-1,'RCON authentication failed'
                if kind==2:break
            packet(2,2,command)
            (_,kind),text=read();return text
    def req(method,path,body=None,auth=True,host=None):
        connection=http.client.HTTPConnection('127.0.0.1',parsed.port,timeout=15)
        headers={'Authorization':'Bearer '+token} if auth else {}
        if host is not None:headers['Host']=host
        if body is not None:headers['Content-Type']='application/json'
        connection.request(method,path,None if body is None else json.dumps(body).encode(),headers)
        response=connection.getresponse();status=response.status;raw=response.read();connection.close()
        try:value=json.loads(raw)
        except ValueError:value={'body':raw[:200].decode(errors='replace')}
        return status,value
    def ok(method,path,body=None):
        status,value=req(method,path,body);assert status==200,(path,status,value);return value
    def check(name,fn):
        start=time.monotonic();fn();report['checks'].append({'name':name,'passed':True,'seconds':round(time.monotonic()-start,3)});print('PASS',game.parent.name,name,flush=True)
    base='/computers/0/'
    def initial():
        assert req('GET','/computers',auth=False)[0]==401
        assert req('GET','/computers',host='evil.invalid')[0]==403
        if not args.skip_dormant_check:assert not (home/'sessions').exists(),'dormant recorder created session files'
        if os.name=='posix':assert (home/'token.txt').stat().st_mode&0o777==0o600
        with socket.create_connection(('127.0.0.1',parsed.port),timeout=5) as sock:
            sock.sendall(b'GET /computers HTTP/1.0\r\n\r\n');assert b'403' in sock.recv(200).split(b'\r\n')[0]
    check('auth-host-permissions-dormant',initial)
    rcon('forceload add 1000 1000');time.sleep(2)
    answer=rcon('setblock 1000 101 1000 computercraft:computer_advanced{ComputerId:0,On:1b}')
    assert 'Unknown' not in answer,answer
    rcon('setblock 1001 101 1000 minecraft:chest')
    rcon('setblock 999 101 1000 computercraft:monitor_advanced')
    rcon('setblock 1000 101 1001 computercraft:wired_modem_full[peripheral=true]')
    rcon('setblock 1000 101 1002 minecraft:chest')
    rcon('setblock 1001 101 1001 computercraft:computer_advanced{ComputerId:1,On:1b}')
    until(lambda:any(c['id']==0 for c in ok('GET','/computers').get('computers',[])))
    attached=ok('POST',base+'attach',{'lease_seconds':120});assert attached['attached']
    status=ok('GET',base+'status');assert 'screen_hash' in status and 'trace_health' in status
    report['capabilities']={'monitors':status.get('monitors',[]),'files_write':attached.get('files_write')}
    def files_snapshots():
        ok('POST',base+'files',{'path':'regression/data.txt','text':'before'})
        ok('POST',base+'snapshot',{'op':'save','name':'regression'})
        ok('POST',base+'files',{'path':'regression/data.txt','text':'after'})
        assert 'regression/data.txt' in ok('POST',base+'snapshot',{'op':'diff','name':'regression'})['changed']
        assert ok('POST',base+'snapshot',{'op':'restore','name':'regression'})['identical']
        assert ok('GET',base+'files?op=read&path=regression/data.txt')['text']=='before'
        ok('POST',base+'snapshot',{'op':'save','name':'bad'})
        bad=home/'snapshots/computer-0/bad/manifest.json';bad.write_text('{')
        listed=ok('POST',base+'snapshot',{'op':'list'})
        assert 'bad' in listed['skipped']
        assert any(s['name']=='regression' for s in listed['snapshots'])
        bad.write_text('{"files":{"../escape":"x"},"dirs":[]}')
        assert req('POST',base+'snapshot',{'op':'restore','name':'bad'})[0]==400
        assert req('GET',base+'files?op=read&path=C:/Windows/win.ini')[0]==400
        assert req('GET',base+'trace?ticks=-')[0]==400
        assert req('GET',base+'status?after=9223372036854775807')[0]==200
    check('files-snapshot-roundtrip-and-path-regressions',files_snapshots)
    def body_limits():
        assert req('POST',base+'event',{'name':'oversized','args':['x'*(2*1024*1024)]})[0]==413
        with socket.create_connection(('127.0.0.1',parsed.port),timeout=8) as sock:
            sock.settimeout(8)
            header=f'POST {base}event HTTP/1.1\r\nHost: 127.0.0.1:{parsed.port}\r\nAuthorization: Bearer {token}\r\nContent-Length: 100\r\n\r\nx'
            sock.sendall(header.encode());assert sock.recv(200)==b'','slow body was not disconnected'
        assert req('GET','/computers')[0]==200
        # A rejected or ignored body must also be drained under a deadline, before response headers.
        # Two rejected partial requests used to occupy both workers inside HttpExchange.close().
        for host,authorisation,method,path,count in [
            ('127.0.0.1:'+str(parsed.port),'wrong','POST',base+'event',2),
            ('evil.invalid',token,'POST',base+'event',1),
            ('127.0.0.1:'+str(parsed.port),token,'GET','/computers',1)]:
            sockets=[]
            try:
                for _ in range(count):
                    sock=socket.create_connection(('127.0.0.1',parsed.port),timeout=8);sock.settimeout(8)
                    sock.sendall(f'{method} {path} HTTP/1.1\r\nHost: {host}\r\nAuthorization: Bearer {authorisation}\r\nContent-Length: 100\r\n\r\nx'.encode());sockets.append(sock)
                for sock in sockets:assert sock.recv(200)==b'', 'unconsumed slow body was not closed before response'
            finally:
                for sock in sockets:sock.close()
            assert req('GET','/computers')[0]==200
        report['rejected_or_ignored_slow_bodies_recovered']=True
    check('http-413-and-slow-body-recovery',body_limits)
    # Exercise Lua/peripheral call ids across a reboot, then draw enough changing full screens to rotate.
    simple="local p=peripheral.find('inventory') if p then p.size() end print('REGRESSION_READY')\n"
    ok('POST',base+'files',{'path':'startup.lua','text':simple})
    def boot():
        ok('POST',base+'power',{'action':'reboot'});time.sleep(3)
        until(lambda:any('REGRESSION_READY' in row for row in ok('GET',base+'status').get('lines',[])),15)
    boot();boot()
    def call_ids():
        calls=ok('GET',base+'trace?kind=peripheral_call&limit=100')['records']
        assert len(calls)>=2,'expected the Lua inventory calls'
        ids=[c['id'] for c in calls];assert len(set(ids))==len(ids),'call id reused after reboot'
        report['peripheral_calls']=len(ids)
        import re
        error_code,unknown=req('POST',base+'peripheral',{'name':'__missing__'})
        assert error_code==400,unknown
        names=set(re.findall(r'minecraft:chest_\d+',unknown.get('error','')))
        assert names,'wired peripheral was not discovered'
        for name in names:ok('POST',base+'peripheral',{'name':name,'method':'size','args':[]})
        report['wired_peripherals']=sorted(names)
        for source in status.get('monitors',[]):ok('GET',base+'screen?source='+source)
        # A Java-side getter exercises side and wired-map discovery without interfering with the VM.
        for side in ['left','right','top','bottom','front','back']:
            code,_=req('POST',base+'peripheral',{'name':side})
            assert code in (200,400),(side,code)
    check('peripheral-reflection-monitors-and-call-ids-across-reboot',call_ids)
    def mcp_client():
        repo=pathlib.Path(__file__).resolve().parents[4]
        launcher=repo/'cli/build/install/cct-run/bin/cct-run'
        requests=[{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-06-18','capabilities':{},'clientInfo':{'name':'repair-regression','version':'1'}}}]
        for rid,name,arguments in [(2,'cct_live_status',{'computer':0,'after':9223372036854775807}),(3,'cct_live_lua',{'computer':0,'code':'return 6*7','timeout':10})]:
            requests.append({'jsonrpc':'2.0','id':rid,'method':'tools/call','params':{'name':name,'arguments':arguments}})
        with open(game/'mcp-client.log','w') as log:
            process=subprocess.run([str(launcher),'mcp','--live',str(game),'--out',str(game/'mcp-runs')],input=''.join(json.dumps(r)+'\n' for r in requests),text=True,stdout=subprocess.PIPE,stderr=log,timeout=30)
        assert process.returncode==0
        responses={r['id']:r for r in map(json.loads,process.stdout.splitlines())}
        for rid in [2,3]:assert not responses[rid]['result']['isError'],responses[rid]
        result=json.loads(responses[3]['result']['content'][0]['text'])
        assert result.get('ok') and '42' in json.dumps(result),result
    check('installed-mcp-client-status-long-seq-and-lua',mcp_client)
    painter="""local n=0
while true do
 n=n+1
 term.setTextColor(2^(n%16)) term.setBackgroundColor(2^((n+1)%16))
 for y=1,19 do term.setCursorPos(1,y) term.write(string.rep(string.char(65+n%26),51)) end
 os.queueEvent('regression_sequence',n) os.pullEvent('regression_sequence')
 sleep(0.05)
end
"""
    painter=painter.replace('regression_sequence',sequence_event)
    ok('POST',base+'files',{'path':'startup.lua','text':painter});ok('POST',base+'power',{'action':'reboot'})
    time.sleep(4)
    ok('POST',base+'detach');time.sleep(1);ok('POST',base+'attach',{'lease_seconds':120})
    import tomllib
    recording=tomllib.loads((game/'config/cctlive-common.toml').read_text())['recorder']['enabled']
    if recording:
        history=ok('GET',base+'trace?kind=event&limit=5000')['records']
        counters=[r['args'][0] for r in history if r.get('name')==sequence_event]
        assert len(counters)>10 and all(b==a+1 for a,b in zip(counters,counters[1:])), 'recording handover lost or duplicated events'
        report['history_handover_events']=len(counters)
    rotation_checkpoint=ok('GET',base+'status')['last_seq']
    def rotation():
        # Existing test worlds may already contain an older painter and earlier rotations. Wait until
        # this invocation's checkpoint has rotated out; off-recording detach gaps are intentional.
        until(lambda:ok('GET',base+'status').get('first_seq',1)>rotation_checkpoint,55)
        data=ok('GET',base+'trace?kind=event&limit=5000')
        values=[r['args'][0] for r in data['records'] if r.get('name')==sequence_event]
        assert len(values)>10
        assert all(b==a+1 for a,b in zip(values,values[1:])), 'history/live handover lost or duplicated sequence events'
        trace=pathlib.Path(data['file']);assert trace.stat().st_size+trace.with_name(trace.stem+'.old.jsonl').stat().st_size<=1024*1024
        summary=ok('GET',base+'trace?summary=1');assert summary['matched']>0
        current=ok('GET',base+'status');ok('GET',base+'screen?tick='+str(current['tick']))
        if status.get('monitors'):ok('GET',base+'screen?source='+status['monitors'][0]+'&tick='+str(current['tick']))
        report['rotations_first_seq']=current['first_seq'];report['retained_sequence_events']=len(values)
    check('rotation-bounded-files-replay-and-recording-handover',rotation)
    ok('POST',base+'event',{'name':'terminate','args':[]})
    def unloaded():
        rcon('forceload remove 1000 1000')
        until(lambda:not ok('GET','/computers').get('computers'),40)
        records=ok('GET',base+'trace?kind=detach&limit=50')['records']
        assert any(r.get('reason')=='unavailable' for r in records),records
        rcon('forceload add 1000 1000');until(lambda:ok('GET','/computers').get('computers'),15)
        assert not next(c for c in ok('GET','/computers')['computers'] if c['id']==0)['attached'],'unload must not silently reattach AI control'
    check('chunk-unload-is-unavailable-without-automatic-control',unloaded)
    def lease():
        ok('POST',base+'attach',{'lease_seconds':30})
        print('WAIT',game.parent.name,'real lease expiry (31 seconds)',flush=True);time.sleep(31)
        assert not next(c for c in ok('GET','/computers')['computers'] if c['id']==0)['attached']
    check('lease-expiry',lease)
    ok('POST',base+'attach',{'lease_seconds':120});ok('POST',base+'detach')
    rcon('stop')
    until(lambda:not(home/'endpoint.json').exists(),30)
    report['endpoint_cleanup']=True
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n')
    print('PASS',game.parent.name,'endpoint cleanup; report',args.output,flush=True)

if __name__=='__main__':main()

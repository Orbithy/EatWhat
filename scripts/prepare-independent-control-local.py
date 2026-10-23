#!/usr/bin/env python3
"""Prepare NEW loopback-only controller PostgreSQL/Redis without changing the
running controller, business containers, old tunnel, NFS, or database contents.
Root/Linux only; no password is accepted in argv or printed. Reruns verify the
same private manifest instead of replacing an existing installation.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import stat
import subprocess
import sys
import time

ROOT=Path('/opt/eatwhat-control-local')
PG_PORT=35432
REDIS_PORT=26379


def require(value):
    if not value:raise RuntimeError('LOCAL_CONTROL_PRECONDITION_FAILED')


def run(args,timeout=30):
    r=subprocess.run(args,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    require(r.returncode==0)
    return r.stdout


def private(path,data,uid=0,gid=0):
    require(not path.is_symlink() and not path.exists())
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'wb') as f:
        os.fchown(f.fileno(),uid,gid);f.write(data);f.flush();os.fsync(f.fileno())


def compose(pg_image,redis_image):
    return {'name':'eatwhat-control-local','services':{
        'postgres':{'image':pg_image,'container_name':'eatwhat-control-local-postgres','network_mode':'host',
            'restart':'unless-stopped','environment':{'POSTGRES_USER':'postgres','POSTGRES_DB':'eatwhat_control',
                'POSTGRES_PASSWORD_FILE':'/run/secrets/admin-password','PGPORT':str(PG_PORT)},
            'command':['postgres','-c','listen_addresses=127.0.0.1','-c','port='+str(PG_PORT)],
            'volumes':[str(ROOT/'pg-data')+':/var/lib/postgresql/data',str(ROOT/'config/pg-admin.password')+':/run/secrets/admin-password:ro']},
        'redis':{'image':redis_image,'container_name':'eatwhat-control-local-redis','network_mode':'host',
            'restart':'unless-stopped','user':'10001:10001','command':['redis-server','/run/secrets/redis.conf'],
            'cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],
            'volumes':[str(ROOT/'redis-data')+':/data',str(ROOT/'config/redis.conf')+':/run/secrets/redis.conf:ro']}}}


def redis_ready(password):
    parts=[b'AUTH',password.encode()]
    command=b'*2\r\n'+b''.join(b'$'+str(len(p)).encode()+b'\r\n'+p+b'\r\n'for p in parts)
    with socket.create_connection(('127.0.0.1',REDIS_PORT),timeout=2)as s:
        s.sendall(command);require(s.recv(256).startswith(b'+OK'))
        s.sendall(b'*1\r\n$4\r\nPING\r\n');require(s.recv(256).startswith(b'+PONG'))


def main():
    require(os.geteuid()==0 and sys.platform=='linux' and sys.argv[1:]==['--prepare'])
    require(not ROOT.is_symlink())
    if ROOT.exists():
        manifest=ROOT/'prepared.json';require(manifest.is_file() and not manifest.is_symlink())
        d=json.loads(manifest.read_text());require(d['protocol']=='eatwhat-independent-local-preparation-v1')
        require(hashlib.sha256((ROOT/'compose.json').read_bytes()).hexdigest()==d['composeSha256'])
        require(stat.S_IMODE((ROOT/'config').stat().st_mode)==0o700)
        print(json.dumps({'state':'ALREADY_PREPARED','controllerChanged':False,'businessChanged':False}));return
    for port in [PG_PORT,REDIS_PORT]:
        with socket.socket()as s:
            try:s.bind(('127.0.0.1',port))
            except OSError:raise RuntimeError('LOCAL_CONTROL_PORT_ALREADY_USED')
    ids=run(['docker','ps','-q']).decode().split();require(ids)
    containers=json.loads(run(['docker','inspect',*ids]))
    pg=next(c for c in containers if c['Name']=='/eatwhat-site-16343d72-postgres-1')
    redis=next(c for c in containers if c['Name']=='/eatwhat-site-16343d72-redis-1')
    for c in [pg,redis]:require(c['State']['Running'])
    ROOT.mkdir(mode=0o700);(ROOT/'config').mkdir(mode=0o700)
    (ROOT/'pg-data').mkdir(mode=0o700);(ROOT/'redis-data').mkdir(mode=0o700);os.chown(ROOT/'redis-data',10001,10001)
    pg_password=secrets.token_urlsafe(36);redis_password=secrets.token_urlsafe(36)
    private(ROOT/'config/pg-admin.password',(pg_password+'\n').encode())
    private(ROOT/'config/redis.password',(redis_password+'\n').encode())
    private(ROOT/'config/redis.conf',(
        'bind 127.0.0.1\nport '+str(REDIS_PORT)+'\nprotected-mode yes\nrequirepass '+redis_password
        +'\nappendonly yes\nappendfsync everysec\ndir /data\n').encode(),10001,10001)
    data=(json.dumps(compose(pg['Image'],redis['Image']),indent=2)+'\n').encode()
    private(ROOT/'compose.json',data)
    run(['docker','compose','-f',str(ROOT/'compose.json'),'up','-d'],timeout=90)
    deadline=time.monotonic()+75
    while True:
        try:
            run(['docker','exec','eatwhat-control-local-postgres','pg_isready','-h','127.0.0.1','-p',str(PG_PORT),'-U','postgres','-d','eatwhat_control'])
            redis_ready(redis_password);break
        except Exception:
            if time.monotonic()>=deadline:raise
            time.sleep(1)
    report={'protocol':'eatwhat-independent-local-preparation-v1','state':'PREPARED_NOT_CONNECTED',
        'composeSha256':hashlib.sha256(data).hexdigest(),'postgresImage':pg['Image'],'redisImage':redis['Image'],
        'postgresPort':PG_PORT,'redisPort':REDIS_PORT,'bind':'127.0.0.1',
        'postgresReady':True,'redisAuthenticatedPing':True,'controllerChanged':False,'businessChanged':False,
        'newManagementAccountsCreated':False,'existingDatabasesCopied':False,'publicListenerCreated':False}
    private(ROOT/'prepared.json',(json.dumps(report,indent=2)+'\n').encode())
    print(json.dumps(report))


if __name__=='__main__':
    try:main()
    except Exception as error:
        print(json.dumps({'state':'PREPARATION_FAILED','errorType':type(error).__name__,'existingServicesChanged':False}))
        sys.exit(1)

"""SOURCE-only, once-only restricted authentication role worker.

Privileged credentials are discovered/snapshotted by the local Java CLI; they
are never returned to the manager. An uncertain dispatch permits observation
of the retained task only, never another CREATE or password reset.
"""
import fcntl, hashlib, json, os, re, stat, subprocess, sys, tempfile, time, types, uuid, zipfile
from pathlib import Path

PROTOCOL='eatwhat-controller-bootstrap-member-auth-v1'
SOURCE_PROTOCOL='eatwhat-authority-auth-source-v1'
PERMISSION='CREATE_LIMITED_BUSINESS_AUTH_ROLE'
BASE=Path('/var/lib/eatwhat-controller-bootstrap-member-auth')
CONTROL=Path('/opt/eatwhat-control')
ROOT_UID=ROOT_GID=0
MAX_INPUT=262144
class Refusal(Exception):
    def __init__(self,code='BOOTSTRAP_MEMBER_AUTH_INPUT_INVALID'):self.code=code;super().__init__(code)
def require(ok,code='BOOTSTRAP_MEMBER_AUTH_INPUT_INVALID'):
    if not ok:raise Refusal(code)
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def digest(raw):return hashlib.sha256(raw).hexdigest()
def unique(rows):
    out={}
    for key,value in rows:require(key not in out);out[key]=value
    return out
def decode(raw):return json.loads(raw,object_pairs_hook=unique)
def identifier(value):
    try:require(str(uuid.UUID(value))==value and uuid.UUID(value).int!=0)
    except (ValueError,TypeError,AttributeError):raise Refusal()
    return value
def phase(value):return str(uuid.UUID(bytes=hashlib.md5((identifier(value)+':MEMBER_AUTH_ROLE').encode()).digest(),version=3))
def sha(value):require(isinstance(value,str) and re.fullmatch('[a-f0-9]{64}',value));return value
def directory(path):
    require(path.is_dir() and all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_MEMBER_AUTH_UNSAFE_DIRECTORY')
    info=path.stat();require(info.st_uid==ROOT_UID and info.st_gid==ROOT_GID and stat.S_IMODE(info.st_mode)==0o700,'BOOTSTRAP_MEMBER_AUTH_UNSAFE_DIRECTORY')
def private(path,maximum=MAX_INPUT):
    require(path.is_file() and all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_MEMBER_AUTH_UNSAFE_FILE')
    info=path.stat();require(info.st_uid==ROOT_UID and info.st_gid==ROOT_GID and stat.S_IMODE(info.st_mode)==0o600 and 0<info.st_size<=maximum,'BOOTSTRAP_MEMBER_AUTH_UNSAFE_FILE')
    with path.open('rb') as stream:raw=stream.read(maximum+1)
    require(len(raw)<=maximum,'BOOTSTRAP_MEMBER_AUTH_UNSAFE_FILE');return raw
def atomic(path,value):
    require(not path.is_symlink());raw=value if isinstance(value,bytes) else canonical(value)
    fd,name=tempfile.mkstemp(prefix='.auth-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as out:os.fchmod(out.fileno(),0o600);out.write(raw);out.flush();os.fsync(out.fileno())
        os.replace(name,path);fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:os.fsync(fd)
        finally:os.close(fd)
    finally:Path(name).unlink(missing_ok=True)
def lock(create=False):
    if create and not BASE.exists():BASE.mkdir(mode=0o700)
    directory(BASE);path=BASE/'host.lock'
    if create and not path.exists():
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600);os.write(fd,b'lock\n');os.close(fd)
    private(path);stream=path.open('r+')
    try:fcntl.lockf(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except Exception:stream.close();raise Refusal('BOOTSTRAP_MEMBER_AUTH_HOST_BUSY')
    return stream
def task(data):return BASE/data['id']
def reservation(data):return BASE/('target-'+data['serverId']+'.json')
def header(data):return {k:v for k,v in data.items() if k!='agent'}
def inspection_input(data):return {k:v for k,v in data.items() if k not in ['agent','expectedProof','credentialBinding','agentSha256']}
def fields(data,operation='CREATE'):return {'protocol':PROTOCOL,'id':data['id'],'serverId':data['serverId'],'binding':digest(canonical(header(data))),'operation':operation}
def validate(data,mode='header'):
    keys={'id','bootstrapId','serverId','role','sourceRequest','sourceCaptureBinding','sourceAgent','sourceAgentSha256','approvalDigest','permission'}
    if mode!='inspect':keys|={'expectedProof','credentialBinding','agentSha256'}
    if mode=='prepare':keys.add('agent')
    require(isinstance(data,dict) and set(data)==keys)
    for key in ['id','bootstrapId','serverId']:identifier(data[key])
    require(data['id']==phase(data['bootstrapId']) and data['permission']==PERMISSION,'BOOTSTRAP_MEMBER_AUTH_SCOPE_REQUIRED')
    role=data['role'];require(isinstance(role,dict) and role.get('id')==data['id'] and role.get('bootstrapId')==data['bootstrapId'] and role.get('targetServerId')==data['serverId'])
    for key in ['sourceCaptureBinding','sourceAgentSha256','approvalDigest','expectedProof','credentialBinding','agentSha256']:
        if key in data:sha(data[key])
    require(isinstance(data['sourceAgent'],str) and len(data['sourceAgent'])<=32768 and digest(data['sourceAgent'].encode())==data['sourceAgentSha256'])
    if mode=='prepare':require(isinstance(data['agent'],str) and digest(data['agent'].encode())==data['agentSha256'])
    require(len(canonical(data))<=MAX_INPUT)
def collector(data):
    module=types.ModuleType('original_member_auth_source');exec(compile(data['sourceAgent'],'<original-source>','exec'),module.__dict__)
    module.validate(data['sourceRequest']);return module
def artifact(data):
    source=collector(data);path=source.artifact(data['sourceRequest']['probeArtifactSha256'])
    with zipfile.ZipFile(path) as jar:
        require(jar.read('META-INF/eatwhat-authority-auth-source-v1')==b'eatwhat-authority-auth-source-v1\n','BOOTSTRAP_MEMBER_AUTH_ARTIFACT_REQUIRED')
    return path
def live(data):
    require(not os.path.ismount(CONTROL/'state'),'BOOTSTRAP_MEMBER_AUTH_PHYSICAL_SOURCE_REQUIRED')
    seen=collector(data).collect(data['sourceRequest']);require(seen.get('captureBinding')==data['sourceCaptureBinding'],'BOOTSTRAP_MEMBER_AUTH_SOURCE_CHANGED')
def java(data,action):
    jar=artifact(data)
    argv=['/usr/bin/java','-Xms32m','-Xmx192m','-Dloader.home=/','-Dloader.config.location=file:/dev/null','-Dloader.path=',
        '-Dloader.main=you.v50to.eatwhat.control.bootstrap.AuthorityAuthRoleSource','-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher',action]
    payload=canonical({'role':data['role'],'source':data['sourceRequest']['expected']}) if action=='inspect' else b''
    if action!='inspect':argv.append(data['id'])
    p=subprocess.run(argv,input=payload,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=90,cwd='/',
        env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','HOME':'/nonexistent'})
    require(p.returncode==0 and 0<len(p.stdout)<=8192,'BOOTSTRAP_MEMBER_AUTH_SQL_UNCONFIRMED');seen=decode(p.stdout)
    require(seen.get('sourceProtocol',seen.get('protocol'))==SOURCE_PROTOCOL and seen.get('id')==data['id'],'BOOTSTRAP_MEMBER_AUTH_SQL_UNCONFIRMED')
    # Closed output allowlist: migration passwords, source TLS keys and raw
    # configuration are never propagated even if a CLI response changes.
    require(set(seen)<={'protocol','sourceProtocol','id','state','effectsStarted','credentialBinding','proof','privateCredentialSha256',
        'targetServerId','role','policySha256','validUntil','userOrSessionDataChanged','membershipChanged','existingRoleAdopted'},'BOOTSTRAP_MEMBER_AUTH_SQL_UNCONFIRMED')
    if action in ['create','status']:
        require(seen.get('state')=='ROLE_READY' and seen.get('targetServerId')==data['serverId']
            and seen.get('role')=='eatwhat_auth_'+data['serverId'].replace('-','') and seen.get('userOrSessionDataChanged') is False
            and seen.get('membershipChanged') is False and seen.get('existingRoleAdopted') is False,'BOOTSTRAP_MEMBER_AUTH_SQL_UNCONFIRMED');sha(seen.get('policySha256'))
    return seen
def inspect(data):
    validate(data,'inspect');live(data);seen=java(data,'inspect')
    require(seen.get('state')=='INSPECTED' and seen.get('effectsStarted') is False);sha(seen.get('credentialBinding'));sha(seen.get('proof'))
    return {**fields(data,'INSPECT'),'state':'INSPECTED','effectsStarted':False,'credentialBinding':seen['credentialBinding'],
        'proof':digest(canonical({'input':data,'sourceProof':seen['proof'],'credentialBinding':seen['credentialBinding']}))}
def sql_input(data):return {'role':data['role'],'source':data['sourceRequest']['expected'],'credentialBinding':data['credentialBinding']}
def original(data):
    validate(data);directory(BASE);directory(task(data))
    require(decode(private(reservation(data)))=={'id':data['id'],'binding':fields(data)['binding']},'BOOTSTRAP_MEMBER_AUTH_TARGET_RESERVED')
    require(decode(private(task(data)/'input.json'))==data and digest(private(task(data)/'agent.py'))==data['agentSha256']
        and decode(private(task(data)/'sql-input.json',65536))==sql_input(data),'BOOTSTRAP_MEMBER_AUTH_ORIGINAL_CHANGED')
def captured(data):
    seen=java(data,'capture-status');require(seen.get('state')=='SOURCE_CREDENTIALS_CAPTURED' and seen.get('credentialBinding')==data['credentialBinding']);sha(seen.get('privateCredentialSha256'))
    return {**fields(data,'PREPARE'),'state':'PREPARED','credentialBinding':seen['credentialBinding'],'privateCredentialSha256':seen['privateCredentialSha256']}
def prepare_status(data):
    validate(data)
    if not BASE.exists() and not BASE.is_symlink():return {**fields(data,'PREPARE'),'state':'NOT_PREPARED'}
    with lock():original(data);return captured(data)
def prepare(data):
    validate(data,'prepare');h=header(data)
    if BASE.exists() and (task(h).exists() or reservation(h).exists()):return prepare_status(h)
    seen=inspect(inspection_input(h));require(seen['proof']==h['expectedProof'] and seen['credentialBinding']==h['credentialBinding'],'BOOTSTRAP_MEMBER_AUTH_APPROVAL_EXPIRED')
    with lock(True):
        require(not task(h).exists() and not task(h).is_symlink() and not reservation(h).exists() and not reservation(h).is_symlink(),'BOOTSTRAP_MEMBER_AUTH_RETAINED')
        # Reservation precedes all private capture. An incomplete write remains
        # retained and cannot be overwritten/replaced with a fresh task UUID.
        atomic(reservation(h),{'id':h['id'],'binding':fields(h)['binding']});task(h).mkdir(mode=0o700)
        atomic(task(h)/'input.json',h);atomic(task(h)/'agent.py',data['agent'].encode());atomic(task(h)/'sql-input.json',sql_input(h))
        atomic(task(h)/'capture-claimed.json',{'id':h['id'],'claimedAt':time.time()});java(h,'capture');return captured(h)
def receipt(data):
    path=task(data)/'receipt.json'
    if not path.exists() and not path.is_symlink():return None
    seen=decode(private(path));require(all(seen.get(k)==v for k,v in fields(data).items()),'BOOTSTRAP_MEMBER_AUTH_RECEIPT_CHANGED');return seen
def service_state(data):
    p=subprocess.run(['/usr/bin/systemctl','show','eatwhat-member-auth-'+data['id'],'--property=ActiveState','--value'],stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=8,check=True,env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8'})
    require(len(p.stdout)<128);return p.stdout.decode().strip()
def status(data):
    original(data);saved=receipt(data)
    if saved is None:return {**fields(data),'state':'NOT_STARTED','effectsStarted':False}
    if saved['state']=='FAILED':return saved
    if saved['state']!='ROLE_READY':
        try:
            if service_state(data) not in ['inactive','failed']:return saved
        except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_MEMBER_AUTH_WORKER_UNCONFIRMED'}
    try:
        with lock():
            original(data);seen=java(data,'status')
            return {**saved,'state':'ROLE_READY','policySha256':seen['policySha256'],'role':seen['role'],'validUntil':seen['validUntil'],
                'membershipChanged':False,'memberConfigured':False,'joinAuthorized':False}
    except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_MEMBER_AUTH_OUTCOME_UNCONFIRMED'}
def dispatch(data):
    original(data)
    with lock():
        saved=receipt(data)
        if saved is not None:return saved
        seen=inspect(inspection_input(data));require(seen['proof']==data['expectedProof'] and seen['credentialBinding']==data['credentialBinding'],'BOOTSTRAP_MEMBER_AUTH_APPROVAL_EXPIRED');captured(data)
        saved={**fields(data),'state':'ACCEPTED','effectsStarted':False,'acceptedAt':time.time()};atomic(task(data)/'receipt.json',saved)
    try:
        subprocess.run(['/usr/bin/systemd-run','--quiet','--no-block','--unit=eatwhat-member-auth-'+data['id'],'--property=Type=exec','--property=UMask=0077',
            '--property=RuntimeMaxSec=180','--property=KillMode=control-group','--property=StandardOutput=null','--property=StandardError=null',
            '/usr/bin/python3','-I',str(task(data)/'agent.py'),'run',data['id']],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,
            timeout=10,check=True,cwd='/',env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8'})
    except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_MEMBER_AUTH_DISPATCH_UNCONFIRMED'}
    return saved
def perform(id):
    data=decode(private(BASE/identifier(id)/'input.json'));original(data)
    with lock():
        saved=receipt(data);require(saved is not None and saved['state']=='ACCEPTED','BOOTSTRAP_MEMBER_AUTH_ALREADY_DISPATCHED')
        try:
            live(data);captured(data)
            saved.update(state='RUNNING',effectsStarted=True);atomic(task(data)/'receipt.json',saved)
            seen=java(data,'create');saved.update(state='ROLE_READY',policySha256=seen['policySha256'],role=seen['role'],validUntil=seen['validUntil'],
                membershipChanged=False,memberConfigured=False,joinAuthorized=False)
        except Refusal as failure:saved.update(state='OUTCOME_UNKNOWN' if saved['effectsStarted'] else 'FAILED',code=failure.code)
        except Exception:saved.update(state='OUTCOME_UNKNOWN' if saved['effectsStarted'] else 'FAILED',code='BOOTSTRAP_MEMBER_AUTH_SQL_UNCONFIRMED')
        saved['finishedAt']=time.time();atomic(task(data)/'receipt.json',saved)
def main():
    try:
        require(os.geteuid()==0 and sys.platform=='linux' and len(sys.argv)>=2)
        if sys.argv[1]=='run':require(len(sys.argv)==3);perform(sys.argv[2]);return
        require(len(sys.argv)==2 and sys.argv[1] in ['inspect','prepare','prepare-status','dispatch','status'])
        raw=sys.stdin.buffer.read(MAX_INPUT+1);require(len(raw)<=MAX_INPUT);data=decode(raw)
        result={'inspect':inspect,'prepare':prepare,'prepare-status':prepare_status,'dispatch':dispatch,'status':status}[sys.argv[1]](data)
        print(json.dumps(result,separators=(',',':')))
    except Refusal as failure:print(json.dumps({'protocol':PROTOCOL,'code':failure.code}));sys.exit(1)
    except Exception:print(json.dumps({'protocol':PROTOCOL,'code':'BOOTSTRAP_MEMBER_AUTH_UNCONFIRMED'}));sys.exit(1)
if __name__=='__main__':main()

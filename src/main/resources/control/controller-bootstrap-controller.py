"""Durable target controller transfer/STAGE/VERIFY. No management write route.

Transfers append only after a client proves the exact retained prefix. STAGE and
VERIFY are independently sealed once-only workers; unknown workers are observed,
never replayed. Source identity in the header still needs the real manager guard.
"""
import base64
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import time
import types
import urllib.error
import urllib.request
import uuid

POLICY_SOURCE='__EATWHAT_CONTROLLER_CONTROLLER_POLICY_BASE64__'
_POLICY=_DEPENDENCY=None
_CTX={}
BASE=Path('/var/lib/eatwhat-controller-bootstrap-controller')
DEPENDENCIES=Path('/var/lib/eatwhat-controller-bootstrap-dependency')
CONTROL=Path('/opt/eatwhat-control')
SYSTEM=Path('/etc/systemd/system')
WEB=Path('/srv/eatwhat-control-releases')
WEB_LINK=Path('/srv/eatwhat-control')
UNIT='eatwhat-control.service'
ROOT_UID=ROOT_GID=0
MAX_INPUT=2*1024*1024
MAX_CHUNK=512*1024
PROTOCOL='eatwhat-controller-bootstrap-controller-v1'
OPS={'STAGE':'STAGED','VERIFY':'VERIFIED'}

class Refusal(Exception):
    def __init__(self,code):self.code=code;super().__init__(code)
def require(value,code='BOOTSTRAP_CONTROLLER_PRECONDITION_FAILED'):
    if not value:raise Refusal(code)
def canonical(v):return json.dumps(v,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def digest(v):return hashlib.sha256(v).hexdigest()
def file_hash(path):
    value=hashlib.sha256()
    with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW),'rb') as stream:
        while block:=stream.read(65536):value.update(block)
    return value.hexdigest()
def policy():
    global _POLICY
    if _POLICY is None:
        _POLICY=types.ModuleType('controller_policy');exec(compile(base64.b64decode(POLICY_SOURCE),'<controller-policy>','exec'),_POLICY.__dict__)
    return _POLICY
def directory(path,uid,mode,gid=None):
    require(path.is_dir() and all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_CONTROLLER_UNSAFE_DIRECTORY')
    st=path.stat();require(st.st_uid==uid and stat.S_IMODE(st.st_mode)==mode and (gid is None or st.st_gid==gid),'BOOTSTRAP_CONTROLLER_UNSAFE_DIRECTORY')
def private(path,maximum=MAX_INPUT):
    require(path.is_file() and all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_CONTROLLER_UNSAFE_FILE')
    st=path.stat();require(st.st_uid==ROOT_UID and st.st_gid==ROOT_GID and stat.S_IMODE(st.st_mode)==0o600 and st.st_size<=maximum,'BOOTSTRAP_CONTROLLER_UNSAFE_FILE')
    return path.read_bytes()
def absent(path):require(not path.exists() and not path.is_symlink(),'BOOTSTRAP_CONTROLLER_CONFIGURATION_RETAINED')
def create_directory(path,uid,gid,mode):
    # Do not require a new helper from an older, sealed dependency executor.
    require(all(not p.is_symlink() for p in path.parents) and path.parent.is_dir(),
        'BOOTSTRAP_CONTROLLER_UNSAFE_DIRECTORY')
    parent=path.parent.stat();require(parent.st_uid in [ROOT_UID,uid] and parent.st_mode&0o022==0,
        'BOOTSTRAP_CONTROLLER_UNSAFE_DIRECTORY')
    absent(path);path.mkdir(mode=0o700);fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:os.fchown(fd,uid,gid);os.fchmod(fd,mode)
    finally:os.close(fd)
    directory(path,uid,mode,gid)
def header(data):return {k:v for k,v in data.items() if k!='agent'}
def task(data):return BASE/data['id']
def fields(data,op):return {'protocol':PROTOCOL,'id':data['verifyId'] if op=='VERIFY' else data['id'],'configurationId':data['id'],
    'binding':digest(canonical(header(data))),'operation':op}
def validate(data,mode='header'):
    keys={'id','verifyId','dependencyId','dependencyBinding','dependencyAgentSha256','approvalDigest','sourceAuthority','bundle'}
    if mode!='inspect':keys|={'expectedProof','agentSha256'}
    if mode=='prepare':keys.add('agent')
    require(isinstance(data,dict) and set(data)==keys,'BOOTSTRAP_CONTROLLER_INPUT_INVALID')
    p=policy();ids=[p.identifier(data[k]) for k in ['id','verifyId','dependencyId']];require(len(set(ids))==3)
    for key in ['dependencyBinding','dependencyAgentSha256','approvalDigest','expectedProof','agentSha256']:
        if key in data:p.sha(data[key])
    require(len(canonical(data))<=MAX_INPUT,'BOOTSTRAP_CONTROLLER_INPUT_TOO_LARGE')
    if mode=='prepare':require(isinstance(data['agent'],str) and digest(data['agent'].encode())==data['agentSha256'],'BOOTSTRAP_CONTROLLER_AGENT_CHANGED')
    return data
def context(data,refresh=False):
    global _DEPENDENCY
    key=(data['dependencyId'],data['dependencyBinding'],data['dependencyAgentSha256'],data['approvalDigest'])
    if refresh:_CTX.pop(key,None)
    if key not in _CTX:
        path=DEPENDENCIES/data['dependencyId'];directory(path,ROOT_UID,0o700,ROOT_GID)
        previous=json.loads(private(path/'input.json'));source=private(path/'agent.py')
        require(previous['id']==data['dependencyId'] and previous['role']=='TARGET' and previous['approvalDigest']==data['approvalDigest']
            and digest(canonical(previous))==data['dependencyBinding'] and digest(source)==previous['agentSha256']==data['dependencyAgentSha256'],
            'BOOTSTRAP_CONTROLLER_DEPENDENCY_BINDING_CHANGED')
        if _DEPENDENCY is None:
            _DEPENDENCY=types.ModuleType('original_dependency');exec(compile(source,'<original-dependency>','exec'),_DEPENDENCY.__dict__)
        d=_DEPENDENCY;require(d.status(previous,'CONNECT')['state']=='VERIFIED','BOOTSTRAP_CONTROLLER_DEPENDENCY_NOT_VERIFIED')
        state,runtime,state_header,request=d.context(previous,True)
        require(request['role']=='TARGET','BOOTSTRAP_CONTROLLER_TARGET_REQUIRED')
        _CTX[key]=(d,state,runtime,previous,state_header,request)
    result=_CTX[key]
    policy().validate_bundle(data['bundle'],result[5],result[3]['forwards'],data['sourceAuthority'])
    return result
def run(data,args,timeout=15,raw=None):return context(data)[2].run(args,timeout=timeout,stdin=raw)
def config(data,path):return context(data)[1].config(path)
def atomic(data,path,value):context(data)[2].atomic(path,value)
def rendered(data):return policy().validate_bundle(data['bundle'],context(data)[5],context(data)[3]['forwards'],data['sourceAuthority'])
def fresh(data):
    validate(data,'inspect');d,s,r,dep,prior,request=context(data,True)
    d.directory(CONTROL,prior['ownerUid'],0o700,prior['ownerGid'])
    for path in [CONTROL/'acceptance',CONTROL/'config',SYSTEM/UNIT,WEB,WEB_LINK]:absent(path)
    for parent in [SYSTEM,WEB.parent]:
        require(parent.is_dir() and all(not p.is_symlink() for p in [parent,*parent.parents])
            and parent.stat().st_uid==ROOT_UID and parent.stat().st_mode&0o022==0,'BOOTSTRAP_CONTROLLER_UNSAFE_DIRECTORY')
    require(run(data,['systemctl','show',UNIT,'--property=LoadState','--value']).strip()==b'not-found','BOOTSTRAP_CONTROLLER_SERVICE_RETAINED')
    require(all(v['address'].rsplit(':',1)[-1]!='18081' for v in d.listeners(dep)),'BOOTSTRAP_CONTROLLER_PORT_IN_USE')
    # Exactly the previous NFS gate is allowed. No new unit/drop-in may silently
    # add an EnvironmentFile, change ExecStart or remove its mount dependency.
    folder=SYSTEM/(UNIT+'.d')
    require(folder.is_dir() and not folder.is_symlink() and folder.stat().st_uid==ROOT_UID
        and folder.stat().st_mode&0o022==0,'BOOTSTRAP_CONTROLLER_SERVICE_OVERRIDE')
    require({p.name for p in folder.iterdir()}=={'20-bootstrap-state.conf'},'BOOTSTRAP_CONTROLLER_SERVICE_OVERRIDE')
    files={str(path):digest(s.config(path)) for path in [folder/'20-bootstrap-state.conf',Path('/etc/eatwhat-control-state-mount-check.py')]}
    facts={'ownerUid':prior['ownerUid'],'ownerGid':prior['ownerGid'],'previousNfsGate':files,
        'privateFilesSha256':{k:digest(v) for k,v in rendered(data)['privateFiles'].items()}}
    return {'protocol':PROTOCOL,'id':data['id'],'proof':digest(canonical({'input':data,'facts':facts})),
        'effectsStarted':False,'privateFacts':facts}
def inspect_input(data):return {k:v for k,v in data.items() if k not in ['agent','expectedProof','agentSha256']}
def lock(data,create=False):
    if create and not BASE.exists():BASE.mkdir(mode=0o700)
    directory(BASE,ROOT_UID,0o700,ROOT_GID);path=BASE/'host.lock'
    if create and not path.exists():
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600);os.close(fd)
    private(path);stream=path.open('r+')
    try:fcntl.lockf(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except Exception:stream.close();raise Refusal('BOOTSTRAP_CONTROLLER_HOST_BUSY')
    return stream
def original(data,dependencies=True):
    validate(data)
    if dependencies:context(data,True)
    directory(BASE,ROOT_UID,0o700,ROOT_GID)
    reserve=json.loads(private(BASE/'reservation.json'));require(reserve=={'id':data['id'],'binding':fields(data,'STAGE')['binding']},'BOOTSTRAP_CONTROLLER_HOST_RESERVED')
    directory(task(data),ROOT_UID,0o700,ROOT_GID)
    require(json.loads(private(task(data)/'input.json'))==data and digest(private(task(data)/'agent.py'))==data['agentSha256'],
        'BOOTSTRAP_CONTROLLER_AGENT_CHANGED')
def prepare_status(data):
    # Preparation is an independent durable effect. A lost reply must not make
    # the manager invoke prepare again. This observes local owned input/code,
    # not NFS/dependency health or whether an application is running.
    validate(data)
    result=fields(data,'PREPARE')
    if not BASE.exists() and not BASE.is_symlink():
        return {**result,'state':'NOT_PREPARED','preparationEffectsStarted':False,'applicationEffectsStarted':False}
    directory(BASE,ROOT_UID,0o700,ROOT_GID)
    reserve=BASE/'reservation.json'
    if not reserve.exists() and not reserve.is_symlink():
        empty=all(path.name=='host.lock' for path in BASE.iterdir())
        return {**result,'state':'NOT_PREPARED' if empty else 'OUTCOME_UNKNOWN',
            'preparationEffectsStarted':not empty,'applicationEffectsStarted':False if empty else None}
    original(data,False)
    # A receipt means the application stage may have started; never equate a
    # prepared transfer directory with a successfully installed controller.
    application=any((task(data)/name).exists() or (task(data)/name).is_symlink()
        for name in ['stage-receipt.json','verify-receipt.json'])
    return {**result,'state':'PREPARED','preparationEffectsStarted':True,'applicationEffectsStarted':application}
def prepare(data):
    validate(data,'prepare');h=header(data);context(h,True)
    if BASE.exists():
        with lock(h,True):
            reserve=BASE/'reservation.json'
            if reserve.exists():original(h);return {**fields(h,'PREPARE'),'state':'PREPARED'}
            require(all(p.name=='host.lock' for p in BASE.iterdir()),'BOOTSTRAP_CONTROLLER_CONFIGURATION_RETAINED')
    require(fresh(inspect_input(h))['proof']==h['expectedProof'],'BOOTSTRAP_CONTROLLER_APPROVAL_EXPIRED')
    with lock(h,True):
        require(not (BASE/'reservation.json').exists(),'BOOTSTRAP_CONTROLLER_HOST_RESERVED')
        task(h).mkdir(mode=0o700)
        atomic(h,task(h)/'input.json',h);atomic(h,task(h)/'agent.py',data['agent'].encode())
        atomic(h,BASE/'reservation.json',{'id':h['id'],'binding':fields(h,'STAGE')['binding']})
    return {**fields(h,'PREPARE'),'state':'PREPARED'}
def blob_spec(data,kind):
    specs=policy().artifact_specs(data['bundle']);require(kind in specs,'BOOTSTRAP_CONTROLLER_BLOB_KIND_INVALID')
    return specs[kind]['size'],specs[kind]['sha256']
def blob_metadata(path,maximum,uid=None,gid=None):
    # Archives may be 2GiB: never load them into RAM to check private ownership.
    uid=ROOT_UID if uid is None else uid;gid=ROOT_GID if gid is None else gid
    directory(path.parent,uid,0o700,gid)
    require(path.is_file() and all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_CONTROLLER_UNSAFE_FILE')
    st=path.stat();require(st.st_uid==uid and st.st_gid==gid and stat.S_IMODE(st.st_mode)==0o600 and st.st_size<=maximum,'BOOTSTRAP_CONTROLLER_UNSAFE_FILE')
    return st.st_size
def blob_status(data,kind):
    # Owned public artifact bytes do not require repeated NFS identity probes.
    # Their original binding/code/hash remain mandatory. No app effects can
    # start until dispatch and the independent worker revalidate dependencies.
    original(data,False);size,sha=blob_spec(data,kind);part=task(data)/(kind.lower()+'.part');ready=task(data)/(kind.lower()+'.ready')
    if ready.exists() or ready.is_symlink():
        require(blob_metadata(ready,size)==size and file_hash(ready)==sha,'BOOTSTRAP_CONTROLLER_BLOB_CHANGED')
        absent(part);return {'kind':kind,'state':'READY','offset':size,'prefixSha256':sha}
    if not part.exists() and not part.is_symlink():return {'kind':kind,'state':'PENDING','offset':0,'prefixSha256':digest(b'')}
    offset=blob_metadata(part,size);prefix=file_hash(part)
    state='COMPLETE_UNSEALED' if offset==size and prefix==sha else 'CORRUPT' if offset==size else 'PARTIAL'
    return {'kind':kind,'state':state,'offset':offset,'prefixSha256':prefix}
def finalize(data,kind):
    original(data,False)
    with lock(data):
        current=blob_status(data,kind)
        if current['state']=='READY':return current
        require(not (task(data)/'stage-receipt.json').exists(),'BOOTSTRAP_CONTROLLER_TRANSFER_CLOSED')
        require(current['state']=='COMPLETE_UNSEALED','BOOTSTRAP_CONTROLLER_BLOB_HASH_MISMATCH')
        os.rename(task(data)/(kind.lower()+'.part'),task(data)/(kind.lower()+'.ready'))
        fd=os.open(task(data),os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(fd)
        finally:os.close(fd)
        return blob_status(data,kind)
def put(data,kind,offset,prefix,chunk):
    require(type(offset) is int and offset>=0 and isinstance(chunk,bytes) and 0<len(chunk)<=MAX_CHUNK,'BOOTSTRAP_CONTROLLER_CHUNK_INVALID')
    return append_stream(data,kind,offset,prefix,len(chunk),io.BytesIO(chunk),MAX_CHUNK)
def put_stream(data,kind,offset,prefix,length,stream):
    require(policy().catalog(data['bundle']) is not None,'BOOTSTRAP_CONTROLLER_STREAM_CATALOG_REQUIRED')
    return append_stream(data,kind,offset,prefix,length,stream,policy().MAX_STREAM)
def append_stream(data,kind,offset,prefix,length,stream,maximum):
    original(data,False);size,expected=blob_spec(data,kind)
    require(type(offset) is int and offset>=0 and type(length) is int and 0<length<=maximum,'BOOTSTRAP_CONTROLLER_CHUNK_INVALID')
    with lock(data):
        require(not (task(data)/'stage-receipt.json').exists(),'BOOTSTRAP_CONTROLLER_TRANSFER_CLOSED')
        current=blob_status(data,kind)
        require(current['state']!='READY' and current['offset']==offset and current['prefixSha256']==prefix,
            'BOOTSTRAP_CONTROLLER_PREFIX_MISMATCH')
        require(offset+length<=size,'BOOTSTRAP_CONTROLLER_BLOB_TOO_LARGE')
        part=task(data)/(kind.lower()+'.part');flags=os.O_WRONLY|os.O_APPEND|os.O_NOFOLLOW
        if offset==0 and not part.exists():flags|=os.O_CREAT|os.O_EXCL
        fd=os.open(part,flags,0o600)
        with os.fdopen(fd,'ab') as f:
            remaining=length
            while remaining:
                block=stream.read(min(65536,remaining));require(isinstance(block,bytes) and 0<len(block)<=remaining,'BOOTSTRAP_CONTROLLER_STREAM_INCOMPLETE')
                f.write(block);remaining-=len(block)
            require(stream.read(1)==b'','BOOTSTRAP_CONTROLLER_STREAM_EXTRA_BYTES');f.flush();os.fsync(f.fileno())
        if offset+length==size:
            require(file_hash(part)==expected,'BOOTSTRAP_CONTROLLER_BLOB_HASH_MISMATCH')
            os.rename(part,task(data)/(kind.lower()+'.ready'))
            fd=os.open(task(data),os.O_RDONLY|os.O_DIRECTORY)
            try:os.fsync(fd)
            finally:os.close(fd)
        return blob_status(data,kind)
def worker_unit(data,op):return 'eatwhat-controller-process-'+data['id']+'-'+op.lower()
def receipt(data,op):
    path=task(data)/(op.lower()+'-receipt.json')
    if not path.exists() and not path.is_symlink():return None
    value=json.loads(private(path));require(all(value.get(k)==v for k,v in fields(data,op).items()),'BOOTSTRAP_CONTROLLER_RECEIPT_CHANGED');return value
def write_owned(data,path,raw,uid,gid,mode=0o600):
    absent(path);fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,mode)
    with os.fdopen(fd,'wb') as f:os.fchown(f.fileno(),uid,gid);os.fchmod(f.fileno(),mode);f.write(raw);f.flush();os.fsync(f.fileno())
def copy_owned(data,source,path,uid,gid,size,sha):
    require(blob_metadata(source,size)==size,'BOOTSTRAP_CONTROLLER_BLOB_CHANGED');absent(path)
    value=hashlib.sha256();copied=0;fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'wb') as out,os.fdopen(os.open(source,os.O_RDONLY|os.O_NOFOLLOW),'rb') as inp:
        os.fchown(out.fileno(),uid,gid);os.fchmod(out.fileno(),0o600)
        while block:=inp.read(65536):
            copied+=len(block);require(copied<=size,'BOOTSTRAP_CONTROLLER_BLOB_CHANGED');value.update(block);out.write(block)
        out.flush();os.fsync(out.fileno())
    require(copied==size and value.hexdigest()==sha,'BOOTSTRAP_CONTROLLER_BLOB_CHANGED')
def manifest(data):
    d,s,r,dep,prior,request=context(data);b=data['bundle'];files=rendered(data);mount=run(data,['systemd-escape','--path','--suffix=mount',str(CONTROL/'state')]).decode().strip()
    return {'config':{**files['privateFiles'],'application.properties':files['properties']},
        'unit':policy().service_unit(b,data['dependencyId'],mount),'ui':policy().ui_files(task(data)/'ui.ready',b)}
def verify_artifacts(data):
    for kind in policy().artifact_specs(data['bundle']):require(blob_status(data,kind)['state']=='READY','BOOTSTRAP_CONTROLLER_TRANSFER_INCOMPLETE')
    policy().verify_jar(task(data)/'jar.ready',data['bundle']);policy().ui_files(task(data)/'ui.ready',data['bundle'])
    policy().verify_catalog_files({kind:task(data)/(kind.lower()+'.ready') for kind in policy().artifact_specs(data['bundle'])},data['bundle'])
def stage(data):
    d,s,r,dep,prior,request=context(data);b=data['bundle'];wanted=manifest(data)
    verify_artifacts(data)
    for path in [CONTROL/'config',CONTROL/'acceptance',WEB,WEB_LINK,SYSTEM/UNIT]:absent(path)
    for path in [CONTROL/'config',CONTROL/'acceptance']:create_directory(path,prior['ownerUid'],prior['ownerGid'],0o700)
    for name,raw in wanted['config'].items():write_owned(data,CONTROL/'config'/name,raw,prior['ownerUid'],prior['ownerGid'])
    specs=policy().artifact_specs(b);copied=specs if policy().catalog(b) is not None else {'JAR':specs['JAR']}
    for kind,spec in copied.items():copy_owned(data,task(data)/(kind.lower()+'.ready'),CONTROL/'acceptance'/spec['filename'],prior['ownerUid'],prior['ownerGid'],spec['size'],spec['sha256'])
    for path in [CONTROL/'config',CONTROL/'acceptance']:
        fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:os.fsync(fd)
        finally:os.close(fd)
    create_directory(WEB,ROOT_UID,ROOT_GID,0o755);web=WEB/('ui-'+b['uiSha256']);create_directory(web,ROOT_UID,ROOT_GID,0o755)
    for name,raw in wanted['ui'].items():
        path=web/name
        for parent in reversed([path.parent,*path.parent.parents]):
            if parent.is_relative_to(web) and not parent.exists():create_directory(parent,ROOT_UID,ROOT_GID,0o755)
        write_owned(data,path,raw,ROOT_UID,ROOT_GID,0o644)
    WEB_LINK.symlink_to(web)
    s.write_config(SYSTEM/UNIT,wanted['unit'],0o644)
    run(data,['systemd-analyze','verify',str(SYSTEM/UNIT)],15);run(data,['systemctl','daemon-reload'])
def staged_proof(data):
    d,s,r,dep,prior,request=context(data);b=data['bundle'];wanted=manifest(data)
    for parent in [CONTROL/'config',CONTROL/'acceptance']:d.directory(parent,prior['ownerUid'],0o700,prior['ownerGid'])
    require({p.name for p in (CONTROL/'config').iterdir()}==set(wanted['config']),'BOOTSTRAP_CONTROLLER_CONFIGURATION_CHANGED')
    for name,raw in wanted['config'].items():require(d.owned_file(CONTROL/'config'/name,prior['ownerUid'],prior['ownerGid'],0o600,512*1024)==raw,'BOOTSTRAP_CONTROLLER_CONFIGURATION_CHANGED')
    jar=CONTROL/'acceptance'/(b['controllerSha256']+'.jar');d.owned_file(jar,prior['ownerUid'],prior['ownerGid'],0o600,policy().MAX_JAR);policy().verify_jar(jar,b)
    if policy().catalog(b) is not None:
        specs=policy().artifact_specs(b);require({path.name for path in (CONTROL/'acceptance').iterdir()}=={spec['filename'] for spec in specs.values()},'BOOTSTRAP_CONTROLLER_CATALOG_CHANGED')
        paths={}
        for kind,spec in specs.items():
            path=CONTROL/'acceptance'/spec['filename'];require(blob_metadata(path,spec['size'],prior['ownerUid'],prior['ownerGid'])==spec['size'] and file_hash(path)==spec['sha256'],'BOOTSTRAP_CONTROLLER_CATALOG_CHANGED');paths[kind]=path
        policy().verify_catalog_files(paths,b)
    require(config(data,SYSTEM/UNIT)==wanted['unit'],'BOOTSTRAP_CONTROLLER_UNIT_CHANGED')
    web=WEB/('ui-'+b['uiSha256']);directory(WEB,ROOT_UID,0o755,ROOT_GID);directory(web,ROOT_UID,0o755,ROOT_GID)
    require(WEB_LINK.is_symlink() and os.readlink(WEB_LINK)==str(web),'BOOTSTRAP_CONTROLLER_UI_LINK_CHANGED')
    actual={};directories=set()
    for path in web.rglob('*'):
        require(not path.is_symlink(),'BOOTSTRAP_CONTROLLER_UI_CHANGED')
        if path.is_dir():directory(path,ROOT_UID,0o755,ROOT_GID);directories.add(str(path.relative_to(web)))
        else:actual[str(path.relative_to(web))]=digest(d.owned_file(path,ROOT_UID,ROOT_GID,0o644,4*1024*1024))
    require(directories=={str(parent) for name in b['uiFiles'] for parent in Path(name).parents if str(parent)!='.'},'BOOTSTRAP_CONTROLLER_UI_CHANGED')
    require(actual==b['uiFiles'],'BOOTSTRAP_CONTROLLER_UI_CHANGED')
    require(run(data,['systemctl','show',UNIT,'--property=FragmentPath','--value']).decode().strip()==str(SYSTEM/UNIT),'BOOTSTRAP_CONTROLLER_UNIT_CHANGED')
    return {'controllerSha256':b['controllerSha256'],'uiSha256':b['uiSha256'],'privateConfigurationVerified':True,'controllerHealthProven':False}
def java_args(data):return ['/usr/bin/java','-Xms128m','-Xmx768m','-jar',str(CONTROL/'acceptance'/(data['bundle']['controllerSha256']+'.jar')),
    '--spring.config.location=file:/opt/eatwhat-control/config/application.properties','--spring.config.additional-location=','--spring.config.import=']
def runtime_proof(data,initial=False):
    staged_proof(data);d,s,r,dep,prior,request=context(data);b=data['bundle']
    def property(name):return run(data,['systemctl','show',UNIT,'--property='+name,'--value']).decode().strip()
    require(property('ActiveState')=='active' and run(data,['systemctl','is-enabled',UNIT]).strip()==b'enabled',
        'BOOTSTRAP_CONTROLLER_SERVICE_NOT_PERSISTENT')
    require(property('FragmentPath')==str(SYSTEM/UNIT) and shlex.split(property('DropInPaths'))==[str(SYSTEM/(UNIT+'.d')/'20-bootstrap-state.conf')],
        'BOOTSTRAP_CONTROLLER_SERVICE_OVERRIDE')
    gate='/usr/bin/python3 /etc/eatwhat-control-state-mount-check.py'
    require(property('ExecStartPre').count('argv[]=')==1 and 'argv[]='+gate+' ;' in property('ExecStartPre'),
        'BOOTSTRAP_CONTROLLER_MOUNT_GATE_MISSING')
    mount=run(data,['systemd-escape','--path','--suffix=mount',str(CONTROL/'state')]).decode().strip()
    for name in ['Requires','After','BindsTo']:require(mount in shlex.split(property(name)),'BOOTSTRAP_CONTROLLER_MOUNT_GATE_MISSING')
    pid=int(property('MainPID'));require(pid>1,'BOOTSTRAP_CONTROLLER_PROCESS_CHANGED');process=Path('/proc')/str(pid)
    require(process.stat().st_uid==prior['ownerUid'] and (process/'exe').resolve()==Path('/usr/bin/java').resolve()
        and [v.decode() for v in (process/'cmdline').read_bytes().split(b'\0') if v]==java_args(data),'BOOTSTRAP_CONTROLLER_PROCESS_CHANGED')
    matching=[{**v,'address':policy().listener_address(v['address'])} for v in d.listeners(dep) if v['address'].rsplit(':',1)[-1]=='18081']
    require(len(matching)==1 and matching[0]['address']=='127.0.0.1:18081' and 'pid='+str(pid)+',' in matching[0]['process'],
        'BOOTSTRAP_CONTROLLER_LISTENER_UNVERIFIED')
    return runtime_observation(data,initial)
def runtime_observation(data,initial):
    b=data['bundle'];opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open('http://127.0.0.1:18081/actuator/health/readiness',timeout=4) as response:
        require(response.status==200 and json.load(response).get('status')=='UP','BOOTSTRAP_CONTROLLER_NOT_READY')
    for name,sha in b['uiFiles'].items():
        with opener.open('http://127.0.0.1:18081/control-ui/'+name,timeout=4) as response:
            require(response.status==200 and digest(response.read(4*1024*1024+1))==sha,'BOOTSTRAP_CONTROLLER_HTTP_UI_CHANGED')
    for path in ['/admin/control/v1/cluster','/admin/control/v1/account']:
        try:opener.open('http://127.0.0.1:18081'+path,timeout=4);raise Refusal('BOOTSTRAP_CONTROLLER_ANONYMOUS_MANAGEMENT_ACCEPTED')
        except urllib.error.HTTPError as e:require(e.code==401,'BOOTSTRAP_CONTROLLER_ANONYMOUS_POLICY_CHANGED');e.close()
    expected={k:b[k] for k in ['clusterId','authoritySiteId','authorityInstanceId','authorityDatabaseFingerprint','controllerName']}
    expected.update(stateId=context(data)[5]['sharedStateId'],requireStandby=initial)
    # PropertiesLauncher must not discover loader.properties in an SSH user's
    # working directory or add an operator-unrelated classpath to this proof.
    args=['runuser','-u','eatwhat-control','--','/usr/bin/env','-C','/','/usr/bin/java',
        '-Dloader.home=/','-Dloader.config.location=file:/dev/null','-Dloader.path=',
        '-Dloader.main=you.v50to.eatwhat.control.bootstrap.ControllerRuntimeProbe',
        '-cp',str(CONTROL/'acceptance'/(b['controllerSha256']+'.jar')),'org.springframework.boot.loader.launch.PropertiesLauncher',str(CONTROL/'config/application.properties')]
    value=json.loads(run(data,args,30,canonical(expected)))
    require(all(value.get(k)==v for k,v in expected.items() if k!='requireStandby') and value.get('readOnlyTransaction') is True
        and value.get('actualDatabaseIdentityVerified') is True,
        'BOOTSTRAP_CONTROLLER_DATABASE_IDENTITY_UNVERIFIED')
    return {**staged_proof(data),'controllerHealthProven':True,'readiness':'UP','httpUiFilesVerified':len(b['uiFiles']),'database':value}
def proof(data,op,initial=False):return staged_proof(data) if op=='STAGE' else runtime_proof(data,initial)
def status(data,op):
    require(op in OPS);original(data);saved=receipt(data,op)
    if saved is None:return {**fields(data,op),'state':'NOT_STARTED','effectsStarted':False}
    if saved['state']==OPS[op]:
        try:return {**saved,**proof(data,op)}
        except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_CONTROLLER_CONFIGURATION_UNVERIFIED'}
    if saved['state'] in ['ACCEPTED','RUNNING','OUTCOME_UNKNOWN']:
        active=run(data,['systemctl','show',worker_unit(data,op),'--property=ActiveState','--value']).strip()
        if active in [b'inactive',b'failed']:
            try:
                with lock(data):
                    require(saved['effectsStarted']);value=proof(data,op,op=='VERIFY')
                    result={**saved,**value,'state':OPS[op],'derivedFromConfiguration':True}
                    if 'code' in result:result['originalReceiptCode']=result.pop('code')
                    return result
            except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_CONTROLLER_RECONCILIATION_REQUIRED'}
        require(active in [b'active',b'activating',b'deactivating',b'reloading'],'BOOTSTRAP_CONTROLLER_WORKER_UNOBSERVABLE')
    return saved
def dispatch(data,op):
    require(op in OPS);original(data);previous=status(data,op)
    if previous['state']!='NOT_STARTED':return previous
    if op=='STAGE':
        require(fresh(inspect_input(data))['proof']==data['expectedProof'],'BOOTSTRAP_CONTROLLER_APPROVAL_EXPIRED')
        verify_artifacts(data)
    else:require(status(data,'STAGE')['state']=='STAGED','BOOTSTRAP_CONTROLLER_STAGE_REQUIRED')
    with lock(data):
        previous=receipt(data,op)
        if previous:return status(data,op)
        saved={**fields(data,op),'state':'ACCEPTED','effectsStarted':False,'acceptedAt':time.time()};atomic(data,task(data)/(op.lower()+'-receipt.json'),saved)
    try:run(data,['systemd-run','--quiet','--no-block','--unit='+worker_unit(data,op),'--property=Type=exec','--property=UMask=0077',
        '--property=RuntimeMaxSec=240','--property=StandardOutput=null','--property=StandardError=null','python3',str(task(data)/'agent.py'),'run',data['id'],op])
    except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_CONTROLLER_DISPATCH_UNCONFIRMED'}
    return saved
def perform(id,op):
    policy().identifier(id);require(op in OPS);directory(BASE/id,ROOT_UID,0o700,ROOT_GID)
    data=json.loads(private(BASE/id/'input.json'));original(data)
    with lock(data):
        saved=receipt(data,op);require(saved is not None and saved['state']=='ACCEPTED','BOOTSTRAP_CONTROLLER_ALREADY_DISPATCHED')
        try:
            if op=='STAGE':
                require(fresh(inspect_input(data))['proof']==data['expectedProof'],'BOOTSTRAP_CONTROLLER_APPROVAL_EXPIRED');verify_artifacts(data)
            else:require(status(data,'STAGE')['state']=='STAGED','BOOTSTRAP_CONTROLLER_STAGE_REQUIRED')
            saved.update(state='RUNNING',effectsStarted=True);atomic(data,task(data)/(op.lower()+'-receipt.json'),saved)
            if op=='STAGE':stage(data)
            else:
                run(data,['systemctl','enable','--now',UNIT],25);deadline=time.monotonic()+150
                while True:
                    try:proof(data,op,True);break
                    except Exception:
                        require(time.monotonic()<deadline,'BOOTSTRAP_CONTROLLER_START_UNVERIFIED');time.sleep(2)
            saved.update(proof(data,op,op=='VERIFY'),state=OPS[op],finishedAt=time.time())
        except Exception as error:saved.update(state='OUTCOME_UNKNOWN' if saved['effectsStarted'] else 'FAILED',
            code=getattr(error,'code','BOOTSTRAP_CONTROLLER_FAILED_PRESERVED'),finishedAt=time.time())
        atomic(data,task(data)/(op.lower()+'-receipt.json'),saved);return saved

if __name__=='__main__':
    try:
        require(os.geteuid()==ROOT_UID and sys.platform=='linux','BOOTSTRAP_CONTROLLER_ROOT_LINUX_REQUIRED')
        if len(sys.argv)==4 and sys.argv[1]=='run':reply=perform(sys.argv[2],sys.argv[3])
        else:
            require(2<=len(sys.argv)<=3)
            if sys.argv[1]=='put-stream':
                raw=sys.stdin.buffer.readline(MAX_INPUT+1);require(len(raw)<=MAX_INPUT and raw.endswith(b'\n'));data=json.loads(raw)
                require(set(data)=={'header','offset','prefixSha256','length'})
                reply=put_stream(data['header'],sys.argv[2],data['offset'],data['prefixSha256'],data['length'],sys.stdin.buffer)
                print(json.dumps(reply));sys.exit(0)
            raw=sys.stdin.buffer.read(MAX_INPUT+1);require(len(raw)<=MAX_INPUT);data=json.loads(raw)
            if sys.argv[1]=='inspect':reply=fresh(data)
            elif sys.argv[1]=='prepare':reply=prepare(data)
            elif sys.argv[1]=='prepare-status':reply=prepare_status(data)
            elif sys.argv[1]=='blob-status':reply=blob_status(data,sys.argv[2])
            elif sys.argv[1]=='finalize':reply=finalize(data,sys.argv[2])
            elif sys.argv[1]=='put':
                require(set(data)=={'header','offset','prefixSha256','bytes'});reply=put(data['header'],sys.argv[2],data['offset'],data['prefixSha256'],base64.b64decode(data['bytes'],validate=True))
            elif sys.argv[1]=='dispatch':reply=dispatch(data,sys.argv[2])
            elif sys.argv[1]=='status':reply=status(data,sys.argv[2])
            else:raise Refusal('BOOTSTRAP_CONTROLLER_COMMAND_INVALID')
        print(json.dumps(reply))
    except Exception as error:print(json.dumps({'protocol':PROTOCOL,'code':getattr(error,'code','BOOTSTRAP_CONTROLLER_REFUSED')}));sys.exit(1)

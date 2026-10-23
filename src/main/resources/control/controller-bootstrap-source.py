"""Read-only, fixed-path source snapshot launcher. Its successful response has
private configuration: encrypt it in the manager, never expose raw HTTP/stdout.
The probe JAR is separately approved/staged; this collector never uploads files,
installs a service or edits the current source controller.
"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import sys
import uuid
import zipfile

PROTOCOL='eatwhat-controller-source-snapshot-v1'
DISCOVERY_PROTOCOL='eatwhat-controller-source-discovery-v1'
CONTROL=Path('/opt/eatwhat-control')
UNIT=Path('/etc/systemd/system/eatwhat-control.service')
PROC=Path('/proc')
JAVA='/usr/bin/java'
ROOT_UID=0

class Refusal(Exception):
    def __init__(self,code='BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE'):self.code=code;super().__init__(code)

def require(ok):
    if not ok:raise Refusal()

def digest(raw):return hashlib.sha256(raw).hexdigest()
def canonical(v):return json.dumps(v,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def sha(value):require(isinstance(value,str) and re.fullmatch('[a-f0-9]{64}',value));return value

def private(path,maximum,owner=None,mode=0o600):
    require(all(not p.is_symlink() for p in [path,*path.parents]))
    info=path.stat();require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode)==mode and info.st_size<=maximum
        and (owner is None or info.st_uid in [ROOT_UID,owner]))
    with path.open('rb') as stream:raw=stream.read(maximum+1)
    require(len(raw)<=maximum);return raw

def validate(h):
    require(isinstance(h,dict) and set(h)=={'sourceArtifactSha256','probeArtifactSha256','unitSha256','expected'})
    for key in ['sourceArtifactSha256','probeArtifactSha256','unitSha256']:sha(h[key])
    expected=h['expected'];require(isinstance(expected,dict) and set(expected)=={'clusterId','authoritySiteId','authorityInstanceId',
        'authorityDatabaseFingerprint','stateId','epoch','controllerName','applicationSha256','profilesSha256','sharedKeySha256'})
    for key in ['applicationSha256','profilesSha256','sharedKeySha256']:sha(expected[key])
    require(len(canonical(h))<=8192);return h

def command(args):
    p=subprocess.run(args,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=8,
        env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LANG':'C.UTF-8'})
    require(p.returncode==0 and len(p.stdout)<=65536);return p.stdout

def process(h):
    uid=pwd.getpwnam('eatwhat-control').pw_uid
    require(digest(private(UNIT,65536,ROOT_UID,0o644))==h['unitSha256'])
    pid=command(['systemctl','show','eatwhat-control.service','-p','MainPID','--value']).decode().strip()
    require(pid.isascii() and pid.isdigit() and int(pid)>1)
    base=PROC/pid;require(base.stat().st_uid==uid)
    argv=(base/'cmdline').read_bytes().split(b'\0');require(argv[-1]==b'');argv=[x.decode() for x in argv[:-1]]
    jar=str(CONTROL/'acceptance'/(h['sourceArtifactSha256']+'.jar'))
    require(argv[0]==JAVA and argv.count('-jar')==1)
    at=argv.index('-jar');require(argv[at+1:]==[jar,'--spring.config.location=file:'+str(CONTROL/'config/application.properties'),
        '--spring.config.additional-location=','--spring.config.import='])
    require(all(re.fullmatch('-Xm[sx][1-9][0-9]*[mMgG]',x) for x in argv[1:at]))
    environment={}
    for row in (base/'environ').read_bytes().split(b'\0'):
        if row:
            k,sep,v=row.partition(b'=');require(sep);name=k.decode();require(name not in environment);environment[name]=v
    require(not any(k.startswith(('SPRING_','APP_','LOADER_')) or k in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS'] for k in environment))
    # proc stat comm can contain spaces/parentheses: split after its final ')'.
    started=(base/'stat').read_text().rsplit(')',1)[1].split()[19]
    return {'pid':int(pid),'startTicks':started,'argvSha256':digest(canonical(argv)),
        'environmentSha256':digest(canonical({k:v.hex() for k,v in environment.items()}))},environment

def profile_storage(pid):
    """Read the actual service mount namespace, not the SSH helper's view.

    An owner-writable config directory can still be read-only underneath
    ProtectSystem=strict. Refuse a new plan before any host effects.
    """
    uid=pwd.getpwnam('eatwhat-control').pw_uid;config=CONTROL/'config'
    if not config.is_dir() or config.is_symlink():raise Refusal('BOOTSTRAP_SOURCE_PROFILE_DIRECTORY_REQUIRED')
    for path in [config,config/'profile-publications']:
        if path.exists():
            info=path.stat()
            if path.is_symlink() or not path.is_dir() or info.st_uid!=uid or stat.S_IMODE(info.st_mode)!=0o700:
                raise Refusal('BOOTSTRAP_SOURCE_PROFILE_DIRECTORY_REQUIRED')
    raw=(PROC/str(pid)/'mountinfo').read_bytes();require(len(raw)<=2097152)
    mounts=[]
    for line in raw.decode().splitlines():
        parts=line.split(' - ');require(len(parts)==2)
        fields=parts[0].split();super_fields=parts[1].split();require(len(fields)>=6 and len(super_fields)>=3)
        mount=fields[4];require(mount.startswith('/'))
        mounts.append((mount,set(fields[5].split(',')),set(super_fields[2].split(','))))
    for path in [config,config/'profile-publications']:
        text=str(path);matching=[row for row in mounts if row[0]=='/' or text==row[0] or text.startswith(row[0]+'/')]
        require(bool(matching));deepest=max(len(row[0]) for row in matching)
        for mount,options,super_options in matching:
            if len(mount)!=deepest:continue
            if 'rw' not in options or 'ro' in options or 'rw' not in super_options or 'ro' in super_options:
                raise Refusal('BOOTSTRAP_SOURCE_PROFILE_STORAGE_READ_ONLY')

def artifact(value):
    p=CONTROL/'acceptance'/(value+'.jar');info=p.stat()
    require(all(not x.is_symlink() for x in [p,*p.parents]) and stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode)==0o600
        and info.st_uid in [ROOT_UID,pwd.getpwnam('eatwhat-control').pw_uid] and 0<info.st_size<=256*1024*1024)
    with p.open('rb') as f:require(hashlib.file_digest(f,'sha256').hexdigest()==value)
    return p

def receiver_capability(path,values=None):
    marker='META-INF/eatwhat-controller-profile-receiver-v1'
    try:
        with zipfile.ZipFile(path) as jar:
            require(jar.namelist().count(marker)==1 and jar.read(marker)==b'eatwhat-controller-profile-receiver-v1\n')
            for name in ['ControlBootstrapProfileNodeController','ControlBootstrapProfileReceiver','ControlBootstrapProfileState']:
                require('BOOT-INF/classes/you/v50to/eatwhat/control/'+name+'.class' in jar.namelist())
            require(jar.read('META-INF/eatwhat-controller-profile-services-successor-v1')==b'eatwhat-controller-profile-services-successor-v1\n'
                and 'BOOT-INF/classes/you/v50to/eatwhat/control/ControlBootstrapProfileServices.class' in jar.namelist())
            require(jar.read('META-INF/eatwhat-controller-bootstrap-membership-v1')==b'eatwhat-controller-bootstrap-membership-v1\n'
                and all('BOOT-INF/classes/you/v50to/eatwhat/control/'+name+'.class' in jar.namelist()
                        for name in ['ControlBootstrapMembership','ControlBootstrapMembershipNodeController']))
            require(jar.read('META-INF/eatwhat-controller-bootstrap-membership-v2')==b'eatwhat-controller-bootstrap-membership-v2\n'
                and 'BOOT-INF/classes/you/v50to/eatwhat/cluster/ClusterMembershipCoordinator$Boundary.class' in jar.namelist())
            require(jar.read('META-INF/eatwhat-controller-bootstrap-verify-v1')==b'eatwhat-controller-bootstrap-verify-v1\n'
                and all('BOOT-INF/classes/you/v50to/eatwhat/control/'+name+'.class' in jar.namelist() for name in ['ControlBootstrapVerify','ControlBootstrapMemberRuntime']))
        if values is not None:
            require(all(values.get(key)=='true' for key in ['app.control.enabled','app.control.node-enabled','app.control.controller-bootstrap.enabled']))
    except (Refusal,zipfile.BadZipFile,KeyError):raise Refusal('BOOTSTRAP_SOURCE_PROFILE_RECEIVER_REQUIRED')

def collect(h,accepting=False):
    h=validate(h);require(os.geteuid()==0 and sys.platform=='linux')
    require(not os.path.ismount(CONTROL/'state'))
    before,environment=process(h);source=artifact(h['sourceArtifactSha256']);probe=artifact(h['probeArtifactSha256'])
    # New plans cannot run host effects against a SOURCE lacking the receiver.
    # Existing tasks keep their original read-only observation contract.
    if accepting:
        receiver_capability(source)
        profile_storage(before['pid'])
    # Fixed loader settings prevent unrelated local loader.properties or ambient
    # JVM options from adding code/configuration to this read-only CLI.
    cmd=[JAVA,'-Xms32m','-Xmx192m','-Dloader.home=/','-Dloader.config.location=file:/dev/null','-Dloader.path=',
        '-Dloader.main=you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot','-cp',str(probe),
        'org.springframework.boot.loader.launch.PropertiesLauncher']
    p=subprocess.run(cmd,input=canonical(h['expected']),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=35,
        cwd='/',env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LANG':'C.UTF-8','HOME':'/nonexistent'})
    if p.returncode and len(p.stdout)<=1024:
        try:failure=json.loads(p.stdout)
        except (ValueError,TypeError):failure={}
        if failure.get('code')=='BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED':raise Refusal('BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED')
    require(p.returncode==0 and 0<len(p.stdout)<=60*1024)
    result=json.loads(p.stdout);require(result.get('protocol')==PROTOCOL and set(result)=={'protocol','proof','sourceAuthority','fileHashes','_private'})
    values=result['_private']['properties'];require(isinstance(values,dict))
    if accepting:receiver_capability(source,values)
    aliases={re.sub(r'[.\-\[\]]','',k).lower() for k in values}
    require(all(re.sub('_','',k).lower() not in aliases for k in environment))
    after,_=process(h);require(before==after)
    artifact(h['sourceArtifactSha256']);artifact(h['probeArtifactSha256'])
    result['sourceProcess']=before
    result['captureBinding']=digest(canonical({'request':h,'fileHashes':result['fileHashes'],'sourceProcess':before}))
    require(len(canonical(result))<=65536);return result

def discover(request):
    require(os.geteuid()==0 and sys.platform=='linux' and not os.path.ismount(CONTROL/'state'))
    require(isinstance(request,dict) and set(request)=={'authority'})
    authority=request['authority'];keys={'clusterId','authoritySiteId','authorityInstanceId','authorityDatabaseFingerprint','stateId','epoch','sharedKeySha256'}
    require(isinstance(authority,dict) and set(authority)==keys)
    for key in ['clusterId','authorityInstanceId','stateId']:
        require(isinstance(authority[key],str) and str(uuid.UUID(authority[key]))==authority[key] and int(uuid.UUID(authority[key]))!=0)
    require(isinstance(authority['authoritySiteId'],str) and re.fullmatch('[A-Za-z0-9_-]{1,64}',authority['authoritySiteId'])
        and isinstance(authority['authorityDatabaseFingerprint'],str) and re.fullmatch('[a-f0-9]{32}',authority['authorityDatabaseFingerprint'])
        and type(authority['epoch']) is int and 0<authority['epoch']<9223372036854775807)
    sha(authority['sharedKeySha256'])
    # Discover the managed process's content-addressed artifact, never a path,
    # loader option, service name or artifact supplied by the HTTP caller.
    unit=digest(private(UNIT,65536,ROOT_UID,0o644))
    pid=command(['systemctl','show','eatwhat-control.service','-p','MainPID','--value']).decode().strip()
    require(pid.isascii() and pid.isdigit() and int(pid)>1)
    argv=(PROC/pid/'cmdline').read_bytes().split(b'\0');require(argv.count(b'-jar')==1)
    at=argv.index(b'-jar');require(at+1<len(argv))
    match=re.fullmatch(re.escape(str(CONTROL/'acceptance'))+'/([a-f0-9]{64})\\.jar',argv[at+1].decode());require(match)
    version=match.group(1);h={'sourceArtifactSha256':version,'probeArtifactSha256':version,'unitSha256':unit}
    before,_=process(h);source=artifact(version);receiver_capability(source)
    try:
        with zipfile.ZipFile(source) as jar:
            marker='META-INF/eatwhat-controller-source-discovery-v1'
            require(jar.namelist().count(marker)==1 and jar.read(marker)==b'eatwhat-controller-source-discovery-v1\n')
    except (Refusal,KeyError,zipfile.BadZipFile):raise Refusal('BOOTSTRAP_SOURCE_DISCOVERY_REQUIRED')
    cmd=[JAVA,'-Xms32m','-Xmx192m','-Dloader.home=/','-Dloader.config.location=file:/dev/null','-Dloader.path=',
        '-Dloader.main=you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot','-cp',str(source),
        'org.springframework.boot.loader.launch.PropertiesLauncher','discover']
    p=subprocess.run(cmd,input=canonical(authority),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=20,
        cwd='/',env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LANG':'C.UTF-8','HOME':'/nonexistent'})
    require(p.returncode==0 and 0<len(p.stdout)<=8192);h['expected']=json.loads(p.stdout);validate(h)
    require(all(h['expected'].get(key)==value for key,value in authority.items()))
    captured=collect(h,True);require(captured['sourceProcess']==before)
    result={'protocol':DISCOVERY_PROTOCOL,'request':h,'capture':captured}
    require(len(canonical(result))<=65536);return result

def inspection(request):
    # A separately versioned read-only observer. Never upgrades this query to
    # profile-receiver admission, and never returns private source materials.
    value=collect(request,False)
    result={key:value[key] for key in ['proof','sourceAuthority','fileHashes','sourceProcess','captureBinding']}
    result.update(protocol='eatwhat-controller-source-inspection-v1',sourceProtocol=PROTOCOL,privateMaterialIncluded=False)
    require(len(canonical(result))<=65536);return result

def main():
    try:
        require(sys.argv[1:] in [[],['profile-receiver'],['discover'],['inspection']]);raw=sys.stdin.buffer.read(8193);require(len(raw)<=8192)
        request=json.loads(raw)
        result=discover(request) if sys.argv[1:]==['discover'] else inspection(request) if sys.argv[1:]==['inspection'] else collect(request,sys.argv[1:]==['profile-receiver'])
        print(json.dumps(result,separators=(',',':')))
    except Refusal as failure:
        print(json.dumps({'protocol':PROTOCOL,'code':failure.code}));sys.exit(1)
    except Exception:
        print(json.dumps({'protocol':PROTOCOL,'code':'BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE'}));sys.exit(1)

if __name__=='__main__':main()

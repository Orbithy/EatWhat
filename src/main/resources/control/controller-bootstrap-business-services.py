"""Independent, read-only controller services successor after Docker effects.

Freeze is allowed only while the original controller chain verifies live. After
installation, no old context/status function is called or replaced: immutable
local material, effective units/processes/listeners and fresh bounded NFS/SSH/
HTTP/database probes are checked independently against that frozen baseline.
This does not approve firewall/IPv6 effects, business health or cluster JOIN.
"""
import base64
import copy
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import tempfile
import types
import urllib.request
import urllib.error
import uuid

RPC_SOURCE='__EATWHAT_BUSINESS_SERVICES_NFS_POLICY_BASE64__'
PROTOCOL='eatwhat-business-controller-services-v1'
CONTROL=Path('/opt/eatwhat-control')
SYSTEM=Path('/etc/systemd/system')
ENV={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LANG':'C.UTF-8','HOME':'/root'}
PROFILE_PROTOCOL='eatwhat-controller-profile-services-successor-v1'
PROFILE_ENDPOINT='http://127.0.0.1:18081/internal/control/v1/controller-profiles/services-status'
STATIC_PROPERTIES=['FragmentPath','DropInPaths','User','Group','Type','Requires','After','BindsTo','ExecStart','ExecStartPre',
    'ExecCondition','Environment','EnvironmentFiles','RootDirectory','RootImage','PrivateNetwork','NetworkNamespacePath']

class Refusal(Exception):
    def __init__(self,code='BOOTSTRAP_BUSINESS_SERVICES_UNCONFIRMED'):self.code=code;super().__init__(code)
def require(ok,code='BOOTSTRAP_BUSINESS_SERVICES_UNCONFIRMED'):
    if not ok:raise Refusal(code)
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def digest(value):return hashlib.sha256(canonical(value)).hexdigest()
def run(args,raw=None,timeout=10):
    with tempfile.TemporaryFile() as output:
        subprocess.run(args,env=ENV,input=raw,stdin=subprocess.DEVNULL if raw is None else None,stdout=output,stderr=subprocess.DEVNULL,timeout=timeout,check=True,cwd='/')
        require(output.tell()<=2097152,'BOOTSTRAP_BUSINESS_SERVICES_RESPONSE_TOO_LARGE');output.seek(0);return output.read(2097153)
def file_fact(path,maximum=300*1024*1024):
    path=Path(path);require(all(not p.is_symlink() for p in [path,*path.parents]) and path.is_file(),'BOOTSTRAP_BUSINESS_SERVICES_UNSAFE_FILE')
    before=path.stat();require(before.st_size<=maximum and before.st_mode&0o022==0,'BOOTSTRAP_BUSINESS_SERVICES_UNSAFE_FILE')
    result=hashlib.sha256()
    with path.open('rb') as stream:
        while block:=stream.read(65536):result.update(block)
    after=path.stat();require((before.st_ino,before.st_size,before.st_mtime_ns)==(after.st_ino,after.st_size,after.st_mtime_ns),'BOOTSTRAP_BUSINESS_SERVICES_FILE_CHANGED')
    return {'uid':before.st_uid,'gid':before.st_gid,'mode':stat.S_IMODE(before.st_mode),'size':before.st_size,'sha256':result.hexdigest()}
def directory_fact(path):
    path=Path(path);require(all(not p.is_symlink() for p in [path,*path.parents]) and path.is_dir(),'BOOTSTRAP_BUSINESS_SERVICES_UNSAFE_DIRECTORY')
    info=path.stat();require(info.st_mode&0o022==0,'BOOTSTRAP_BUSINESS_SERVICES_UNSAFE_DIRECTORY')
    return {'uid':info.st_uid,'gid':info.st_gid,'mode':stat.S_IMODE(info.st_mode),'entries':sorted(p.name for p in path.iterdir())}
def properties(unit):
    raw=run(['/usr/bin/systemctl','show',unit,*['--property='+p for p in STATIC_PROPERTIES+['ActiveState','UnitFileState','MainPID']]]).decode()
    values={}
    for line in raw.splitlines():
        k,sep,v=line.partition('=');require(sep and k not in values);values[k]=v
    expected=set(STATIC_PROPERTIES+['ActiveState','UnitFileState','MainPID'])
    # systemd omits empty command arrays/EnvironmentFiles even with --all;
    # mount units have no ExecStart or MainPID property. Preserve absence as a
    # distinct baseline value so adding one later is still detected.
    optional={'EnvironmentFiles','ExecCondition','ExecStartPre'}
    if unit.endswith('.mount'):optional|={'ExecStart','MainPID'}
    require(set(values)<=expected and expected-set(values)<=optional,'BOOTSTRAP_BUSINESS_SERVICES_UNIT_UNOBSERVABLE')
    for key in expected-set(values):values[key]=None
    require(values.pop('ActiveState')=='active' and values.pop('UnitFileState')=='enabled','BOOTSTRAP_BUSINESS_SERVICES_UNIT_UNREADY')
    pid=int(values.pop('MainPID') or '0')
    for key in ['ExecStart','ExecStartPre','ExecCondition']:
        text=values[key]
        if text is None:continue
        argv=re.findall(r'argv\[\]=(.*?) ;',text)
        require(text.count('argv[]=')==len(argv) and (not text or argv),'BOOTSTRAP_BUSINESS_SERVICES_UNIT_UNOBSERVABLE');values[key]=argv
    for key in ['DropInPaths','Requires','After','BindsTo']:values[key]=sorted(shlex.split(values[key]))
    return values,pid
def process(pid):
    if pid==0:return None
    require(pid>1,'BOOTSTRAP_BUSINESS_SERVICES_PROCESS_UNREADY');path=Path('/proc')/str(pid)
    before=(path/'stat').read_text().rsplit(')',1)[1].split()[19]
    args=(path/'cmdline').read_bytes();environment=(path/'environ').read_bytes().split(b'\0')
    ignored=[b'INVOCATION_ID=',b'JOURNAL_STREAM=',b'SYSTEMD_EXEC_PID=']
    environment=sorted(v for v in environment if v and not any(v.startswith(k) for k in ignored))
    info=path.stat();binary=(path/'exe').resolve(strict=True)
    require((path/'ns/net').stat().st_ino==Path('/proc/1/ns/net').stat().st_ino,'BOOTSTRAP_BUSINESS_SERVICES_PROCESS_NAMESPACE_CHANGED')
    value={'uid':info.st_uid,'gid':info.st_gid,'argvSha256':hashlib.sha256(args).hexdigest(),'environmentSha256':hashlib.sha256(b'\0'.join(environment)).hexdigest(),
        'executable':str(binary),'executableSha256':file_fact(binary)['sha256']}
    require(before==(path/'stat').read_text().rsplit(')',1)[1].split()[19],'BOOTSTRAP_BUSINESS_SERVICES_PROCESS_CHANGED');return value
def listener_address(value):
    match=re.fullmatch(r'\[::ffff:(127\.0\.0\.1)\]:(\d+)',value)
    return match[1]+':'+match[2] if match else value
def listeners():
    result=[]
    for line in run(['/usr/bin/ss','-H','-lntp']).decode().splitlines():
        fields=line.split();require(len(fields)>=5,'BOOTSTRAP_BUSINESS_SERVICES_LISTENER_UNOBSERVABLE')
        result.append({'address':listener_address(fields[3]),'process':' '.join(fields[5:])})
    return result
def unit_materials(units):
    files=set();directories=set();absent=set();symlinks={}
    for unit in units:
        values,pid=properties(unit);paths=[values['FragmentPath'],*values['DropInPaths']]
        for raw in paths:
            path=Path(raw);require(path.is_absolute())
            for p in [path,*path.parents]:
                if p.is_symlink():symlinks[str(p)]=os.readlink(p)
            files.add(path.resolve(strict=True))
        stem,suffix=unit.rsplit('.',1);names={unit,suffix}
        if '@' in stem:names.add(stem.split('@',1)[0]+'@.'+suffix)
        parts=stem.split('-')
        for i in range(1,len(parts)):names.add('-'.join(parts[:i])+'-.'+suffix)
        for parent in [SYSTEM,Path('/run/systemd/system'),Path('/usr/lib/systemd/system')]:
            for name in names:
                folder=parent/(name+'.d')
                if folder.exists():
                    require(not folder.is_symlink() and folder.is_dir(),'BOOTSTRAP_BUSINESS_SERVICES_UNSAFE_DIRECTORY')
                    directories.add(folder);files.update(folder.iterdir())
                else:require(not folder.is_symlink(),'BOOTSTRAP_BUSINESS_SERVICES_UNSAFE_DIRECTORY');absent.add(str(folder))
    return files,directories,absent,symlinks
def observe_files(plan):
    archive=plan.get('imageArchive');archive_path=None
    if archive is not None:
        require(isinstance(archive,dict) and set(archive)=={'sha256','size'}
            and isinstance(archive['sha256'],str) and re.fullmatch('[a-f0-9]{64}',archive['sha256'])
            and type(archive['size']) is int and 0<archive['size']<=2*1024*1024*1024,'BOOTSTRAP_BUSINESS_SERVICES_ARCHIVE_BINDING_CHANGED')
        archive_path=str(CONTROL/'acceptance'/(archive['sha256']+'.images.tar'))
        require(archive_path in plan['files'],'BOOTSTRAP_BUSINESS_SERVICES_ARCHIVE_BINDING_CHANGED')
    files={p:file_fact(p,archive['size'] if p==archive_path else 300*1024*1024) for p in plan['files']}
    if archive_path is not None:
        require(files[archive_path]['size']==archive['size'] and files[archive_path]['sha256']==archive['sha256'],
            'BOOTSTRAP_BUSINESS_SERVICES_ARCHIVE_CHANGED')
    return files
def observe(plan):
    # All paths are derived from an original live-verified chain, sealed before
    # Docker. The state mount itself is deliberately never walked/stat'ed.
    files=observe_files(plan)
    directories={p:directory_fact(p) for p in plan['directories']}
    for path,target in plan['symlinks'].items():require(Path(path).is_symlink() and os.readlink(path)==target,'BOOTSTRAP_BUSINESS_SERVICES_SYMLINK_CHANGED')
    for path in plan['absent']:require(not Path(path).exists() and not Path(path).is_symlink(),'BOOTSTRAP_BUSINESS_SERVICES_GUARD_OPENED')
    units={};pids={}
    for unit in plan['units']:
        props,pid=properties(unit);units[unit]={'properties':props,'process':process(pid)};pids[unit]=pid
    rows=listeners()
    for port,unit in plan['listeners'].items():
        matching=[r for r in rows if r['address'].rsplit(':',1)[-1]==str(port)]
        require(len(matching)==1 and matching[0]['address']=='127.0.0.1:'+str(port) and pids[unit]>1
            and 'pid='+str(pids[unit])+',' in matching[0]['process'],'BOOTSTRAP_BUSINESS_SERVICES_LISTENER_CHANGED')
    for unit in plan['inactiveUnits']:
        require(run(['/usr/bin/systemctl','show',unit,'--property=ActiveState','--value']).strip() in [b'inactive',b'failed'],
            'BOOTSTRAP_BUSINESS_SERVICES_LEGACY_RPC_ACTIVE')
    return {'files':files,'directories':directories,'units':units}
def freeze(c,header):
    c.original(header);require(c.status(header,'VERIFY')['state']=='VERIFIED','BOOTSTRAP_BUSINESS_SERVICES_ORIGINAL_UNVERIFIED')
    d,s,r,dep,state,request=c.context(header);link,link_header=s.link(state)
    runtime,ready,original=link.completed_runtime(link_header)
    intent=json.loads(s.private(s.task(state)/'state-intent.json'));require(intent['role']=='TARGET')
    dp=d.policy();unit=dp.unit_name(dep['id'],'target');b=header['bundle'];web=c.WEB/('ui-'+b['uiSha256'])
    files={SYSTEM/c.UNIT,SYSTEM/(c.UNIT+'.d')/'20-bootstrap-state.conf',Path('/etc/eatwhat-control-state-mount-check.py'),Path('/etc/fstab'),
        SYSTEM/'wg-quick@ewctrl0.service.d/90-eatwhat-bootstrap.conf'}
    # wg-quick may use its stock unit without a bootstrap drop-in.
    missing={str(p) for p in files if not p.exists()};files={p for p in files if p.exists()};files.update(Path(p) for p in intent['desired'])
    directories={CONTROL/'config',CONTROL/'acceptance',c.WEB,web,SYSTEM/(c.UNIT+'.d'),CONTROL/'private'}
    for directory in [CONTROL/'config',CONTROL/'acceptance',CONTROL/'private']:files.update(directory.iterdir())
    for path in web.rglob('*'):
        if path.is_dir():directories.add(path)
        else:files.add(path)
    files.update([SYSTEM/unit,d.key_path(dep),Path(str(d.key_path(dep))+'.pub'),d.host_path(dep)])
    for path,names in [(c.task(header),['input.json','agent.py']),(d.task(dep),['input.json','agent.py','key-proof.json']),
        (s.task(state),['state-input.json','state-agent.py','state-intent.json','mounted-proof.json']),
        (link.task(link_header),['link-input.json','link-agent.py','link-intent.json']),
        (r.task(runtime),['runtime-input.json','runtime-agent.py','preflight.json'])]:files.update(path/name for name in names)
    absent=list(missing);guards=r.task(runtime)/'service-guards.json'
    if guards.exists():
        r.verify_service_guards(runtime);files.add(guards)
        for guard in json.loads(r.private(guards)):
            files.add(Path(guard['path']))
            if guard['unit'] in r.RPC_UNITS+r.NFT_UNITS:
                absent.append(str(r.task(runtime)/('stock-firewall-not-approved' if guard['unit'] in r.NFT_UNITS else 'legacy-rpc-not-approved')))
    units=[c.UNIT,unit,'wg-quick@ewctrl0.service',s.CLIENT_FW_NAME,intent['mountUnit']]
    unit_files,unit_dirs,unit_absent,unit_links=unit_materials(units);files.update(unit_files);directories.update(unit_dirs);absent+=list(unit_absent)
    plan={'files':sorted(map(str,files)),'directories':sorted(map(str,directories)),'symlinks':{**unit_links,'/srv/eatwhat-control':str(web)},'absent':sorted(set(absent)),
        'units':units,'inactiveUnits':r.RPC_UNITS,'listeners':{str(port):name for port,name in [(18081,c.UNIT),*[(v['localPort'],unit) for v in dep['forwards']]]}}
    # Match the original reviewed catalog's 2 GiB limit only for its exact
    # content-addressed archive. Other files retain the smaller default bound.
    plan['imageArchive']=copy.deepcopy(b.get('catalog',{}).get('imageArchive'))
    database={k:b[k] for k in ['clusterId','authoritySiteId','authorityInstanceId','authorityDatabaseFingerprint','controllerName']}
    database.update(stateId=request['sharedStateId'],requireStandby=False)
    probes={'sourceAddress':request['sourceAddress'],'targetAddress':request['targetAddress'],'targetServerId':request['targetServerId'],
        'stateId':request['sharedStateId'],'stateKeySha256':state['stateKeySha256'],'ownerUid':state['ownerUid'],'ownerGid':state['ownerGid'],
        'dependencyId':dep['id'],'dependencyAccount':dp.account(request['targetServerId']),'sshSocket':'/run/'+dp.runtime_name(dep['id'])+'/control',
        'database':database,'controllerJar':str(CONTROL/'acceptance'/(b['controllerSha256']+'.jar')),'uiFiles':b['uiFiles']}
    probes['nodeTokenFile']=b['properties']['app.control.node-token-file']
    value={'protocol':PROTOCOL,'controllerBinding':c.fields(header,'STAGE')['binding'],'approvalDigest':header['approvalDigest'],'plan':plan,
        'observation':observe(plan),'probes':probes};require(len(canonical(value))<=1048576,'BOOTSTRAP_BUSINESS_SERVICES_BASELINE_TOO_LARGE')
    fresh_probes(probes);require(observe(plan)==value['observation'],'BOOTSTRAP_BUSINESS_SERVICES_CHANGED_DURING_PROBE');return value
def nfs_identity(p):
    n=types.ModuleType('reviewed_services_nfs_policy');exec(compile(base64.b64decode(RPC_SOURCE),'<reviewed-services-nfs-policy>','exec'),n.__dict__)
    return n.NfsReadProbe(p['sourceAddress'],p['targetAddress'],p['ownerUid'],p['ownerGid']).identity(p['stateId'],p['stateKeySha256'])
def fresh_probes(p):
    # Procfs-only guard runs before any application probe; the RPC read does not
    # traverse an unreachable hard NFS mount or initialize replacement state.
    run(['/usr/bin/python3','-I','/etc/eatwhat-control-state-mount-check.py'],timeout=5)
    proof=nfs_identity(p)
    require(proof.get('stateIdentityVerified') is True and proof.get('boundedReadOnlyRpc') is True,'BOOTSTRAP_BUSINESS_SERVICES_STATE_UNVERIFIED')
    run(['/usr/sbin/runuser','-u','eatwhat-control','--','/usr/bin/ssh','-F','/dev/null','-S',p['sshSocket'],'-O','check','--',p['dependencyAccount']+'@'+p['sourceAddress']],timeout=8)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open('http://127.0.0.1:18081/actuator/health/readiness',timeout=4) as response:
        require(response.status==200 and json.loads(response.read(65537)).get('status')=='UP','BOOTSTRAP_BUSINESS_SERVICES_CONTROLLER_UNREADY')
    for name,sha in p['uiFiles'].items():
        with opener.open('http://127.0.0.1:18081/control-ui/'+name,timeout=4) as response:
            require(response.status==200 and hashlib.sha256(response.read(4194305)).hexdigest()==sha,'BOOTSTRAP_BUSINESS_SERVICES_HTTP_UI_CHANGED')
    for path in ['/admin/control/v1/cluster','/admin/control/v1/account']:
        try:
            with opener.open('http://127.0.0.1:18081'+path,timeout=4):raise Refusal('BOOTSTRAP_BUSINESS_SERVICES_ANONYMOUS_ADMIN_ACCEPTED')
        except urllib.error.HTTPError as failure:require(failure.code==401,'BOOTSTRAP_BUSINESS_SERVICES_AUTH_POLICY_CHANGED');failure.close()
    args=['/usr/sbin/runuser','-u','eatwhat-control','--','/usr/bin/env','-C','/','/usr/bin/java','-Dloader.home=/',
        '-Dloader.config.location=file:/dev/null','-Dloader.path=','-Dloader.main=you.v50to.eatwhat.control.bootstrap.ControllerRuntimeProbe',
        '-cp',p['controllerJar'],'org.springframework.boot.loader.launch.PropertiesLauncher',str(CONTROL/'config/application.properties')]
    actual=json.loads(run(args,canonical(p['database']),timeout=30))
    require(all(actual.get(k)==v for k,v in p['database'].items() if k!='requireStandby') and actual.get('readOnlyTransaction') is True
        and actual.get('actualDatabaseIdentityVerified') is True and actual.get('leaseLive') is True,'BOOTSTRAP_BUSINESS_SERVICES_DATABASE_UNVERIFIED')
    return {'boundedNfsIdentityVerified':True,'mountGuardVerified':True,'privateSshMasterVerified':True,'controllerReadiness':'UP',
        'httpUiFilesVerified':len(p['uiFiles']),'anonymousAdminDenied':True,'readOnlyAuthorityDatabaseVerified':True,'currentControllerLeaseLive':True}
def strict_json(raw):
    def pairs(rows):
        result={}
        for key,value in rows:require(key not in result,'BOOTSTRAP_PROFILE_SERVICES_RECORD_CHANGED');result[key]=value
        return result
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=lambda _:require(False,'BOOTSTRAP_PROFILE_SERVICES_RECORD_CHANGED'))
def profile_private(path,maximum,uid):
    require(all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_PROFILE_SERVICES_UNSAFE_FILE')
    with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW),'rb') as source:
        info=os.fstat(source.fileno());require(stat.S_ISREG(info.st_mode) and info.st_uid in [0,uid]
            and stat.S_IMODE(info.st_mode)==0o600 and 0<info.st_size<=maximum,'BOOTSTRAP_PROFILE_SERVICES_UNSAFE_FILE');raw=source.read(maximum+1)
    require(len(raw)<=maximum,'BOOTSTRAP_PROFILE_SERVICES_UNSAFE_FILE');return raw
def profile_signature(value,key):
    require(isinstance(value,dict) and isinstance(value.get('signature'),str),'BOOTSTRAP_PROFILE_SERVICES_RECORD_CHANGED')
    fields={k:v for k,v in value.items() if k!='signature'}
    signature=base64.b64encode(hmac.digest(key,canonical(fields),'sha256')).decode()
    require(hmac.compare_digest(signature,value['signature']),'BOOTSTRAP_PROFILE_SERVICES_RECORD_CHANGED')
def profile_journal(server):
    legacy=CONTROL/'profile-publications'
    require(not legacy.is_symlink(),'BOOTSTRAP_PROFILE_SERVICES_UNSAFE_FILE')
    prior=legacy/server
    require(not prior.exists() and not prior.is_symlink(),'BOOTSTRAP_PROFILE_SERVICES_RETAINED_LAYOUT')
    return CONTROL/'config'/'profile-publications'/server
def profile_successor(request,baseline):
    """Derive the only permitted config successor from signed local evidence.
    No new baseline is captured and no files, services or receipts are written.
    """
    server=request['serverId'];require(isinstance(server,str) and re.fullmatch('[A-Za-z0-9_-]{1,64}',server),'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    task=profile_journal(server);path=task/'services-successor.json'
    if not path.exists() and not path.is_symlink():return baseline['plan'],baseline['observation'],None
    require(str(uuid.UUID(server))==server,'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    uid=baseline['probes']['ownerUid'];gid=baseline['probes']['ownerGid']
    for directory in [task,task.parent]:
        require(all(not p.is_symlink() for p in [directory,*directory.parents]),'BOOTSTRAP_PROFILE_SERVICES_UNSAFE_FILE')
        info=directory.stat();require(stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode)==0o700
            and info.st_uid==uid and info.st_gid==gid,'BOOTSTRAP_PROFILE_SERVICES_UNSAFE_FILE')
    token_path=Path(baseline['probes']['nodeTokenFile']);require(token_path.parent==CONTROL/'config' and str(token_path)==baseline['probes']['nodeTokenFile']
        and str(token_path) in baseline['observation']['files'],'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    token_raw=profile_private(token_path,256,uid);require(hashlib.sha256(token_raw).hexdigest()==baseline['observation']['files'][str(token_path)]['sha256'],
        'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    key=token_raw.decode('ascii').strip().encode('ascii');require(re.fullmatch(b'[A-Za-z0-9_-]{43,128}',key),'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    witness=strict_json(profile_private(path,8192,uid));profile_signature(witness,key)
    require(set(witness)=={'protocol','bootstrapId','serverId','approvalDigest','businessJobId','businessBinding','baselineSha256','controllerBinding',
        'acceptanceBinding','profilePlanBinding','controllerId','applicationSha256','nodeTokenFile','joinAuthorized','signature'},'BOOTSTRAP_PROFILE_SERVICES_RECORD_CHANGED')
    require(witness['protocol']==PROFILE_PROTOCOL and witness['serverId']==server and witness['businessJobId']==request['jobId']
        and witness['businessBinding']==request['inputBinding'] and witness['approvalDigest']==request['approvalDigest']
        and witness['controllerBinding']==request['controllerBinding'] and witness['baselineSha256']==digest(baseline)
        and witness['nodeTokenFile']==str(token_path) and witness['joinAuthorized'] is False,'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    require(str(uuid.UUID(witness['bootstrapId']))==witness['bootstrapId'] and uuid.UUID(witness['bootstrapId']).int!=0
        and str(uuid.UUID(witness['controllerId']))==witness['controllerId'] and uuid.UUID(witness['controllerId']).int!=0
        and re.fullmatch('[A-Za-z0-9+/]{43}=',witness['acceptanceBinding']),'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    expected_job=str(uuid.UUID(bytes=hashlib.md5((witness['bootstrapId']+':BUSINESS_INSTALL').encode()).digest(),version=3))
    require(request['jobId']==expected_job,'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    plan=strict_json(profile_private(task/'plan.json',1048576,uid))
    require(set(plan)=={'bootstrapId','serverId','member','profilesFile','beforeBase64','afterBase64','beforeManifest','afterManifest','additionsBase64','binding'},'BOOTSTRAP_PROFILE_SERVICES_RECORD_CHANGED')
    binding=digest([plan[k] for k in ['bootstrapId','serverId','member','profilesFile','beforeBase64','afterBase64','beforeManifest','afterManifest','additionsBase64']])
    require(binding==plan['binding']==witness['profilePlanBinding'] and plan['bootstrapId']==witness['bootstrapId'] and plan['serverId']==server
        and plan['profilesFile']==str(CONTROL/'config/cluster-profiles.json'),'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    intent=strict_json(profile_private(task/'intent.json',8192,uid))
    require(intent=={'protocol':'eatwhat-cluster-profile-publication-v1','bootstrapId':plan['bootstrapId'],'serverId':server,'binding':binding,
        'state':'CLAIMED','beforeBinding':digest(plan['beforeManifest']),'afterBinding':digest(plan['afterManifest']),'joinAuthorized':False},'BOOTSTRAP_PROFILE_SERVICES_RECORD_CHANGED')
    profile=plan['profilesFile'];config=CONTROL/'config';old=baseline['observation'];new_plan=copy.deepcopy(baseline['plan']);expected=copy.deepcopy(old)
    require(profile in old['files'] and str(config) in old['directories'],'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    for name,value in plan['beforeManifest'].items():
        require(Path(name).parent==config and name in old['files'] and old['files'][name]['sha256']==value,'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    before=base64.b64decode(plan['beforeBase64'],validate=True);after=base64.b64decode(plan['afterBase64'],validate=True)
    require(hashlib.sha256(before).hexdigest()==old['files'][profile]['sha256']==plan['beforeManifest'][profile], 'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    require(set(plan['additionsBase64'])=={str(config/('bootstrap-member-'+server+suffix)) for suffix in ['.token','.ca.pem']},'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    after_manifest={**plan['beforeManifest'],profile:hashlib.sha256(after).hexdigest()}
    for name,encoded in plan['additionsBase64'].items():
        raw=base64.b64decode(encoded,validate=True);require(name not in old['files'] and Path(name).name not in old['directories'][str(config)]['entries']
            and 0<len(raw)<=65536,'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
        after_manifest[name]=hashlib.sha256(raw).hexdigest()
        expected['files'][name]={**old['files'][profile],'sha256':after_manifest[name],'size':len(raw)}
    require(after_manifest==plan['afterManifest'],'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    expected['files'][profile]={**old['files'][profile],'sha256':after_manifest[profile],'size':len(after)}
    # The signed publication creates this one service-private metadata
    # directory. Its owner/mode and original task files were checked above;
    # every other unexpected config entry must still fail the baseline.
    require('profile-publications' not in old['directories'][str(config)]['entries'],'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    expected['directories'][str(config)]['entries']=sorted(old['directories'][str(config)]['entries']
        +[Path(p).name for p in plan['additionsBase64']]+['profile-publications'])
    new_plan['files']=sorted(set(new_plan['files'])|set(plan['additionsBase64']))
    token=Path(witness['nodeTokenFile']);require(token.parent==config and str(token)==witness['nodeTokenFile'] and str(token) in plan['beforeManifest'],
        'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    require(witness['applicationSha256']==old['files'][str(config/'application.properties')]['sha256'],'BOOTSTRAP_PROFILE_SERVICES_BINDING_CHANGED')
    # The key never leaves this function/its read-only status verifier.
    return new_plan,expected,witness
class ProfileNoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,request,fp,code,message,headers,newurl):return None
def profile_runtime(witness,baseline):
    uid=baseline['probes']['ownerUid']
    token=profile_private(Path(witness['nodeTokenFile']),256,uid).decode('ascii').strip()
    require(re.fullmatch('[A-Za-z0-9_-]{43,128}',token),'BOOTSTRAP_PROFILE_SERVICES_RECORD_CHANGED')
    frame={'id':witness['bootstrapId'],'role':'TARGET','binding':witness['acceptanceBinding']}
    outgoing=urllib.request.Request(PROFILE_ENDPOINT,data=canonical(frame),method='POST',headers={'Content-Type':'application/json','X-Control-Node-Token':token})
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),ProfileNoRedirect())
    try:
        with opener.open(outgoing,timeout=4) as response:
            require(response.status==200 and response.headers.get_content_type()=='application/json','BOOTSTRAP_PROFILE_SERVICES_RUNTIME_UNCONFIRMED');raw=response.read(8193)
    except urllib.error.HTTPError as failure:failure.close();raise Refusal('BOOTSTRAP_PROFILE_SERVICES_RUNTIME_UNCONFIRMED') from None
    require(len(raw)<=8192,'BOOTSTRAP_PROFILE_SERVICES_RUNTIME_UNCONFIRMED');receipt=strict_json(raw);profile_signature(receipt,token.encode('ascii'))
    expected={'protocol':'eatwhat-controller-profile-services-runtime-v1','bootstrapId':witness['bootstrapId'],'role':'TARGET','binding':witness['acceptanceBinding'],
        'planBinding':witness['profilePlanBinding'],'controllerId':witness['controllerId'],'controllerName':baseline['probes']['database']['controllerName'],
        'applicationSha256':witness['applicationSha256'],'state':'RUNTIME_RELOADED','joinAuthorized':False}
    require(set(receipt)==set(expected)|{'observedBinding','signature'} and all(receipt[k]==v for k,v in expected.items())
        and re.fullmatch('[a-f0-9]{64}',receipt['observedBinding']),'BOOTSTRAP_PROFILE_SERVICES_RUNTIME_UNCONFIRMED')
    task=profile_journal(witness['serverId']);plan=strict_json(profile_private(task/'plan.json',1048576,uid))
    require(receipt['observedBinding']==digest(plan['afterManifest']),'BOOTSTRAP_PROFILE_SERVICES_RUNTIME_UNCONFIRMED')

def verify(request,baseline):
    require(baseline['protocol']==PROTOCOL and baseline['approvalDigest']==request['approvalDigest']
        and baseline['controllerBinding']==request['controllerBinding'] and baseline['probes']['targetServerId']==request['serverId'],
        'BOOTSTRAP_BUSINESS_SERVICES_BINDING_CHANGED')
    plan,expected,witness=profile_successor(request,baseline)
    current=observe(plan);require(current==expected,'BOOTSTRAP_BUSINESS_SERVICES_CONFIGURATION_CHANGED')
    if witness is not None:profile_runtime(witness,baseline)
    probes=fresh_probes(baseline['probes']);require(profile_successor(request,baseline)==(plan,expected,witness)
        and observe(plan)==current,'BOOTSTRAP_BUSINESS_SERVICES_CHANGED_DURING_PROBE')
    return {'protocol':PROTOCOL,'state':'CONTROLLER_SERVICES_REVALIDATED','jobId':request['jobId'],'serverId':request['serverId'],
        'inputBinding':request['inputBinding'],'approvalDigest':request['approvalDigest'],'controllerBinding':request['controllerBinding'],
        'baselineSha256':digest(baseline),'observationSha256':digest(current),'controllerServicesRevalidated':True,**probes,
        'firewallRevalidationRequired':True,'ipv6RevalidationRequired':True,'businessHealthVerified':False,'joinAuthorized':False,
        'controllerProfilesSuccessorVerified':witness is not None,'profilePlanBinding':None if witness is None else witness['profilePlanBinding']}

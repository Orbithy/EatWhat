"""Private NFS state phase, under development; no management write route.

Only the immutable, approved local link agent is loaded. Inspection facts and
identity digests are private manager inputs, never a public HTTP response DTO.
Original-state ownership/key are preserved; a client never initializes state.
"""
import base64
import fcntl
import grp
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import stat
import sys
import time
import types
import uuid

POLICY_SOURCE='__EATWHAT_CONTROLLER_STATE_POLICY_BASE64__'
_POLICY=None
_LINK=None
ROOT_UID=0
BASE=Path('/var/lib/eatwhat-controller-bootstrap-state')
LINK_BASE=Path('/var/lib/eatwhat-controller-bootstrap-link')
CONTROL=Path('/opt/eatwhat-control')
ETC=Path('/etc')
SYSTEM=ETC/'systemd/system'
PROTOCOL='eatwhat-controller-bootstrap-state-v1'
MAX_INPUT=524288
FW_NAME='eatwhat-control-state-firewall.service'
CLIENT_FW_NAME='eatwhat-control-state-client-firewall.service'
NFS_GENERATED=Path('/run/systemd/generator/nfs-server.service.d/order-with-mounts.conf')


class Refusal(Exception):
    def __init__(self,code):self.code=code;super().__init__(code)


def require(value,code='BOOTSTRAP_STATE_PRECONDITION_FAILED'):
    if not value:raise Refusal(code)


def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def digest(value):return hashlib.sha256(value).hexdigest()


def policy():
    global _POLICY
    if _POLICY is None:
        value=types.ModuleType('reviewed_state_policy')
        exec(compile(base64.b64decode(POLICY_SOURCE),'<reviewed-state-policy>','exec'),value.__dict__);_POLICY=value
    return _POLICY


def private(path,maximum=MAX_INPUT):
    require(all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_STATE_UNSAFE_PATH')
    require(path.is_file() and path.parent.is_dir(),'BOOTSTRAP_STATE_PRIVATE_FILE_MISSING')
    a,b=path.stat(),path.parent.stat()
    require(a.st_uid==ROOT_UID and stat.S_IMODE(a.st_mode)==0o600 and a.st_size<=maximum
        and b.st_uid==ROOT_UID and stat.S_IMODE(b.st_mode)==0o700,'BOOTSTRAP_STATE_UNSAFE_PATH')
    return path.read_bytes()


def identifier(value):
    try:require(str(uuid.UUID(value))==value and uuid.UUID(value).int!=0,'BOOTSTRAP_STATE_INVALID_IDENTITY')
    except (ValueError,TypeError,AttributeError):raise Refusal('BOOTSTRAP_STATE_INVALID_IDENTITY')


def validate(data,kind='header'):
    fields={'id','linkId','linkBinding','approvalDigest'}
    if kind!='metadata':fields|={'ownerUid','ownerGid','idmapDomain','stateKeySha256'}
    if kind in ['header','dispatch']:fields|={'expectedProof','agentSha256'}
    if kind=='dispatch':fields.add('agent')
    require(isinstance(data,dict) and set(data)==fields,'BOOTSTRAP_STATE_INVALID_INPUT')
    identifier(data['id']);identifier(data['linkId']);require(data['id']!=data['linkId'])
    for name in ['linkBinding','approvalDigest','stateKeySha256','expectedProof','agentSha256']:
        if name in data:require(isinstance(data[name],str) and re.fullmatch('[a-f0-9]{64}',data[name]),'BOOTSTRAP_STATE_INVALID_BINDING')
    if kind!='metadata':policy().export_options(data['ownerUid'],data['ownerGid']);policy().domain(data['idmapDomain'])
    if kind=='dispatch':require(isinstance(data['agent'],str) and len(data['agent'].encode())<=MAX_INPUT
        and digest(data['agent'].encode())==data['agentSha256'],'BOOTSTRAP_STATE_AGENT_CHANGED')
    return data


def link(data):
    global _LINK
    path=LINK_BASE/data['linkId']
    header=json.loads(private(path/'link-input.json'))
    require(set(header)=={'id','runtimeId','runtimeBinding','approvalDigest','peerPublicKey','agentSha256'}
        and header['id']==data['linkId'] and digest(canonical(header))==data['linkBinding']
        and header['approvalDigest']==data['approvalDigest'],'BOOTSTRAP_STATE_LINK_BINDING_CHANGED')
    source=private(path/'link-agent.py');require(digest(source)==header['agentSha256'],'BOOTSTRAP_STATE_LINK_AGENT_CHANGED')
    if _LINK is None:
        value=types.ModuleType('reviewed_prior_link')
        exec(compile(source,'<reviewed-prior-link>','exec'),value.__dict__);_LINK=value
    return _LINK,header


def context(data):
    l,header=link(data);proof=l.status(header)
    require(proof['state']=='CONFIGURED','BOOTSTRAP_STATE_LINK_NOT_CONFIGURED')
    runtime_header,ready,original=l.completed_runtime(header)
    request=runtime_header['request']
    return l,l.runtime(),header,runtime_header,request,original


def command(data,args,timeout=15):
    return context_runtime(data).run(args,timeout=timeout)


def context_runtime(data):return link(data)[0].runtime()


def config(path,maximum=262144):
    if not path.exists() and not path.is_symlink():return None
    require(all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_STATE_UNSAFE_CONFIG')
    require(path.is_file(),'BOOTSTRAP_STATE_UNSAFE_CONFIG');info=path.stat()
    require(info.st_uid==ROOT_UID and info.st_mode&0o022==0 and info.st_size<=maximum,'BOOTSTRAP_STATE_UNSAFE_CONFIG')
    return path.read_bytes()


def fragments(directory,pattern):
    if not directory.exists() and not directory.is_symlink():return {}
    require(directory.is_dir() and not directory.is_symlink(),'BOOTSTRAP_STATE_UNSAFE_CONFIG')
    rows=list(directory.glob(pattern));require(len(rows)<=64,'BOOTSTRAP_STATE_TOO_MANY_CONFIGS')
    return {str(path):config(path) for path in sorted(rows)}


def identity_files(data,request):
    state=CONTROL/'state'
    require(state.is_dir() and not state.is_symlink() and not os.path.ismount(state),'BOOTSTRAP_STATE_LOCAL_OWNER_REQUIRED')
    info=state.stat();require(info.st_uid==data['ownerUid'] and info.st_gid==data['ownerGid']
        and stat.S_IMODE(info.st_mode)==0o700,'BOOTSTRAP_STATE_OWNER_CHANGED')
    results=[]
    for name,size in [('controller-state-id',36),('servers.key',32)]:
        path=state/name
        require(all(not p.is_symlink() for p in [path,*path.parents]) and path.is_file(),'BOOTSTRAP_STATE_IDENTITY_CHANGED')
        attrs=path.stat();require(attrs.st_size==size and attrs.st_uid==data['ownerUid']
            and stat.S_IMODE(attrs.st_mode)==0o600,'BOOTSTRAP_STATE_IDENTITY_CHANGED')
        results.append(path.read_bytes())
    require(results[0].decode()==request['sharedStateId'] and digest(results[1])==data['stateKeySha256'],
        'BOOTSTRAP_STATE_IDENTITY_CHANGED')
    return {'uid':info.st_uid,'gid':info.st_gid,'stateId':request['sharedStateId']}


def metadata(data):
    validate(data,'metadata');l,r,lh,rh,request,original=context(data)
    require(request['role']=='SOURCE','BOOTSTRAP_STATE_SOURCE_METADATA_ONLY')
    host=original['facts']['host']
    result={'ownerUid':host['stateOwnerUid'],'ownerGid':host['stateOwnerGid'],
            'stateKeySha256':host['_sharedKeySha256'],'idmapDomain':r.run(['nfsidmap','-d'],timeout=8).decode().strip()}
    policy().domain(result['idmapDomain']);identity_files(result,request)
    return {'protocol':PROTOCOL,'id':data['id'],'effectsStarted':False,'privateMetadata':result}


def nft(data,name=None):
    args=['nft','-j','list','ruleset'] if name is None else ['nft','-j','list','table','inet',name]
    return json.loads(command(data,args))


def nft_has(document,name):
    return any(row.get('table',{}).get('family')=='inet' and row['table'].get('name')==name for row in document.get('nftables',[]))


def other_nft(document,name):
    rows=[]
    for obj in document.get('nftables',[]):
        if 'metainfo' in obj:continue
        value=next(iter(obj.values()))
        if isinstance(value,dict) and value.get('family')=='inet' and (value.get('table')==name or 'table' in obj and value.get('name')==name):continue
        rows.append(policy().nft_normalize(obj))
    return digest(canonical(rows))


def account_plan(data):
    try:user=pwd.getpwnam('eatwhat-control')
    except KeyError:user=None
    try:group=grp.getgrnam('eatwhat-control')
    except KeyError:group=None
    if user is not None:
        require(user.pw_uid==data['ownerUid'] and user.pw_gid==data['ownerGid'] and user.pw_shell=='/usr/sbin/nologin'
            and user.pw_dir==str(CONTROL),'BOOTSTRAP_STATE_ACCOUNT_CONFLICT')
    else:
        try:pwd.getpwuid(data['ownerUid'])
        except KeyError:pass
        else:raise Refusal('BOOTSTRAP_STATE_UID_CONFLICT')
    if group is not None:require(group.gr_gid==data['ownerGid'],'BOOTSTRAP_STATE_GROUP_CONFLICT')
    else:
        try:grp.getgrgid(data['ownerGid'])
        except KeyError:pass
        else:raise Refusal('BOOTSTRAP_STATE_GID_CONFLICT')
    return {'createUser':user is None,'createGroup':group is None,'uid':data['ownerUid'],'gid':data['ownerGid']}


def nfs_config(address,legacy=False):
    return ('[nfsd]\nhost='+address+'\nport=2049\nthreads=4\n'+('' if legacy else 'vers2=n\n')+
        'vers3=n\nvers4=y\nudp=n\ntcp=y\nrdma=n\n').encode()


def paths():
    return {'export':ETC/'exports.d/eatwhat-control.exports','nfs':ETC/'nfs.conf.d/90-eatwhat-control.conf',
        'firewall':ETC/'eatwhat-control-state.nft','owner':ETC/'eatwhat-control-state.owner.json',
        'clientFirewall':ETC/'eatwhat-control-state-client.nft','clientOwner':ETC/'eatwhat-control-state.client.json'}


def server_units(clients,legacy=False):
    return {
        SYSTEM/FW_NAME:b'''[Unit]
Description=Private EatWhat management-state network guard
After=wg-quick@ewctrl0.service
Requires=wg-quick@ewctrl0.service
Before=nfs-server.service
[Service]
Type=oneshot
ExecStart=/usr/sbin/nft -f /etc/eatwhat-control-state.nft
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
''',
        SYSTEM/'nfs-server.service.d/eatwhat-private.conf':b'''[Unit]
Requires=wg-quick@ewctrl0.service eatwhat-control-state-firewall.service
After=wg-quick@ewctrl0.service eatwhat-control-state-firewall.service
BindsTo=wg-quick@ewctrl0.service
''',
        SYSTEM/'nfs-mountd.service.d/eatwhat-private.conf':('''[Service]
IPAddressDeny=any
IPAddressAllow=127.0.0.0/8
'''+('' if legacy else 'IPAddressAllow=::1/128\n')+''.join('IPAddressAllow='+ip+'/32\n' for ip in sorted(clients))+'''Type=simple
ExecStart=
ExecStart=/usr/sbin/rpc.mountd --foreground --no-tcp --no-udp --no-nfs-version 2 --no-nfs-version 3
''').encode()}


def generated_mount_ordering():
    raw=config(NFS_GENERATED,4096)
    if raw is None:return {}
    expected=('# Automatically generated by nfs-server-generator\n\n[Unit]\nRequiresMountsFor='+str(CONTROL/'state')+'\n').encode()
    require(raw==expected,'BOOTSTRAP_STATE_SERVICE_OVERRIDE')
    return {str(NFS_GENERATED):digest(raw)}


def unit_inventory(data,mode,clients):
    l,r,lh,rh,request,original=context(data)
    guards_path=r.task(rh)/'service-guards.json'
    guards=json.loads(r.private(guards_path)) if guards_path.exists() else []
    r.verify_service_guards(rh)
    expected={g['path']:g['sha256'] for g in guards}
    expected.update(generated_mount_ordering())
    units=server_units(clients,legacy=mode=='LEGACY') if mode!='FRESH' else {}
    if mode=='FRESH':
        require(command(data,['systemctl','show','--property=LoadState','--value',FW_NAME]).strip()==b'not-found',
            'BOOTSTRAP_STATE_SERVICE_OVERRIDE')
    else:
        require(command(data,['systemctl','show','--property=FragmentPath','--value',FW_NAME]).decode().strip()==str(SYSTEM/FW_NAME)
            and command(data,['systemctl','show','--property=DropInPaths','--value',FW_NAME]).strip()==b'',
            'BOOTSTRAP_STATE_SERVICE_OVERRIDE')
    expected.update({str(p):digest(v) for p,v in units.items() if p.name=='eatwhat-private.conf'})
    observed={}
    for name in r.RPC_UNITS+r.NFS_UNITS:
        fragment=command(data,['systemctl','show','--property=FragmentPath','--value',name]).decode().strip()
        require(fragment.startswith(('/usr/lib/systemd/system/','/lib/systemd/system/')) or
            name in r.RPC_UNITS and fragment=='/dev/null','BOOTSTRAP_STATE_SERVICE_OVERRIDE')
        dropins=command(data,['systemctl','show','--property=DropInPaths','--value',name]).decode().split()
        for dest in dropins:
            require(dest in expected and digest(config(Path(dest)) or b'')==expected[dest],'BOOTSTRAP_STATE_SERVICE_OVERRIDE')
        # A deleted drop-in is not safe simply because systemd still remembers it.
        for dest,sha in expected.items():
            if str(SYSTEM/(name+'.d'))+'/' in dest:
                canonical_alias=str(SYSTEM/'nfs-server.service.d/90-eatwhat-bootstrap.conf')
                alias_guard=(name=='nfs-kernel-server.service' and Path(dest).name=='90-eatwhat-bootstrap.conf'
                    and canonical_alias in dropins and expected.get(canonical_alias)==sha)
                require(dest in dropins or alias_guard,'BOOTSTRAP_STATE_SERVICE_OVERRIDE')
        state=command(data,['systemctl','show','--property=ActiveState','--value',name]).decode().strip()
        require(state in (['inactive','failed'] if mode=='FRESH' or name in r.RPC_UNITS else ['active']),
            'BOOTSTRAP_STATE_SERVICE_UNEXPECTED')
        observed[name]={'fragment':fragment,'dropIns':{p:digest(config(Path(p))) for p in dropins},'active':state}
    if mode=='FRESH':
        # New runtime versions also seal the newly installed stock firewall
        # service. Older, already-installed nftables has no owned drop-in.
        core=set(r.RPC_UNITS+r.NFS_UNITS);allowed=core|set(getattr(r,'NFT_UNITS',[]))
        require(guards and core<={g['unit'] for g in guards}<=allowed,
            'BOOTSTRAP_STATE_NFS_GUARDS_REQUIRED')
        require(not (r.task(rh)/'shared-state-services-ready').exists(),'BOOTSTRAP_STATE_GUARD_ALREADY_OPEN')
    require(not (r.task(rh)/'legacy-rpc-not-approved').exists(),'BOOTSTRAP_STATE_LEGACY_RPC_ENABLED')
    for path in server_units(clients):
        require(config(path)==units.get(path),'BOOTSTRAP_STATE_SERVICE_OVERRIDE')
    return observed


def source_inventory(data,request,original):
    p=paths();identity=identity_files(data,request)
    require(identity['uid']==original['facts']['host']['stateOwnerUid'] and identity['gid']==original['facts']['host']['stateOwnerGid']
        and data['stateKeySha256']==original['facts']['host']['_sharedKeySha256'],'BOOTSTRAP_STATE_OWNER_CHANGED')
    domain=command(data,['nfsidmap','-d'],8).decode().strip()
    require(domain==data['idmapDomain'],'BOOTSTRAP_STATE_IDMAP_DOMAIN_CHANGED')
    main=config(ETC/'exports')
    require(not policy().parse_exports((main or b'').decode(),str(CONTROL/'state'),data['ownerUid'],data['ownerGid']),
        'BOOTSTRAP_STATE_MAIN_EXPORT_CONFIGURED')
    main_nfs=policy().ini_active((config(ETC/'nfs.conf') or b'').decode())
    require(all(k in ['general','mountd'] for k in main_nfs)
        and main_nfs.get('general',{}) in [{},{'pipefs-directory':'/run/rpc_pipefs'}]
        and main_nfs.get('mountd',{}) in [{},{'manage-gids':'y'}],'BOOTSTRAP_STATE_FOREIGN_NFS_CONFIG')
    exports=fragments(ETC/'exports.d','*.exports');configs=fragments(ETC/'nfs.conf.d','*.conf')
    for name,raw in exports.items():
        if name!=str(p['export']):require(not policy().parse_exports(raw.decode(),str(CONTROL/'state'),data['ownerUid'],data['ownerGid']),
            'BOOTSTRAP_STATE_FOREIGN_EXPORT')
    for name,raw in configs.items():
        if name!=str(p['nfs']):require(not policy().ini_active(raw.decode()),'BOOTSTRAP_STATE_FOREIGN_NFS_CONFIG')
    export_raw=config(p['export']);clients=policy().parse_exports((export_raw or b'').decode(),str(CONTROL/'state'),data['ownerUid'],data['ownerGid'])
    firewall=config(p['firewall']);nfs=config(p['nfs']);owner=config(p['owner'])
    table=nft(data);present=nft_has(table,'eatwhat_control_state')
    if owner is not None:
        manifest=json.loads(owner)
        require(manifest.get('protocol')=='eatwhat-controller-state-owner-v1' and manifest.get('stateId')==request['sharedStateId']
            and manifest.get('sourceServerId')==request['sourceServerId'] and manifest.get('ownerUid')==data['ownerUid']
            and manifest.get('ownerGid')==data['ownerGid'] and manifest.get('clients')==sorted(clients),'BOOTSTRAP_STATE_OWNERSHIP_CONFLICT')
        expected_paths={str(p[n]) for n in ['export','nfs','firewall']}|{str(x) for x in server_units(clients)}
        require(set(manifest.get('files',{}))==expected_paths,'BOOTSTRAP_STATE_OWNERSHIP_CONFLICT')
        for name,sha in manifest['files'].items():
            path=Path(name);require(path.is_relative_to(ETC) and digest(config(path) or b'')==sha,'BOOTSTRAP_STATE_MANAGED_CONFIG_CHANGED')
        mode='OWNED'
    elif clients:
        require(len(clients)==1 and nfs==nfs_config(request['sourceAddress'],legacy=True)
            and firewall==policy().legacy_firewall_content(next(iter(clients))),'BOOTSTRAP_STATE_LEGACY_CONFIG_UNRECOGNIZED')
        mode='LEGACY'
    else:
        require(export_raw is None and nfs is None and firewall is None and not present,'BOOTSTRAP_STATE_CONFIGURATION_RETAINED')
        mode='FRESH'
    if clients:
        allowed=set()
        peer_map=link(data)[0].peer_map()
        for values in peer_map.values():
            for prefix in values:
                if re.fullmatch(r'[0-9.]+/32',prefix):allowed.add(prefix[:-3])
        require(set(clients)<=allowed,'BOOTSTRAP_STATE_EXPORT_NOT_A_PRIVATE_PEER')
        require(present,'BOOTSTRAP_STATE_FIREWALL_MISSING')
        policy().nft_guard_valid(nft(data,'eatwhat_control_state'),sorted(clients),legacy=mode=='LEGACY')
    unit_facts=unit_inventory(data,mode,clients)
    files={str(ETC/'exports'):main,str(ETC/'nfs.conf'):config(ETC/'nfs.conf'),str(ETC/'idmapd.conf'):config(ETC/'idmapd.conf'),
           **exports,**configs,**{str(path):config(path) for path in [p['firewall'],p['owner'],*server_units(clients)]}}
    return {'mode':mode,'clients':sorted(clients),'identity':identity,'fileHashes':{k:digest(v) if v is not None else None for k,v in files.items()},
        'unrelatedNftSha256':other_nft(table,'eatwhat_control_state'),'domain':domain,'units':unit_facts},files


def target_inventory(data,request):
    require(not CONTROL.exists() and not CONTROL.is_symlink(),'BOOTSTRAP_STATE_TARGET_RETAINED')
    for line in Path('/proc/self/mountinfo').read_text().splitlines():
        right=line.split(' - ',1)[1].split()
        require(right[0] not in ['nfs','nfs4'],'BOOTSTRAP_STATE_TARGET_OTHER_NFS')
    l,r,lh,rh,prior,original=context(data)
    r.verify_service_guards(rh)
    for name in r.RPC_UNITS:
        require(command(data,['systemctl','show','--property=ActiveState','--value',name]).strip() in [b'inactive',b'failed'],
            'BOOTSTRAP_STATE_LEGACY_RPC_ENABLED')
    fstab=config(ETC/'fstab') or b''
    for line in fstab.decode().splitlines():
        values=line.split('#',1)[0].split()
        require(len(values)<3 or values[2] not in ['nfs','nfs4'],'BOOTSTRAP_STATE_TARGET_OTHER_NFS')
    idmap=config(ETC/'idmapd.conf');require(idmap is not None,'BOOTSTRAP_STATE_IDMAP_CONFIG_MISSING')
    active=policy().ini_active(idmap.decode())
    require(set(active)<= {'general','mapping'} and set(active.get('general',{}))<= {'domain','verbosity'}
        and active.get('mapping',{}) in [{},{'nobody-user':'nobody','nobody-group':'nogroup'}],'BOOTSTRAP_STATE_TARGET_IDMAP_CONFIGURED')
    policy().idmap_content(idmap.decode(),data['idmapDomain'])
    p=paths()
    mount_unit=command(data,['systemd-escape','--path','--suffix=mount',str(CONTROL/'state')]).decode().strip()
    require(re.fullmatch(r'[a-zA-Z0-9_.\\-]+\.mount',mount_unit),'BOOTSTRAP_STATE_INVALID_MOUNT_UNIT')
    for path in [p['clientFirewall'],p['clientOwner'],SYSTEM/CLIENT_FW_NAME,SYSTEM/'eatwhat-control.service.d/20-bootstrap-state.conf',
                 SYSTEM/mount_unit,ETC/'eatwhat-control-state-mount-check.py']:
        require(config(path) is None,'BOOTSTRAP_STATE_TARGET_RETAINED')
    require(not (SYSTEM/'eatwhat-control.service.d').exists(),'BOOTSTRAP_STATE_TARGET_RETAINED')
    for name in ['eatwhat-control.service',CLIENT_FW_NAME,mount_unit]:
        require(command(data,['systemctl','show','--property=LoadState','--value',name]).strip()==b'not-found',
            'BOOTSTRAP_STATE_TARGET_RETAINED')
    idmap_fragment=command(data,['systemctl','show','--property=FragmentPath','--value','nfs-idmapd.service']).decode().strip()
    require(idmap_fragment.startswith(('/usr/lib/systemd/system/','/lib/systemd/system/')) and
        command(data,['systemctl','show','--property=DropInPaths','--value','nfs-idmapd.service']).strip()==b'',
        'BOOTSTRAP_STATE_IDMAP_SERVICE_OVERRIDE')
    idmap_active=command(data,['systemctl','show','--property=ActiveState','--value','nfs-idmapd.service']).decode().strip()
    require(idmap_active in ['active','inactive','failed'],'BOOTSTRAP_STATE_IDMAP_SERVICE_BUSY')
    table=nft(data);require(not nft_has(table,'eatwhat_control_state_client'),'BOOTSTRAP_STATE_OWNERSHIP_CONFLICT')
    return {'account':account_plan(data),'idmapSha256':digest(idmap),'fstabSha256':digest(fstab),
        'mountUnit':mount_unit,'idmapServiceActive':idmap_active,'idmapServiceFragment':idmap_fragment,
        'unrelatedNftSha256':other_nft(table,'eatwhat_control_state_client')},{str(ETC/'idmapd.conf'):idmap}


def inspect(data):
    validate(data,'inspect');l,r,lh,rh,request,original=context(data)
    role=request['role']
    r.run(['ping','-I','ewctrl0','-c','1','-W','2',request['targetAddress'] if role=='SOURCE' else request['sourceAddress']],timeout=5)
    facts,files=source_inventory(data,request,original) if role=='SOURCE' else target_inventory(data,request)
    return {'protocol':PROTOCOL,'id':data['id'],'role':role,'effectsStarted':False,'privateFacts':facts,
        'proof':digest(canonical({'input':data,'facts':facts}))}


def input_fields(data):return {k:v for k,v in data.items() if k not in ['agent','expectedProof','agentSha256']}
def fields(data):return {'protocol':PROTOCOL,'id':data['id'],'binding':digest(canonical({k:v for k,v in data.items() if k!='agent'}))}
def task(data):return BASE/data['id']
def unit(data):return 'eatwhat-controller-state-'+data['id']


def lock(data,create=False):
    r=context_runtime(data)
    if create and not BASE.exists():BASE.mkdir(mode=0o700)
    r.directory(BASE);path=BASE/'host.lock'
    if create and not path.exists():
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600);os.close(fd)
    private(path);stream=path.open('r+')
    try:fcntl.lockf(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except Exception:stream.close();raise Refusal('BOOTSTRAP_STATE_HOST_BUSY')
    return stream


def saved_receipt(data):
    path=task(data)/'state-receipt.json'
    if not path.exists() and not path.is_symlink():return None
    result=json.loads(private(path))
    require(all(result.get(k)==v for k,v in fields(data).items()),'BOOTSTRAP_STATE_BINDING_CHANGED')
    return result


def source_intent(data):
    original=inspect(input_fields(data))
    require(original['proof']==data['expectedProof'],'BOOTSTRAP_STATE_APPROVAL_EXPIRED')
    l,r,lh,rh,request,before=context(data);facts=original['privateFacts'];p=paths()
    clients=sorted(set(facts['clients'])|{request['targetAddress']})
    require(request['targetAddress'] not in facts['clients'],'BOOTSTRAP_STATE_CLIENT_ALREADY_EXPORTED')
    content={p['export']:policy().export_content(str(CONTROL/'state'),clients,data['ownerUid'],data['ownerGid']),
        p['nfs']:nfs_config(request['sourceAddress']),p['firewall']:policy().firewall_content(clients),**server_units(clients)}
    owner={'protocol':'eatwhat-controller-state-owner-v1','stateId':request['sharedStateId'],'sourceServerId':request['sourceServerId'],
        'ownerUid':data['ownerUid'],'ownerGid':data['ownerGid'],'clients':clients,'files':{str(k):digest(v) for k,v in content.items()}}
    content[p['owner']]=canonical(owner)
    return {'role':'SOURCE','clients':clients,'before':facts,'sourceAddress':request['sourceAddress'],
        'stateId':request['sharedStateId'],'runtimeId':rh['request']['id'],
        'desired':{str(k):base64.b64encode(v).decode() for k,v in content.items()}}


def mount_check_source(source):
    # PID1 and ExecStartPre inspect procfs only. Never stat/read a hard NFS
    # pathname here: that could indefinitely stall a service activation.
    return ('''import sys
rows=[]
with open('/proc/self/mountinfo') as stream:
 for line in stream:
  left,right=line.rstrip().split(' - ',1);a,b=left.split(),right.split()
  if a[4]=='/opt/eatwhat-control/state':rows.append((a,b))
ok=len(rows)==1
if ok:
 a,b=rows[0];options=set(a[5].split(','))|set(b[2].split(','))
 ok=b[0]=='nfs4' and b[1]==SOURCE and {'rw','hard','nosuid','nodev','vers=4.2','proto=tcp','sec=sys','local_lock=none'}<=options
sys.exit(0 if ok else 1)
''').replace('SOURCE',repr(source+':/')).encode()


def target_intent(data):
    original=inspect(input_fields(data))
    require(original['proof']==data['expectedProof'],'BOOTSTRAP_STATE_APPROVAL_EXPIRED')
    l,r,lh,rh,request,before=context(data);facts=original['privateFacts'];p=paths();mount=facts['mountUnit']
    source=request['sourceAddress']
    content={p['clientFirewall']:policy().client_firewall_content(source),
        ETC/'idmapd.conf':policy().idmap_content(config(ETC/'idmapd.conf').decode(),data['idmapDomain']),
        ETC/'eatwhat-control-state-mount-check.py':mount_check_source(source),
        SYSTEM/CLIENT_FW_NAME:b'''[Unit]
Description=Restrict private NFS transport to the host kernel management mount
After=wg-quick@ewctrl0.service
Requires=wg-quick@ewctrl0.service
[Service]
Type=oneshot
ExecStart=/usr/sbin/nft -f /etc/eatwhat-control-state-client.nft
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
''',
        SYSTEM/mount:('''[Unit]
Description=Shared EatWhat management state over the dedicated private link
Requires=wg-quick@ewctrl0.service eatwhat-control-state-client-firewall.service
After=network-online.target wg-quick@ewctrl0.service eatwhat-control-state-client-firewall.service
BindsTo=wg-quick@ewctrl0.service
[Mount]
What='''+source+''':/
Where=/opt/eatwhat-control/state
Type=nfs4
Options=vers=4.2,proto=tcp,port=2049,sec=sys,hard,timeo=50,retrans=2,actimeo=0,local_lock=none,nosuid,nodev,_netdev,retry=0
TimeoutSec=30
[Install]
WantedBy=remote-fs.target
''').encode(),
        SYSTEM/'eatwhat-control.service.d/20-bootstrap-state.conf':('''[Unit]
Requires='''+mount+'''
After='''+mount+'''
BindsTo='''+mount+'''
[Service]
ExecStartPre=/usr/bin/python3 /etc/eatwhat-control-state-mount-check.py
''').encode()}
    owner={'protocol':'eatwhat-controller-state-client-v1','stateId':request['sharedStateId'],
        'sourceServerId':request['sourceServerId'],'targetServerId':request['targetServerId'],
        'ownerUid':data['ownerUid'],'ownerGid':data['ownerGid'],'files':{str(k):digest(v) for k,v in content.items()}}
    content[p['clientOwner']]=canonical(owner)
    return {'role':'TARGET','before':facts,'stateId':request['sharedStateId'],'sourceAddress':source,
        'targetAddress':request['targetAddress'],'mountUnit':mount,
        'desired':{str(k):base64.b64encode(v).decode() for k,v in content.items()}}


def remote_identity(data,intent):
    return policy().NfsReadProbe(intent['sourceAddress'],intent['targetAddress'],data['ownerUid'],data['ownerGid']).identity(
        intent['stateId'],data['stateKeySha256'])


def wait_remote_identity(data,intent):
    deadline=time.monotonic()+120
    while True:
        try:return remote_identity(data,intent)
        except policy().NfsRefusal as error:
            require(error.status in [10008,10013] and time.monotonic()<deadline,
                'BOOTSTRAP_STATE_REMOTE_NOT_READY')
            # Only grace/delay may be retried, without any mutable NFS RPC.
            time.sleep(1)
        except (TimeoutError,ConnectionRefusedError):
            require(time.monotonic()<deadline,'BOOTSTRAP_STATE_REMOTE_NOT_READY')
            # Service initialization and auth-cache upcalls can outlast one
            # socket deadline. Retry only these bounded, read-only exchanges.
            time.sleep(1)


def verify_target(data,intent):
    require(intent['role']=='TARGET','BOOTSTRAP_STATE_ROLE_CHANGED')
    plan=account_plan(data);require(not plan['createUser'] and not plan['createGroup'],'BOOTSTRAP_STATE_ACCOUNT_CHANGED')
    base=CONTROL.stat();require(not CONTROL.is_symlink() and base.st_uid==data['ownerUid'] and base.st_gid==data['ownerGid']
        and stat.S_IMODE(base.st_mode)==0o700,'BOOTSTRAP_STATE_OWNER_CHANGED')
    for name,encoded in intent['desired'].items():
        require(config(Path(name))==base64.b64decode(encoded),'BOOTSTRAP_STATE_CONFIGURATION_CHANGED')
    require(digest(config(ETC/'fstab') or b'')==intent['before']['fstabSha256'],'BOOTSTRAP_STATE_FSTAB_CHANGED')
    policy().nft_client_guard_valid(nft(data,'eatwhat_control_state_client'),intent['sourceAddress'])
    require(other_nft(nft(data),'eatwhat_control_state_client')==intent['before']['unrelatedNftSha256'],
        'BOOTSTRAP_STATE_FOREIGN_FIREWALL_CHANGED')
    for name in [CLIENT_FW_NAME,intent['mountUnit']]:
        require(command(data,['systemctl','show','--property=FragmentPath','--value',name]).decode().strip()==str(SYSTEM/name)
            and command(data,['systemctl','show','--property=DropInPaths','--value',name]).strip()==b'',
            'BOOTSTRAP_STATE_SERVICE_OVERRIDE')
        require(command(data,['systemctl','show','--property=ActiveState','--value',name]).strip()==b'active',
            'BOOTSTRAP_STATE_MOUNT_NOT_ACTIVE')
        require(command(data,['systemctl','is-enabled',name]).strip()==b'enabled','BOOTSTRAP_STATE_MOUNT_NOT_PERSISTENT')
    command(data,['python3',str(ETC/'eatwhat-control-state-mount-check.py')],5)
    proof=json.loads(private(task(data)/'mounted-proof.json'))
    require(proof=={'binding':fields(data)['binding'],'serviceUserIdentityRead':True,'sharedKeySha256':data['stateKeySha256'],
        'stateId':intent['stateId'],'ownerUid':data['ownerUid'],'ownerGid':data['ownerGid']},'BOOTSTRAP_STATE_SERVICE_USER_UNVERIFIED')
    l,r,lh,rh,request,original=context(data);r.verify_service_guards(rh)
    for name in r.RPC_UNITS:
        require(command(data,['systemctl','show','--property=ActiveState','--value',name]).strip() in [b'inactive',b'failed'],
            'BOOTSTRAP_STATE_LEGACY_RPC_ENABLED')
    remote=remote_identity(data,intent)
    return {'role':'TARGET','mountVerified':True,'serviceUserIdentityRead':True,'localStateInitialized':False,
        'controllerStartedByStatePhase':False,'unrelatedFirewallPreserved':True,**remote}


def apply_target(data,intent):
    # Availability/identity must be proven before creating local state paths.
    wait_remote_identity(data,intent)
    plan=account_plan(data)
    if plan['createGroup']:command(data,['groupadd','--system','--gid',str(data['ownerGid']),'eatwhat-control'])
    if plan['createUser']:command(data,['useradd','--system','--uid',str(data['ownerUid']),'--gid','eatwhat-control',
        '--no-create-home','--home-dir',str(CONTROL),'--shell','/usr/sbin/nologin','eatwhat-control'])
    require(not CONTROL.exists() and not CONTROL.is_symlink(),'BOOTSTRAP_STATE_TARGET_RETAINED')
    CONTROL.mkdir(mode=0o700);os.chown(CONTROL,data['ownerUid'],data['ownerGid'])
    (CONTROL/'state').mkdir(mode=0o000)
    for name,encoded in intent['desired'].items():
        path=Path(name);raw=config(path)
        expected=intent['before']['idmapSha256'] if path==ETC/'idmapd.conf' else None
        require((digest(raw) if raw is not None else None)==expected,'BOOTSTRAP_STATE_CONFIGURATION_CHANGED')
        mode=0o644 if path.is_relative_to(SYSTEM) or path.name.endswith('.py') else 0o600
        write_config(path,base64.b64decode(encoded),mode)
    p=paths();command(data,['nft','-c','-f',str(p['clientFirewall'])]);command(data,['nft','-f',str(p['clientFirewall'])])
    policy().nft_client_guard_valid(nft(data,'eatwhat_control_state_client'),intent['sourceAddress'])
    command(data,['systemctl','daemon-reload']);command(data,['systemctl','enable','--now',CLIENT_FW_NAME],30)
    if intent['before']['idmapServiceActive']=='active':
        # No other NFS mount/fstab exists on this from-zero target. Refresh only
        # its verified vendor mapper so a newly assigned domain cannot be stale.
        command(data,['systemctl','restart','nfs-idmapd.service'],20)
    command(data,['systemctl','enable','--now',intent['mountUnit']],40)
    command(data,['python3',str(ETC/'eatwhat-control-state-mount-check.py')],5)
    # This potentially blocking NFS read occurs only in the original worker's
    # cgroup, never status or PID1. A timeout retains UNKNOWN; no fallback init.
    check='''import hashlib,os,sys
from pathlib import Path
root=Path('/opt/eatwhat-control/state');a=root.stat()
assert a.st_uid==UID and a.st_gid==GID and a.st_mode&0o777==0o700
assert (root/'controller-state-id').read_text()==STATE
assert hashlib.sha256((root/'servers.key').read_bytes()).hexdigest()==SHA
'''.replace('UID',str(data['ownerUid'])).replace('GID',str(data['ownerGid'])).replace('STATE',repr(intent['stateId'])).replace('SHA',repr(data['stateKeySha256']))
    command(data,['runuser','-u','eatwhat-control','--','python3','-c',check],12)
    context_runtime(data).atomic(task(data)/'mounted-proof.json',{'binding':fields(data)['binding'],
        'serviceUserIdentityRead':True,'sharedKeySha256':data['stateKeySha256'],'stateId':intent['stateId'],
        'ownerUid':data['ownerUid'],'ownerGid':data['ownerGid']})


def write_config(path,content,mode):
    """Only reviewed /etc paths; owner and symlink checks also cover parents."""
    require(path.is_relative_to(ETC),'BOOTSTRAP_STATE_UNSAFE_CONFIG')
    for parent in reversed([path.parent,*path.parent.parents]):
        if parent==ETC or parent.is_relative_to(ETC):
            if not parent.exists():parent.mkdir(mode=0o755)
            attrs=parent.stat();require(not parent.is_symlink() and attrs.st_uid==ROOT_UID and attrs.st_mode&0o022==0,
                'BOOTSTRAP_STATE_UNSAFE_CONFIG')
    config(path)
    temporary=path.with_name('.eatwhat-state-'+str(uuid.uuid4()))
    try:
        with temporary.open('xb') as stream:
            os.fchmod(stream.fileno(),mode);stream.write(content);stream.flush();os.fsync(stream.fileno())
        os.replace(temporary,path)
        fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(fd)
        finally:os.close(fd)
    finally:temporary.unlink(missing_ok=True)


def verify_source(data,intent):
    l,r,lh,rh,request,original=context(data)
    require(request['role']=='SOURCE' and intent['role']=='SOURCE','BOOTSTRAP_STATE_ROLE_CHANGED')
    identity_files(data,request)
    unit_inventory(data,'OWNED',intent['clients'])
    for name,encoded in intent['desired'].items():
        require(config(Path(name))==base64.b64decode(encoded),'BOOTSTRAP_STATE_CONFIGURATION_CHANGED')
    for name,sha in intent['before']['fileHashes'].items():
        if name not in intent['desired']:
            raw=config(Path(name));require((digest(raw) if raw is not None else None)==sha,
                'BOOTSTRAP_STATE_FOREIGN_CONFIG_CHANGED')
    known=set(intent['before']['fileHashes'])|set(intent['desired'])
    require(set(fragments(ETC/'exports.d','*.exports'))|set(fragments(ETC/'nfs.conf.d','*.conf'))<=known,
        'BOOTSTRAP_STATE_FOREIGN_CONFIG_CHANGED')
    require(command(data,['nfsidmap','-d'],8).decode().strip()==data['idmapDomain'],'BOOTSTRAP_STATE_IDMAP_DOMAIN_CHANGED')
    table=nft(data)
    policy().nft_guard_valid(nft(data,'eatwhat_control_state'),intent['clients'])
    require(other_nft(table,'eatwhat_control_state')==intent['before']['unrelatedNftSha256'],
        'BOOTSTRAP_STATE_FOREIGN_FIREWALL_CHANGED')
    for name in ['nfs-server.service','nfs-mountd.service',FW_NAME]:
        require(command(data,['systemctl','show','--property=ActiveState','--value',name]).strip()==b'active',
            'BOOTSTRAP_STATE_SERVER_NOT_ACTIVE')
    require(command(data,['systemctl','is-enabled',FW_NAME]).strip()==b'enabled','BOOTSTRAP_STATE_GUARD_NOT_PERSISTENT')
    versions=Path('/proc/fs/nfsd/versions').read_text().split()
    require('+2' not in versions and '+3' not in versions and '+4' in versions,'BOOTSTRAP_STATE_NFS_VERSION_UNSAFE')
    live=command(data,['exportfs','-s']).decode()
    require(set(policy().parse_exports(live,str(CONTROL/'state'),data['ownerUid'],data['ownerGid'],live=True))==set(intent['clients']),
        'BOOTSTRAP_STATE_EXPORT_UNVERIFIED')
    listeners=[]
    for protocol in ['tcp','udp']:
        raw=command(data,['ss','-H','-ln'+('t' if protocol=='tcp' else 'u')]).decode()
        for line in raw.splitlines():
            columns=line.split();require(len(columns)>=5,'BOOTSTRAP_STATE_LISTENER_UNOBSERVABLE')
            local=columns[3];port=local.rsplit(':',1)[-1]
            if port in ['111','2049','20048']:listeners.append((protocol,local))
    require(listeners==[('tcp',request['sourceAddress']+':2049')],'BOOTSTRAP_STATE_LISTENER_UNSAFE')
    r.verify_service_guards(rh)
    require(not (r.task(rh)/'legacy-rpc-not-approved').exists(),'BOOTSTRAP_STATE_LEGACY_RPC_ENABLED')
    for name in r.RPC_UNITS:
        require(command(data,['systemctl','show','--property=ActiveState','--value',name]).strip() in [b'inactive',b'failed'],
            'BOOTSTRAP_STATE_LEGACY_RPC_ENABLED')
    # The original package/link receipts remain immutable and authoritative.
    return {'role':'SOURCE','clients':intent['clients'],'versions':'NFSv4_ONLY','exportVerified':True,
        'originalStateIdentityPreserved':True,'unrelatedFirewallPreserved':True,'publicNfsListener':False,
        'clientMountVerified':False}


def apply_source(data,intent):
    r=context_runtime(data);p=paths()
    # Files are sealed before service enablement. A partial write never opens
    # runtime's NFS condition marker or replays this phase under another ID.
    for name,encoded in intent['desired'].items():
        path=Path(name);previous=intent['before']['fileHashes'].get(name)
        actual=config(path)
        require((digest(actual) if actual is not None else None)==previous,'BOOTSTRAP_STATE_CONFIGURATION_CHANGED')
        write_config(path,base64.b64decode(encoded),0o644 if path.is_relative_to(SYSTEM) else 0o600)
    command(data,['nft','-c','-f',str(p['firewall'])])
    command(data,['nft','-f',str(p['firewall'])])
    policy().nft_guard_valid(nft(data,'eatwhat_control_state'),intent['clients'])
    command(data,['systemctl','daemon-reload'])
    command(data,['systemctl','enable','--now',FW_NAME],30)
    if intent['before']['mode']=='FRESH':
        marker=r.BASE/intent['runtimeId']/'shared-state-services-ready'
        require(not marker.exists() and not marker.is_symlink(),'BOOTSTRAP_STATE_GUARD_ALREADY_OPEN')
        r.atomic(marker,{'statePhaseId':data['id'],'binding':fields(data)['binding']})
        command(data,['systemctl','enable','--now','nfs-server.service'],45)
    else:
        # Existing NFS sessions are not restarted to append a second client.
        require(command(data,['systemctl','show','--property=ActiveState','--value','nfs-server.service']).strip()==b'active')
    command(data,['exportfs','-ra'])


def status(data):
    validate(data)
    if not BASE.exists() and not BASE.is_symlink():return {**fields(data),'state':'NOT_STARTED','effectsStarted':False}
    r=context_runtime(data);r.directory(BASE)
    reserve=BASE/'reservation.json'
    if reserve.exists() or reserve.is_symlink():
        require(json.loads(private(reserve))=={'id':data['id'],'binding':fields(data)['binding']},'BOOTSTRAP_STATE_HOST_RESERVED')
    else:require(all(p.name=='host.lock' for p in BASE.iterdir()),'BOOTSTRAP_STATE_RESERVATION_MISSING')
    saved=saved_receipt(data)
    if saved is None:return {**fields(data),'state':'OUTCOME_UNKNOWN' if reserve.exists() else 'NOT_STARTED','effectsStarted':False}
    if saved['state'] in ['EXPORTED','MOUNTED']:
        try:
            intent=json.loads(private(task(data)/'state-intent.json'))
            verify=verify_source if intent['role']=='SOURCE' else verify_target
            return {**saved,**verify(data,intent)}
        except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_STATE_CONFIGURATION_UNVERIFIED'}
    if saved['state'] in ['ACCEPTED','RUNNING','OUTCOME_UNKNOWN']:
        active=command(data,['systemctl','show','--property=ActiveState','--value',unit(data)]).strip()
        if active in [b'inactive',b'failed']:
            try:
                with lock(data):
                    require(saved.get('effectsStarted') is True,'BOOTSTRAP_STATE_ACCEPTANCE_UNCONFIRMED')
                    intent=json.loads(private(task(data)/'state-intent.json'))
                    verify=verify_source if intent['role']=='SOURCE' else verify_target
                    proof=verify(data,intent)
                    result={**saved,**proof,'state':'EXPORTED' if intent['role']=='SOURCE' else 'MOUNTED','derivedFromConfiguration':True}
                    if 'code' in result:result['originalReceiptCode']=result.pop('code')
                    return result
            except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_STATE_RECONCILIATION_REQUIRED'}
        require(active in [b'active',b'activating',b'deactivating',b'reloading'],'BOOTSTRAP_STATE_WORKER_UNOBSERVABLE')
    return saved


def dispatch(data):
    validate(data,'dispatch');header={k:v for k,v in data.items() if k!='agent'}
    previous=status(header)
    if previous['state']!='NOT_STARTED':return previous
    role=context(header)[4]['role']
    require(role in ['SOURCE','TARGET'],'BOOTSTRAP_STATE_ROLE_CHANGED')
    (source_intent if role=='SOURCE' else target_intent)(header)
    r=context_runtime(data)
    with lock(data,True):
        previous=status(header)
        if previous['state']!='NOT_STARTED':return previous
        r.atomic(BASE/'reservation.json',{'id':data['id'],'binding':fields(header)['binding']})
        path=task(data);require(not path.exists() and not path.is_symlink(),'BOOTSTRAP_STATE_TASK_RETAINED');path.mkdir(mode=0o700)
        r.atomic(path/'state-input.json',header);r.atomic(path/'state-agent.py',data['agent'].encode())
        saved={**fields(header),'state':'ACCEPTED','effectsStarted':False,'acceptedAt':time.time()}
        r.atomic(path/'state-receipt.json',saved)
    try:
        command(data,['systemd-run','--quiet','--no-block','--unit='+unit(data),'--property=Type=exec','--property=UMask=0077',
            '--property=RuntimeMaxSec=240','--property=StandardOutput=null','--property=StandardError=null',
            '/usr/bin/python3',str(path/'state-agent.py'),'run',data['id']],15)
    except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_STATE_DISPATCH_UNCONFIRMED'}
    return saved


def perform(id):
    identifier(id);path=BASE/id
    data=validate(json.loads(private(path/'state-input.json')))
    require(data['id']==id and digest(private(path/'state-agent.py'))==data['agentSha256'],'BOOTSTRAP_STATE_AGENT_CHANGED')
    r=context_runtime(data)
    with lock(data):
        require(json.loads(private(BASE/'reservation.json'))=={'id':id,'binding':fields(data)['binding']},'BOOTSTRAP_STATE_HOST_RESERVED')
        saved=saved_receipt(data);require(saved is not None and saved['state']=='ACCEPTED','BOOTSTRAP_STATE_ALREADY_DISPATCHED')
        try:
            role=context(data)[4]['role'];require(role in ['SOURCE','TARGET'],'BOOTSTRAP_STATE_ROLE_CHANGED')
            intent=(source_intent if role=='SOURCE' else target_intent)(data);r.atomic(path/'state-intent.json',intent)
            # Save original bytes for review/recovery; never restore them implicitly.
            before={name:base64.b64encode(config(Path(name))).decode() if config(Path(name)) is not None else None
                for name in intent['desired']}
            r.atomic(path/'state-before.json',before)
            saved.update(state='RUNNING',effectsStarted=True);r.atomic(path/'state-receipt.json',saved)
            (apply_source if role=='SOURCE' else apply_target)(data,intent)
            proof=(verify_source if role=='SOURCE' else verify_target)(data,intent)
            saved.update(proof,state='EXPORTED' if role=='SOURCE' else 'MOUNTED',finishedAt=time.time())
        except Exception as error:
            saved.update(state='OUTCOME_UNKNOWN' if saved['effectsStarted'] else 'FAILED',finishedAt=time.time(),
                code=error.code if isinstance(error,(Refusal,policy().Refusal)) else 'BOOTSTRAP_STATE_FAILED_PRESERVED')
        r.atomic(path/'state-receipt.json',saved);return saved


if __name__=='__main__':
    try:
        require(os.geteuid()==ROOT_UID and sys.platform=='linux','BOOTSTRAP_STATE_LINUX_ROOT_REQUIRED')
        if len(sys.argv)==3 and sys.argv[1]=='run':result=perform(sys.argv[2])
        else:
            require(len(sys.argv)==2 and sys.argv[1] in ['metadata','inspect','dispatch','status'],'BOOTSTRAP_STATE_INVALID_COMMAND')
            raw=sys.stdin.buffer.read(MAX_INPUT+1);require(len(raw)<=MAX_INPUT,'BOOTSTRAP_STATE_INPUT_TOO_LARGE');data=json.loads(raw)
            result={'metadata':metadata,'inspect':inspect,'dispatch':dispatch,'status':status}[sys.argv[1]](data)
        print(json.dumps(result))
    except Exception as error:
        print(json.dumps({'protocol':PROTOCOL,'code':error.code if isinstance(error,(Refusal,policy().Refusal)) else 'BOOTSTRAP_STATE_REFUSED'}));sys.exit(1)

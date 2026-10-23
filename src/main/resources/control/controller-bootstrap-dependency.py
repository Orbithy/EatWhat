"""Durable forwarding-only dependency phase. No management write route yet.

KEY -> SOURCE authorization -> TARGET connection are separately sealed operations
under the same phase identity. No original key/worker is implicitly replayed.
A reviewed read-only state observer checks the immutable previous NFS task.
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
import socket
import stat
import subprocess
import sys
import time
import types
import uuid

POLICY_SOURCE='__EATWHAT_CONTROLLER_DEPENDENCY_POLICY_BASE64__'
STATE_SOURCE='__EATWHAT_CONTROLLER_STATE_OBSERVER_BASE64__'
_POLICY=_STATE=None
_CTX={}
ROOT_UID=0
ROOT_GID=0
BASE=Path('/var/lib/eatwhat-controller-bootstrap-dependency')
ACCOUNTS=Path('/var/lib/eatwhat-controller-dependency-accounts')
CONTROL=Path('/opt/eatwhat-control')
SYSTEM=Path('/etc/systemd/system')
PROTOCOL='eatwhat-controller-bootstrap-dependency-v1'
MAX_INPUT=1048576
OPS={'KEY':'KEY_READY','SOURCE':'AUTHORIZED','CONNECT':'VERIFIED'}


class Refusal(Exception):
    def __init__(self,code):self.code=code;super().__init__(code)


def require(value,code='BOOTSTRAP_DEPENDENCY_PRECONDITION_FAILED'):
    if not value:raise Refusal(code)


def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def digest(value):return hashlib.sha256(value).hexdigest()


def policy():
    global _POLICY
    if _POLICY is None:
        _POLICY=types.ModuleType('dependency_policy');exec(compile(base64.b64decode(POLICY_SOURCE),'<dependency-policy>','exec'),_POLICY.__dict__)
    return _POLICY


def observer(data):
    global _STATE
    source=base64.b64decode(STATE_SOURCE)
    require(digest(source)==data['stateObserverSha256'],'BOOTSTRAP_DEPENDENCY_OBSERVER_CHANGED')
    if _STATE is None:
        _STATE=types.ModuleType('reviewed_state_observer');exec(compile(source,'<reviewed-state-observer>','exec'),_STATE.__dict__)
    return _STATE


def validate(data,mode='header'):
    require(isinstance(data,dict),'BOOTSTRAP_DEPENDENCY_INPUT_INVALID')
    keys={'id','role','stateId','stateBinding','stateObserverSha256','approvalDigest','sourceHostPublicKey','dependencySshPort','forwards'}
    if data.get('role')=='SOURCE':keys.add('targetPublicKey')
    if mode!='inspect':keys|={'expectedProof','agentSha256'}
    if mode=='dispatch':keys.add('agent')
    require(isinstance(data,dict) and set(data)==keys,'BOOTSTRAP_DEPENDENCY_INPUT_INVALID')
    p=policy();p.identifier(data['id']);p.identifier(data['stateId']);require(data['id']!=data['stateId'])
    require(data['role'] in ['SOURCE','TARGET'],'BOOTSTRAP_DEPENDENCY_ROLE_INVALID')
    for name in ['stateBinding','stateObserverSha256','approvalDigest','expectedProof','agentSha256']:
        if name in data:require(isinstance(data[name],str) and re.fullmatch('[a-f0-9]{64}',data[name]),'BOOTSTRAP_DEPENDENCY_BINDING_INVALID')
    p.public(data['sourceHostPublicKey']);p.port(data['dependencySshPort']);p.forwards(data['forwards'])
    require(data['dependencySshPort'] not in {row[k] for row in data['forwards'] for k in ['remotePort','localPort']},'BOOTSTRAP_DEPENDENCY_PORT_COLLISION')
    if data['role']=='SOURCE':p.public(data['targetPublicKey']);require(data['targetPublicKey']!=data['sourceHostPublicKey'],'BOOTSTRAP_DEPENDENCY_EXISTING_KEY_REUSE')
    if mode=='dispatch':require(isinstance(data['agent'],str) and len(data['agent'].encode())<=MAX_INPUT
        and digest(data['agent'].encode())==data['agentSha256'],'BOOTSTRAP_DEPENDENCY_AGENT_CHANGED')
    return data


def context(data,refresh=False):
    key=(data['stateId'],data['stateBinding'],data['stateObserverSha256'],data['approvalDigest'],data['role'])
    if refresh:_CTX.pop(key,None)
    if key not in _CTX:
        s=observer(data);header=json.loads(s.private(s.BASE/data['stateId']/'state-input.json'))
        require(header['id']==data['stateId'] and digest(canonical(header))==data['stateBinding']
            and header['approvalDigest']==data['approvalDigest'],'BOOTSTRAP_DEPENDENCY_STATE_BINDING_CHANGED')
        ready=s.status(header);request=s.context(header)[4]
        require(request['role']==data['role'] and ready['state']==('EXPORTED' if data['role']=='SOURCE' else 'MOUNTED'),
            'BOOTSTRAP_DEPENDENCY_STATE_UNVERIFIED')
        _CTX[key]=(s,s.context_runtime(header),header,request)
    result=_CTX[key];require(result[3]['role']==data['role'],'BOOTSTRAP_DEPENDENCY_ROLE_INVALID');return result


def run(data,args,timeout=15,raw=None):return context(data)[1].run(args,stdin=raw,timeout=timeout)
def task(data):return BASE/data['id']
def header(data):return {k:v for k,v in data.items() if k!='agent'}
def inspect_input(data):return {k:v for k,v in data.items() if k not in ['agent','agentSha256','expectedProof']}
def fields(data):return {'protocol':PROTOCOL,'id':data['id'],'role':data['role'],'binding':digest(canonical(header(data)))}
def key_path(data):return CONTROL/'private'/(data['id']+'.key')
def host_path(data):return CONTROL/'private'/(data['id']+'.known-hosts')


def owned_file(path,uid,gid,mode,maximum=8192):
    require(all(not p.is_symlink() for p in [path,*path.parents]) and path.is_file(),'BOOTSTRAP_DEPENDENCY_UNSAFE_FILE')
    info=path.stat();require(info.st_uid==uid and info.st_gid==gid and stat.S_IMODE(info.st_mode)==mode
        and info.st_size<=maximum,'BOOTSTRAP_DEPENDENCY_UNSAFE_FILE');return path.read_bytes()


def directory(path,uid,mode,gid=None):
    require(all(not p.is_symlink() for p in [path,*path.parents]) and path.is_dir(),'BOOTSTRAP_DEPENDENCY_UNSAFE_DIRECTORY')
    a=path.stat();require(a.st_uid==uid and stat.S_IMODE(a.st_mode)==mode and (gid is None or a.st_gid==gid),'BOOTSTRAP_DEPENDENCY_UNSAFE_DIRECTORY')


def create_directory(path,uid,gid,mode):
    # systemd workers use UMask=0077. Establish the approved final ownership
    # and permissions explicitly on the newly created inode, never chmod an
    # existing path or temporarily relax the worker's process-wide umask.
    path.mkdir(mode=0o700)
    fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        os.fchown(fd,uid,gid);os.fchmod(fd,mode)
    finally:os.close(fd)
    directory(path,uid,mode,gid)


def private(data,path):return context(data)[0].private(path)
def config(data,path):return context(data)[0].config(path)


def default_ssh(data):
    paths=[Path('/etc/ssh/sshd_config')]
    paths+=sorted(Path('/etc/ssh/sshd_config.d').glob('*.conf'))
    for name in ['ssh.service','ssh.socket','sshd.service']:
        path=SYSTEM/name
        if path.exists() and not path.is_symlink():paths.append(path)
        folder=SYSTEM/(name+'.d')
        if folder.exists():
            require(folder.is_dir() and not folder.is_symlink(),'BOOTSTRAP_DEPENDENCY_UNSAFE_DEFAULT_SSH')
            paths+=sorted(folder.glob('*.conf'))
    return {str(p):digest(config(data,p) or b'') for p in paths}


def user_database(name):
    result={}
    for path in [Path('/etc/passwd'),Path('/etc/group'),Path('/etc/shadow'),Path('/etc/gshadow')]:
        require(path.is_file() and not path.is_symlink() and path.stat().st_uid==ROOT_UID,'BOOTSTRAP_DEPENDENCY_USER_DATABASE_UNSAFE')
        raw=path.read_bytes();require(len(raw)<=1048576,'BOOTSTRAP_DEPENDENCY_USER_DATABASE_UNSAFE')
        result[str(path)]=digest(b''.join(row+b'\n' for row in raw.splitlines() if row.split(b':',1)[0]!=name.encode()))
    return result


def own_account_rows(name):
    result={}
    for file in ['passwd','group','shadow','gshadow']:
        path=Path('/etc')/file
        rows=[row for row in path.read_bytes().splitlines() if row.split(b':',1)[0]==name.encode()]
        require(len(rows)==1,'BOOTSTRAP_DEPENDENCY_ACCOUNT_CHANGED');result[file]=digest(rows[0])
    return result


def admin_masters():
    rows={}
    for path in Path('/proc').glob('[0-9]*'):
        try:
            if (path/'comm').read_text().strip()!='sshd':continue
            info=(path/'stat').read_text().rpartition(')')[2].split()
            if int(info[1])==1:rows[path.name]=info[19]
        except (OSError,ValueError,IndexError):continue
    return rows


def listeners(data):
    values=[]
    for line in run(data,['ss','-H','-lntp']).decode().splitlines():
        cols=line.split();require(len(cols)>=5,'BOOTSTRAP_DEPENDENCY_LISTENER_UNOBSERVABLE')
        values.append({'address':policy().listener_address(cols[3]),'process':' '.join(cols[5:])})
    return values


def ss_config_check(data,raw,user,peer):
    # sshd closes inherited extra descriptors. fd0 is kept, so a bounded pipe
    # previews the exact private config without a temporary filesystem write.
    try:
        path='/dev/stdin'
        for args in [['/usr/sbin/sshd','-t','-f',path],['/usr/sbin/sshd','-T','-f',path,'-C','user='+user+',host=localhost,addr='+peer]]:
            r=subprocess.run(args,input=raw,capture_output=True,timeout=8,env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LANG':'C.UTF-8'})
            require(r.returncode==0,'BOOTSTRAP_DEPENDENCY_SSHD_CONFIG_INVALID')
        require(len(r.stdout)<=65536,'BOOTSTRAP_DEPENDENCY_SSHD_CONFIG_INVALID')
        effective={}
        for line in r.stdout.decode().splitlines():
            key,_,value=line.partition(' ');effective[key]=value
        expected={'allowtcpforwarding':'local','allowstreamlocalforwarding':'no','permitlisten':'none','maxsessions':'0',
            'passwordauthentication':'no','kbdinteractiveauthentication':'no','permittty':'no','allowagentforwarding':'no',
            'x11forwarding':'no','permittunnel':'no','forcecommand':'/bin/false','authenticationmethods':'publickey','authorizedkeysfile':'none',
            'allowusers':user+'@'+peer,'allowgroups':user,'authorizedkeyscommanduser':'root',
            'authorizedkeyscommand':'/usr/bin/cat '+str(task(data)/'authorized_keys')}
        require(all(effective.get(k)==v for k,v in expected.items()),'BOOTSTRAP_DEPENDENCY_SSH_POLICY_UNVERIFIED')
        require(set(effective.get('permitopen','').split())=={'127.0.0.1:'+str(row['remotePort']) for row in data['forwards']},
            'BOOTSTRAP_DEPENDENCY_SSH_POLICY_UNVERIFIED')
        return digest(canonical({k:effective[k] for k in expected}))
    except subprocess.TimeoutExpired:raise Refusal('BOOTSTRAP_DEPENDENCY_SSHD_CONFIG_INVALID')


def inspect(data):
    validate(data,'inspect');s,r,prior,request=context(data,True);p=policy();ports=p.forwards(data['forwards'])
    unit=SYSTEM/p.unit_name(data['id'],'source' if data['role']=='SOURCE' else 'target')
    require(config(data,unit) is None and run(data,['systemctl','show','--property=LoadState','--value',unit.name]).strip()==b'not-found',
        'BOOTSTRAP_DEPENDENCY_UNIT_RETAINED')
    current=listeners(data)
    if data['role']=='SOURCE':
        user=p.account(request['targetServerId']);home=ACCOUNTS/request['targetServerId']
        try:pwd.getpwnam(user)
        except KeyError:pass
        else:raise Refusal('BOOTSTRAP_DEPENDENCY_ACCOUNT_RETAINED')
        try:grp.getgrnam(user)
        except KeyError:pass
        else:raise Refusal('BOOTSTRAP_DEPENDENCY_ACCOUNT_RETAINED')
        require(not home.exists() and not home.is_symlink(),'BOOTSTRAP_DEPENDENCY_ACCOUNT_RETAINED')
        if ACCOUNTS.exists():directory(ACCOUNTS,ROOT_UID,0o755)
        host=Path('/etc/ssh/ssh_host_ed25519_key');owned_file(host,ROOT_UID,0,0o600)
        pub=p.public(' '.join(run(data,['ssh-keygen','-y','-f',str(host)],8).decode().split()[:2]))
        require(pub==data['sourceHostPublicKey'],'BOOTSTRAP_DEPENDENCY_HOST_KEY_CHANGED')
        require(all(row['address'].rsplit(':',1)[-1]!=str(data['dependencySshPort']) for row in current),'BOOTSTRAP_DEPENDENCY_PORT_IN_USE')
        for row in ports:
            allowed={'127.0.0.1:'+str(row['remotePort'])}
            if row['purpose']=='SOURCE_ADMIN_SSH':allowed.add('0.0.0.0:'+str(row['remotePort']))
            require(any(v['address'] in allowed for v in current),'BOOTSTRAP_DEPENDENCY_SOURCE_LISTENER_MISSING')
        raw=p.server_config(data['id'],request['targetServerId'],request['sourceAddress'],request['targetAddress'],data['dependencySshPort'],ports,home,task(data))
        facts={'sourceSshFiles':default_ssh(data),'existingAdminMasters':admin_masters(),'userDatabase':user_database(user),
            'accountParentMissing':not ACCOUNTS.exists(),
            'accountName':user,'accountHome':str(home),'sourceHostPublicKey':pub,'sourceSshPolicySha256':ss_config_check(data,raw,user,request['targetAddress'])}
    else:
        user=pwd.getpwnam('eatwhat-control')
        require(user.pw_uid==prior['ownerUid'] and user.pw_gid==prior['ownerGid'],'BOOTSTRAP_DEPENDENCY_CONTROLLER_ACCOUNT_CHANGED')
        directory(CONTROL,user.pw_uid,0o700,user.pw_gid)
        require(not (CONTROL/'private').exists() and not (CONTROL/'private').is_symlink(),'BOOTSTRAP_DEPENDENCY_TARGET_PRIVATE_RETAINED')
        for row in ports:require(all(v['address'].rsplit(':',1)[-1]!=str(row['localPort']) for v in current),'BOOTSTRAP_DEPENDENCY_PORT_IN_USE')
        facts={'uid':user.pw_uid,'gid':user.pw_gid,'privateDirectoryMissing':True,'portsAvailable':True}
    return {'protocol':PROTOCOL,'id':data['id'],'role':data['role'],'effectsStarted':False,'privateFacts':facts,
        'proof':digest(canonical({'input':data,'facts':facts}))}


def lock(data,create=False):
    r=context(data)[1]
    if create and not BASE.exists():BASE.mkdir(mode=0o700)
    r.directory(BASE);path=BASE/'host.lock'
    if create and not path.exists():
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600);os.close(fd)
    private(data,path);stream=path.open('r+')
    try:fcntl.lockf(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except Exception:stream.close();raise Refusal('BOOTSTRAP_DEPENDENCY_HOST_BUSY')
    return stream


def operation(op,data):
    require(op in OPS and (data['role']=='SOURCE' and op=='SOURCE' or data['role']=='TARGET' and op in ['KEY','CONNECT']),
        'BOOTSTRAP_DEPENDENCY_OPERATION_INVALID')

def read_receipt(data,op):
    path=task(data)/(op.lower()+'-receipt.json')
    if not path.exists() and not path.is_symlink():return None
    value=json.loads(private(data,path));require(all(value.get(k)==v for k,v in fields(data).items()) and value.get('operation')==op,
        'BOOTSTRAP_DEPENDENCY_BINDING_CHANGED');return value


def worker_unit(data,op):return 'eatwhat-dependency-phase-'+data['id']+'-'+op.lower()


def key_proof(data):
    s,r,prior,request=context(data);proof=json.loads(private(data,task(data)/'key-proof.json'))
    directory(CONTROL/'private',prior['ownerUid'],0o700,prior['ownerGid'])
    raw=owned_file(key_path(data),prior['ownerUid'],prior['ownerGid'],0o600)
    require(digest(raw)==proof['privateKeySha256'],'BOOTSTRAP_DEPENDENCY_CLIENT_KEY_CHANGED')
    actual=policy().public(' '.join(run(data,['ssh-keygen','-y','-f',str(key_path(data))],8).decode().split()[:2]))
    require(actual==proof['publicKey'],'BOOTSTRAP_DEPENDENCY_CLIENT_KEY_CHANGED')
    return proof


def source_proof(data):
    s,r,prior,request=context(data);p=policy();intent=json.loads(private(data,task(data)/'source-intent.json'))
    proof=json.loads(private(data,task(data)/'account-proof.json'));name=p.account(request['targetServerId']);user=pwd.getpwnam(name)
    require(user.pw_uid==proof['uid'] and user.pw_gid==proof['gid'] and user.pw_dir==str(ACCOUNTS/request['targetServerId'])
        and user.pw_shell=='/usr/sbin/nologin','BOOTSTRAP_DEPENDENCY_ACCOUNT_CHANGED')
    require(own_account_rows(name)==proof['accountRows'],'BOOTSTRAP_DEPENDENCY_ACCOUNT_CHANGED')
    require(policy().public(' '.join(run(data,['ssh-keygen','-y','-f','/etc/ssh/ssh_host_ed25519_key'],8).decode().split()[:2]))==data['sourceHostPublicKey'],
        'BOOTSTRAP_DEPENDENCY_HOST_KEY_CHANGED')
    directory(Path(user.pw_dir),ROOT_UID,0o750,user.pw_gid)
    require(not list(Path(user.pw_dir).iterdir()),'BOOTSTRAP_DEPENDENCY_ACCOUNT_HOME_CHANGED')
    require(user_database(name)==intent['before']['userDatabase'],'BOOTSTRAP_DEPENDENCY_EXISTING_ACCOUNTS_CHANGED')
    require(default_ssh(data)==intent['before']['sourceSshFiles'],'BOOTSTRAP_DEPENDENCY_DEFAULT_SSH_CHANGED')
    require(private(data,task(data)/'authorized_keys')==p.authorized_key(data['targetPublicKey'],request['targetAddress'],data['forwards']),
        'BOOTSTRAP_DEPENDENCY_KEY_OPTIONS_CHANGED')
    require(private(data,task(data)/'server.conf')==base64.b64decode(intent['serverConfig']),'BOOTSTRAP_DEPENDENCY_SERVER_CHANGED')
    unit=SYSTEM/p.unit_name(data['id'],'source')
    require(config(data,unit)==base64.b64decode(intent['unit']),'BOOTSTRAP_DEPENDENCY_SERVER_CHANGED')
    verify_service(data,unit.name,ROOT_UID,['/usr/sbin/sshd','-D','-e','-f',str(task(data)/'server.conf')])
    require(any(v['address']==request['sourceAddress']+':'+str(data['dependencySshPort']) for v in listeners(data)),
        'BOOTSTRAP_DEPENDENCY_PRIVATE_LISTENER_MISSING')
    return {'accountName':name,'defaultSshConfigurationUnchanged':True,'shellSessionsAllowed':False,'remoteListenersAllowed':False,
        'sourcePrivatePort':data['dependencySshPort'],'dependencyApplicationHealthProven':False}


def verify_service(data,name,uid,args):
    require(run(data,['systemctl','show',name,'--property=ActiveState','--value']).strip()==b'active','BOOTSTRAP_DEPENDENCY_SERVICE_NOT_ACTIVE')
    require(run(data,['systemctl','is-enabled',name]).strip()==b'enabled','BOOTSTRAP_DEPENDENCY_SERVICE_NOT_PERSISTENT')
    require(run(data,['systemctl','show',name,'--property=FragmentPath','--value']).decode().strip()==str(SYSTEM/name)
        and run(data,['systemctl','show',name,'--property=DropInPaths','--value']).strip()==b'','BOOTSTRAP_DEPENDENCY_SERVICE_OVERRIDDEN')
    pid=int(run(data,['systemctl','show',name,'--property=MainPID','--value']).strip());require(pid>1,'BOOTSTRAP_DEPENDENCY_SERVICE_NOT_ACTIVE')
    path=Path('/proc')/str(pid)
    require(path.stat().st_uid==uid and (path/'exe').resolve()==Path(args[0]).resolve(),'BOOTSTRAP_DEPENDENCY_PROCESS_CHANGED')
    if args[0]=='/usr/sbin/sshd':
        # sshd rewrites its own argv for listener status. Validate the loaded
        # unit's original argv plus actual executable/UID, not volatile title.
        declared=run(data,['systemctl','show',name,'--property=ExecStart','--value']).decode()
        require('argv[]='+' '.join(args)+' ;' in declared,'BOOTSTRAP_DEPENDENCY_PROCESS_CHANGED')
    else:require([v.decode() for v in (path/'cmdline').read_bytes().split(b'\0') if v]==args,'BOOTSTRAP_DEPENDENCY_PROCESS_CHANGED')
    return pid


def connection_proof(data):
    s,r,prior,request=context(data);p=policy();key_proof(data)
    require(owned_file(host_path(data),prior['ownerUid'],prior['ownerGid'],0o600)==('dependency-'+data['id']+' '+data['sourceHostPublicKey']+'\n').encode(),
        'BOOTSTRAP_DEPENDENCY_HOST_KEY_CHANGED')
    unit=SYSTEM/p.unit_name(data['id'],'target')
    require(config(data,unit)==p.client_unit(data['id'],request['targetServerId'],request['sourceAddress'],data['dependencySshPort'],data['forwards'],key_path(data),host_path(data)),
        'BOOTSTRAP_DEPENDENCY_CLIENT_UNIT_CHANGED')
    args=p.client_args(data['id'],request['targetServerId'],request['sourceAddress'],data['dependencySshPort'],data['forwards'],key_path(data),host_path(data))
    pid=verify_service(data,unit.name,prior['ownerUid'],args)
    for row in data['forwards']:
        matching=[v for v in listeners(data) if v['address'].rsplit(':',1)[-1]==str(row['localPort'])]
        require(len(matching)==1 and matching[0]['address']=='127.0.0.1:'+str(row['localPort'])
            and 'pid='+str(pid)+',' in matching[0]['process'],'BOOTSTRAP_DEPENDENCY_FORWARD_LISTENER_UNVERIFIED')
    run(data,['runuser','-u','eatwhat-control','--','ssh','-F','/dev/null','-S','/run/'+p.runtime_name(data['id'])+'/control','-O','check','--',
        p.account(request['targetServerId'])+'@'+request['sourceAddress']],8)
    return {'authenticatedPrivateSshMaster':True,'newDedicatedClientKey':True,'localListenersLoopbackOnly':True,
        'rootLoginCredentialReused':False,'dependencyApplicationHealthProven':False}


def proof(data,op):
    if op=='KEY':return {'publicKey':key_proof(data)['publicKey'],'privateKeyExported':False,'keySource':'NEW_TARGET_DEDICATED_KEY'}
    return source_proof(data) if op=='SOURCE' else connection_proof(data)


def status(data,op):
    validate(data);operation(op,data);context(data,True)
    if not BASE.exists() and not BASE.is_symlink():return {**fields(data),'operation':op,'state':'NOT_STARTED','effectsStarted':False}
    context(data)[1].directory(BASE);reserve=BASE/'reservation.json'
    if reserve.exists() or reserve.is_symlink():require(json.loads(private(data,reserve))=={'id':data['id'],'binding':fields(data)['binding']},'BOOTSTRAP_DEPENDENCY_HOST_RESERVED')
    else:require(all(p.name=='host.lock' for p in BASE.iterdir()),'BOOTSTRAP_DEPENDENCY_RESERVATION_MISSING')
    saved=read_receipt(data,op)
    if saved is None:return {**fields(data),'operation':op,'state':'NOT_STARTED','effectsStarted':False}
    if saved['state']==OPS[op]:
        try:return {**saved,**proof(data,op)}
        except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_DEPENDENCY_CONFIGURATION_UNVERIFIED'}
    if saved['state'] in ['ACCEPTED','RUNNING','OUTCOME_UNKNOWN']:
        active=run(data,['systemctl','show',worker_unit(data,op),'--property=ActiveState','--value']).strip()
        if active in [b'inactive',b'failed']:
            try:
                with lock(data):
                    require(saved.get('effectsStarted') is True,'BOOTSTRAP_DEPENDENCY_ACCEPTANCE_UNCONFIRMED')
                    current=proof(data,op);result={**saved,**current,'state':OPS[op],'derivedFromConfiguration':True}
                    if 'code' in result:result['originalReceiptCode']=result.pop('code')
                    return result
            except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_DEPENDENCY_RECONCILIATION_REQUIRED'}
        require(active in [b'active',b'activating',b'deactivating',b'reloading'],'BOOTSTRAP_DEPENDENCY_WORKER_UNOBSERVABLE')
    return saved


def dispatch(data,op):
    validate(data,'dispatch');operation(op,data);h=header(data);previous=status(h,op)
    if previous['state']!='NOT_STARTED':return previous
    if op=='CONNECT':require(status(h,'KEY')['state']=='KEY_READY','BOOTSTRAP_DEPENDENCY_KEY_REQUIRED');key_proof(h)
    else:require(inspect(inspect_input(h))['proof']==data['expectedProof'],'BOOTSTRAP_DEPENDENCY_APPROVAL_EXPIRED')
    r=context(data)[1]
    with lock(data,True):
        previous=status(h,op)
        if previous['state']!='NOT_STARTED':return previous
        if not (BASE/'reservation.json').exists():r.atomic(BASE/'reservation.json',{'id':data['id'],'binding':fields(h)['binding']})
        path=task(data)
        if not path.exists():path.mkdir(mode=0o700);r.atomic(path/'input.json',h);r.atomic(path/'agent.py',data['agent'].encode())
        else:require(json.loads(private(data,path/'input.json'))==h and digest(private(data,path/'agent.py'))==data['agentSha256'],'BOOTSTRAP_DEPENDENCY_AGENT_CHANGED')
        saved={**fields(h),'operation':op,'state':'ACCEPTED','effectsStarted':False,'acceptedAt':time.time()};r.atomic(path/(op.lower()+'-receipt.json'),saved)
    try:run(data,['systemd-run','--quiet','--no-block','--unit='+worker_unit(data,op),'--property=Type=exec','--property=UMask=0077','--property=RuntimeMaxSec=180',
        '--property=StandardOutput=null','--property=StandardError=null','python3',str(path/'agent.py'),'run',data['id'],op],15)
    except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_DEPENDENCY_DISPATCH_UNCONFIRMED'}
    return saved


def make_key(data):
    s,r,prior,request=context(data);folder=CONTROL/'private';require(not folder.exists() and not folder.is_symlink(),'BOOTSTRAP_DEPENDENCY_TARGET_PRIVATE_RETAINED')
    create_directory(folder,prior['ownerUid'],prior['ownerGid'],0o700)
    run(data,['ssh-keygen','-q','-t','ed25519','-N','','-C','dependency-'+data['id'],'-f',str(key_path(data))],10)
    for path in [key_path(data),Path(str(key_path(data))+'.pub')]:os.chown(path,prior['ownerUid'],prior['ownerGid'])
    public=policy().public(' '.join(run(data,['ssh-keygen','-y','-f',str(key_path(data))],8).decode().split()[:2]))
    require(public!=data['sourceHostPublicKey'],'BOOTSTRAP_DEPENDENCY_EXISTING_KEY_REUSE')
    r.atomic(task(data)/'key-proof.json',{'publicKey':public,'privateKeySha256':digest(owned_file(key_path(data),prior['ownerUid'],prior['ownerGid'],0o600))})


def authorize(data,before):
    s,r,prior,request=context(data);p=policy();name=p.account(request['targetServerId']);home=ACCOUNTS/request['targetServerId']
    conf=p.server_config(data['id'],request['targetServerId'],request['sourceAddress'],request['targetAddress'],data['dependencySshPort'],data['forwards'],home,task(data))
    unit=p.server_unit(data['id'],request['targetAddress'],task(data))
    r.atomic(task(data)/'source-intent.json',{'before':before,'serverConfig':base64.b64encode(conf).decode(),'unit':base64.b64encode(unit).decode()})
    run(data,['useradd','--system','--user-group','--no-create-home','--home-dir',str(home),'--shell','/usr/sbin/nologin',name])
    user=pwd.getpwnam(name);require(100<=user.pw_uid<60000 and user.pw_gid>0,'BOOTSTRAP_DEPENDENCY_ACCOUNT_CHANGED')
    r.atomic(task(data)/'account-proof.json',{'uid':user.pw_uid,'gid':user.pw_gid,'accountRows':own_account_rows(name)})
    if not ACCOUNTS.exists():create_directory(ACCOUNTS,ROOT_UID,ROOT_GID,0o755)
    directory(ACCOUNTS,ROOT_UID,0o755);create_directory(home,ROOT_UID,user.pw_gid,0o750)
    r.atomic(task(data)/'authorized_keys',p.authorized_key(data['targetPublicKey'],request['targetAddress'],data['forwards']))
    r.atomic(task(data)/'server.conf',conf)
    s.write_config(SYSTEM/p.unit_name(data['id'],'source'),unit,0o644)
    run(data,['/usr/sbin/sshd','-t','-f',str(task(data)/'server.conf')],8)
    run(data,['systemctl','daemon-reload']);run(data,['systemctl','enable','--now',p.unit_name(data['id'],'source')],25)
    require(all(admin_masters().get(pid)==start for pid,start in before['existingAdminMasters'].items()),'BOOTSTRAP_DEPENDENCY_EXISTING_ADMIN_SSH_RESTARTED')


def connect(data):
    s,r,prior,request=context(data);key_proof(data);path=host_path(data)
    require(not path.exists() and not path.is_symlink(),'BOOTSTRAP_DEPENDENCY_HOST_PIN_RETAINED')
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'wb') as f:
        os.fchown(f.fileno(),prior['ownerUid'],prior['ownerGid']);f.write(('dependency-'+data['id']+' '+data['sourceHostPublicKey']+'\n').encode());f.flush();os.fsync(f.fileno())
    for row in data['forwards']:require(all(v['address'].rsplit(':',1)[-1]!=str(row['localPort']) for v in listeners(data)),'BOOTSTRAP_DEPENDENCY_PORT_IN_USE')
    unit=policy().unit_name(data['id'],'target');require(config(data,SYSTEM/unit) is None,'BOOTSTRAP_DEPENDENCY_UNIT_RETAINED')
    s.write_config(SYSTEM/unit,policy().client_unit(data['id'],request['targetServerId'],request['sourceAddress'],data['dependencySshPort'],data['forwards'],key_path(data),host_path(data)),0o644)
    run(data,['systemctl','daemon-reload']);run(data,['systemctl','enable','--now',unit],25)
    deadline=time.monotonic()+30
    while True:
        try:connection_proof(data);return
        except Exception:
            require(time.monotonic()<deadline,'BOOTSTRAP_DEPENDENCY_CONNECTION_UNVERIFIED');time.sleep(1)


def perform(id,op):
    policy().identifier(id);path=BASE/id
    # Header/agent are root-private before any prior-state observer is loaded.
    require(path.is_dir() and not path.is_symlink() and path.stat().st_uid==ROOT_UID and stat.S_IMODE(path.stat().st_mode)==0o700)
    def saved(name):return owned_file(path/name,ROOT_UID,ROOT_GID,0o600,MAX_INPUT)
    data=validate(json.loads(saved('input.json')));operation(op,data)
    require(data['id']==id and digest(saved('agent.py'))==data['agentSha256'],'BOOTSTRAP_DEPENDENCY_AGENT_CHANGED')
    s,r,prior,request=context(data,True)
    with lock(data):
        require(json.loads(private(data,BASE/'reservation.json'))=={'id':id,'binding':fields(data)['binding']},'BOOTSTRAP_DEPENDENCY_HOST_RESERVED')
        result=read_receipt(data,op);require(result is not None and result['state']=='ACCEPTED','BOOTSTRAP_DEPENDENCY_ALREADY_DISPATCHED')
        try:
            before=None
            if op!='CONNECT':
                observed=inspect(inspect_input(data));require(observed['proof']==data['expectedProof'],'BOOTSTRAP_DEPENDENCY_APPROVAL_EXPIRED');before=observed['privateFacts']
            else:require(status(data,'KEY')['state']=='KEY_READY','BOOTSTRAP_DEPENDENCY_KEY_REQUIRED')
            r.atomic(path/(op.lower()+'-before.json'),before or {})
            result.update(state='RUNNING',effectsStarted=True);r.atomic(path/(op.lower()+'-receipt.json'),result)
            if op=='KEY':make_key(data)
            elif op=='SOURCE':authorize(data,before)
            else:connect(data)
            result.update(proof(data,op),state=OPS[op],finishedAt=time.time())
        except Exception as error:
            result.update(state='OUTCOME_UNKNOWN' if result['effectsStarted'] else 'FAILED',finishedAt=time.time(),
                code=error.code if isinstance(error,(Refusal,policy().Refusal)) else 'BOOTSTRAP_DEPENDENCY_FAILED_PRESERVED')
        r.atomic(path/(op.lower()+'-receipt.json'),result);return result


if __name__=='__main__':
    try:
        require(os.geteuid()==ROOT_UID and sys.platform=='linux','BOOTSTRAP_DEPENDENCY_ROOT_LINUX_REQUIRED')
        if len(sys.argv)==4 and sys.argv[1]=='run':reply=perform(sys.argv[2],sys.argv[3])
        else:
            require(len(sys.argv)==3 and sys.argv[1] in ['inspect','dispatch','status'],'BOOTSTRAP_DEPENDENCY_COMMAND_INVALID')
            raw=sys.stdin.buffer.read(MAX_INPUT+1);require(len(raw)<=MAX_INPUT,'BOOTSTRAP_DEPENDENCY_INPUT_TOO_LARGE');data=json.loads(raw)
            if sys.argv[1]=='inspect':reply=inspect(data)
            else:reply=dispatch(data,sys.argv[2]) if sys.argv[1]=='dispatch' else status(data,sys.argv[2])
        print(json.dumps(reply))
    except Exception as error:
        print(json.dumps({'protocol':PROTOCOL,'code':getattr(error,'code','BOOTSTRAP_DEPENDENCY_REFUSED')}));sys.exit(1)

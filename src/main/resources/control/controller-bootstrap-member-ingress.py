"""Once-only TARGET private member HTTPS provisioning, before configuration/JOIN.

Loads the exact original business worker and its saved baseline. Never calls
the pre-Docker controller live context, reruns an installer, or exports a key.
"""
import http.client, ipaddress, base64, fcntl, hashlib, json, os, re, secrets, socket, ssl, stat, subprocess, sys, tempfile, time, types, uuid
from pathlib import Path
from urllib.parse import urlsplit, parse_qsl

PROTOCOL='eatwhat-controller-bootstrap-member-ingress-v1'
PERMISSION='CREATE_PRIVATE_MEMBER_HTTPS'
BASE=Path('/var/lib/eatwhat-controller-bootstrap-member-ingress')
RELAYS=Path('/var/lib/eatwhat-controller-bootstrap-member-relay')
BUSINESS=Path('/var/lib/eatwhat-controller-bootstrap-business')
SITES=Path('/opt/eatwhat-sites')
CONTROL=Path('/opt/eatwhat-control')
SYSTEM=Path('/etc/systemd/system')
ROOT_UID=ROOT_GID=0
MAX_INPUT=65536
_RELAY=None
class Refusal(Exception):
    def __init__(self,code='BOOTSTRAP_MEMBER_INGRESS_INPUT_INVALID'):self.code=code;super().__init__(code)
def require(value,code='BOOTSTRAP_MEMBER_INGRESS_INPUT_INVALID'):
    if not value:raise Refusal(code)
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def digest(raw):return hashlib.sha256(raw).hexdigest()
def identifier(value):
    try:require(str(uuid.UUID(value))==value and uuid.UUID(value).int!=0)
    except (ValueError,TypeError,AttributeError):raise Refusal()
    return value
def phase(value,name):return str(uuid.UUID(bytes=hashlib.md5((identifier(value)+':'+name).encode()).digest(),version=3))
def sha(value):require(isinstance(value,str) and re.fullmatch('[a-f0-9]{64}',value));return value
def directory(path):
    require(path.is_dir() and all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_MEMBER_INGRESS_UNSAFE_DIRECTORY')
    st=path.stat();require(st.st_uid==ROOT_UID and st.st_gid==ROOT_GID and stat.S_IMODE(st.st_mode)==0o700,'BOOTSTRAP_MEMBER_INGRESS_UNSAFE_DIRECTORY')
def private(path,maximum=MAX_INPUT):
    require(path.is_file() and all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_MEMBER_INGRESS_UNSAFE_FILE')
    st=path.stat();require(st.st_uid==ROOT_UID and st.st_gid==ROOT_GID and stat.S_IMODE(st.st_mode)==0o600 and 0<st.st_size<=maximum,'BOOTSTRAP_MEMBER_INGRESS_UNSAFE_FILE')
    raw=path.read_bytes();require(len(raw)<=maximum,'BOOTSTRAP_MEMBER_INGRESS_UNSAFE_FILE');return raw
def atomic(path,value):
    require(not path.is_symlink(),'BOOTSTRAP_MEMBER_INGRESS_UNSAFE_FILE');raw=value if isinstance(value,bytes) else canonical(value)
    fd,name=tempfile.mkstemp(prefix='.relay-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as out:os.fchmod(out.fileno(),0o600);out.write(raw);out.flush();os.fsync(out.fileno())
        os.replace(name,path);fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:os.fsync(fd)
        finally:os.close(fd)
    finally:Path(name).unlink(missing_ok=True)
def run(argv,timeout=15):
    p=subprocess.run(argv,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=timeout,check=True,
        cwd='/',env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','HOME':'/root'})
    require(len(p.stdout)<=65536,'BOOTSTRAP_MEMBER_INGRESS_COMMAND_UNCONFIRMED');return p.stdout
def header(data):return {k:v for k,v in data.items() if k!='agent'}
def fields(data,operation='START'):return {'protocol':PROTOCOL,'id':data['id'],'serverId':data['serverId'],'binding':digest(canonical(header(data))),'operation':operation}
def address(value):
    require(isinstance(value,str));ip=ipaddress.IPv4Address(value)
    require(str(ip)==value and any(ip in ipaddress.ip_network(n) for n in ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16']));return ip
def validate(data,mode='header'):
    keys={'id','bootstrapId','serverId','relayId','relayBinding','relayAgentSha256','sourceAddress','targetAddress','approvalDigest','permission'}
    if mode!='inspect':keys|={'expectedProof','agentSha256'}
    if mode=='prepare':keys.add('agent')
    require(isinstance(data,dict) and set(data)==keys)
    for key in ['id','bootstrapId','serverId','relayId']:identifier(data[key])
    require(data['id']==phase(data['bootstrapId'],'MEMBER_HTTPS_INGRESS') and data['relayId']==phase(data['bootstrapId'],'MEMBER_TLS_RELAY'))
    for key in ['relayBinding','relayAgentSha256','approvalDigest','expectedProof','agentSha256']:
        if key in data:sha(data[key])
    require(address(data['sourceAddress'])!=address(data['targetAddress']))
    require(data['permission']==PERMISSION,'BOOTSTRAP_MEMBER_INGRESS_SCOPE_REQUIRED')
    if mode=='prepare':require(isinstance(data['agent'],str) and digest(data['agent'].encode())==data['agentSha256'])
def original_relay(data,fresh=True):
    global _RELAY
    directory(RELAYS);parent=RELAYS/data['relayId'];directory(parent);prior=json.loads(private(parent/'input.json'));source=private(parent/'agent.py',524288)
    require(prior['id']==data['relayId'] and prior['bootstrapId']==data['bootstrapId'] and prior['serverId']==data['serverId']
        and prior['approvalDigest']==data['approvalDigest'] and digest(canonical(prior))==data['relayBinding']
        and digest(source)==data['relayAgentSha256']==prior['agentSha256'],'BOOTSTRAP_MEMBER_INGRESS_RELAY_CHANGED')
    if _RELAY is None:_RELAY=types.ModuleType('original_member_ingress_relay');exec(compile(source,'<original-relay>','exec'),_RELAY.__dict__)
    r=_RELAY;r.original(prior);state=r.guard(prior,fresh);r.service(prior,state)
    if fresh:r.probes(prior,state)
    return r,prior,state
def current(data,fresh=True):
    r,prior,relay=original_relay(data,fresh);b,business,live=r.original_business(prior,False);n=b.network_policy();baseline=b.baseline(business,live)['context']['snapshot']
    net=n.network();wg=[row for row in net['addresses'] if row['ifname']=='ewctrl0'];old=[row for row in baseline['network']['addresses'] if row['ifname']=='ewctrl0']
    require(len(wg)==1 and wg==old and net['links']['ewctrl0']==baseline['network']['links']['ewctrl0']
        and net['links']['ewctrl0']['kind']=='wireguard' and wg[0]['addresses']==[{'local':data['targetAddress'],'prefixlen':32,'scope':'global'}],
        'BOOTSTRAP_MEMBER_INGRESS_PRIVATE_INTERFACE_CHANGED')
    if fresh:
        require(n.wireguard_digest()==baseline['wireguardSha256'],'BOOTSTRAP_MEMBER_INGRESS_PRIVATE_PEER_CHANGED')
        peers=n.run([n.executable('wg'),'show','ewctrl0','allowed-ips']).decode().splitlines()
        require(len(peers)==1 and peers[0].split()[1:]==[data['sourceAddress']+'/32'],'BOOTSTRAP_MEMBER_INGRESS_PRIVATE_PEER_CHANGED')
    config={'serverId':data['serverId'],'bootstrapId':data['bootstrapId'],'businessId':business['id'],'networkId':relay['config']['networkId'],
        'listenAddress':data['targetAddress'],'sourceAddress':data['sourceAddress'],'httpPort':business['business']['httpPort'],'maxConnections':16}
    require(type(config['httpPort']) is int and 1024<=config['httpPort']<=65535 and config['httpPort'] not in [2222,2223,18080,18081,28490,25432,16379,25443,28443])
    import zipfile
    with zipfile.ZipFile(relay['jar']) as jar:
        require(jar.read('META-INF/eatwhat-member-ingress-relay-v1')==b'eatwhat-member-ingress-relay-v1\n'
            and 'BOOT-INF/classes/you/v50to/eatwhat/control/bootstrap/MemberIngressRelay.class' in jar.namelist(),'BOOTSTRAP_MEMBER_INGRESS_ARTIFACT_REQUIRED')
    return {'config':config,'jar':relay['jar'],'relayStateSha256':digest(canonical(relay)),
        'wireguardConfigurationSha256':digest(private(Path('/etc/wireguard/ewctrl0.conf'))),'wireguardInterfaceSha256':digest(canonical({'address':wg[0],'link':net['links']['ewctrl0']}))}
def folder(data):return SITES/data['serverId']/'private/member-ingress'
def unit(data):return 'eatwhat-member-ingress-'+data['serverId']+'.service'
def task(data):return BASE/data['id']
def absent(path):require(not path.exists() and not path.is_symlink(),'BOOTSTRAP_MEMBER_INGRESS_RETAINED')
def inspection_input(data):return {k:v for k,v in data.items() if k not in ['agent','expectedProof','agentSha256']}
def inspect(data):
    validate(data,'inspect');state=current(data);directory(SITES/data['serverId']/'private');absent(folder(data));absent(SYSTEM/unit(data));absent(SYSTEM/(unit(data)+'.d'))
    with socket.socket() as stream:stream.bind((data['targetAddress'],28490))
    return {**fields(data,'INSPECT'),'effectsStarted':False,'proof':digest(canonical({'header':data,'state':state}))}
def lock(create=False):
    if create and not BASE.exists():BASE.mkdir(mode=0o700)
    directory(BASE);path=BASE/'host.lock'
    if create and not path.exists():
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600);os.write(fd,b'lock\n');os.close(fd)
    private(path);stream=path.open('r+')
    try:fcntl.lockf(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except Exception:stream.close();raise Refusal('BOOTSTRAP_MEMBER_INGRESS_HOST_BUSY')
    return stream
def original(data):
    validate(data);directory(BASE);directory(task(data));require(json.loads(private(BASE/'reservation.json'))=={'id':data['id'],'binding':fields(data)['binding']},'BOOTSTRAP_MEMBER_INGRESS_HOST_RESERVED')
    require(json.loads(private(task(data)/'input.json'))==data and digest(private(task(data)/'agent.py',262144))==data['agentSha256'],'BOOTSTRAP_MEMBER_INGRESS_ORIGINAL_CHANGED')
def prepare_status(data):
    validate(data)
    if not BASE.exists() and not BASE.is_symlink():return {**fields(data,'PREPARE'),'state':'NOT_PREPARED'}
    directory(BASE)
    if not (BASE/'reservation.json').exists():return {**fields(data,'PREPARE'),'state':'OUTCOME_UNKNOWN'}
    original(data);return {**fields(data,'PREPARE'),'state':'PREPARED'}
def prepare(data):
    validate(data,'prepare');h=header(data)
    if BASE.exists():
        with lock(True):
            if (BASE/'reservation.json').exists():original(h);return prepare_status(h)
            require(all(p.name=='host.lock' for p in BASE.iterdir()),'BOOTSTRAP_MEMBER_INGRESS_RETAINED')
    require(inspect(inspection_input(h))['proof']==h['expectedProof'],'BOOTSTRAP_MEMBER_INGRESS_APPROVAL_EXPIRED')
    with lock(True):
        absent(BASE/'reservation.json');task(h).mkdir(mode=0o700);atomic(task(h)/'input.json',h);atomic(task(h)/'agent.py',data['agent'].encode());atomic(BASE/'reservation.json',{'id':h['id'],'binding':fields(h)['binding']})
    return prepare_status(h)
def java_args(data,state):return ['/usr/bin/java','-Xms32m','-Xmx128m','-XX:MaxDirectMemorySize=32m','-XX:+ExitOnOutOfMemoryError',
    '-Dloader.home=/','-Dloader.config.location=file:/dev/null','-Dloader.path=','-Dloader.main=you.v50to.eatwhat.control.bootstrap.MemberIngressRelay',
    '-cp',state['jar'],'org.springframework.boot.loader.launch.PropertiesLauncher',data['serverId']]
def rendered_unit(data,state):
    relay='eatwhat-business-relay-'+data['serverId']+'.service'
    return ('[Unit]\nDescription=EatWhat private member HTTPS\nAfter=network-online.target docker.service wg-quick@ewctrl0.service '+relay+'\n'
        'Requires=docker.service wg-quick@ewctrl0.service '+relay+'\nPartOf=docker.service wg-quick@ewctrl0.service '+relay+'\n'
        '[Service]\nType=exec\nUser=root\nGroup=root\nUMask=0077\n'
        'ExecStartPre=/usr/bin/env -i PATH=/usr/bin:/bin LANG=C.UTF-8 /usr/bin/python3 -I '+str(task(data)/'agent.py')+' guard '+data['id']+'\n'
        'ExecStart=/usr/bin/env -i PATH=/usr/bin:/bin LANG=C.UTF-8 '+' '.join(java_args(data,state))+'\n'
        'Restart=on-failure\nRestartSec=5\nTimeoutStartSec=90\nTimeoutStopSec=15\nKillMode=control-group\n'
        'MemoryMax=384M\nTasksMax=96\nCPUQuota=100%\nLimitNOFILE=256\nNoNewPrivileges=yes\nCapabilityBoundingSet=\n'
        'ProtectSystem=strict\nProtectHome=yes\nPrivateTmp=yes\nPrivateDevices=yes\nProtectKernelTunables=yes\nProtectKernelModules=yes\nProtectControlGroups=yes\n'
        'RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK\nIPAddressDeny=any\nIPAddressAllow=127.0.0.0/8\n'
        'IPAddressAllow='+data['sourceAddress']+'/32\nIPAddressAllow='+data['targetAddress']+'/32\n'
        'StandardOutput=null\nStandardError=null\n[Install]\nWantedBy=multi-user.target\n').encode()
def mint(data,state):
    path=folder(data);absent(path);path.mkdir(mode=0o700);atomic(path/'server.password',(secrets.token_urlsafe(32)+'\n').encode());password=['-storepass:file',str(path/'server.password')]
    common=['-keyalg','EC','-groupname','secp256r1','-storetype','PKCS12','-noprompt'];keytool='/usr/bin/keytool'
    run([keytool,'-genkeypair','-alias','ca','-keystore',str(path/'ca.p12'),*password,*common,'-dname','CN=EatWhat Member CA '+data['serverId'],'-ext','bc:c','-ext','ku=keyCertSign,cRLSign','-validity','3650'],30)
    run([keytool,'-exportcert','-rfc','-alias','ca','-keystore',str(path/'ca.p12'),*password,'-file',str(path/'ca.pem')],30)
    run([keytool,'-genkeypair','-alias','relay','-keystore',str(path/'server.p12'),*password,*common,'-dname','CN=EatWhat Private Member '+data['serverId'],'-validity','365'],30)
    run([keytool,'-certreq','-alias','relay','-keystore',str(path/'server.p12'),*password,'-file',str(path/'relay.csr')],30)
    run([keytool,'-gencert','-rfc','-alias','ca','-keystore',str(path/'ca.p12'),*password,'-infile',str(path/'relay.csr'),'-outfile',str(path/'relay.crt'),
        '-ext','bc=ca:false','-ext','ku=digitalSignature','-ext','eku=serverAuth','-ext','san=ip:'+data['targetAddress'],'-validity','365'],30)
    for alias,name in [('ca','ca.pem'),('relay','relay.crt')]:run([keytool,'-importcert','-noprompt','-alias',alias,'-keystore',str(path/'server.p12'),*password,'-file',str(path/name)],30)
    for p in path.iterdir():private(p)
    atomic(path/'config.json',state['config'])
def material_manifest(data,state):
    path=folder(data);directory(path);files={p.name:digest(private(p)) for p in path.iterdir()};require(set(files)=={'server.password','ca.p12','ca.pem','server.p12','relay.csr','relay.crt','config.json'})
    return {'files':files,'unitSha256':digest(rendered_unit(data,state)),'stateSha256':digest(canonical(state))}
def guard(data,fresh=True):
    original(data);state=current(data,fresh);require(material_manifest(data,state)==json.loads(private(task(data)/'materials.json')),'BOOTSTRAP_MEMBER_INGRESS_MATERIAL_CHANGED')
    require(private(SYSTEM/unit(data))==rendered_unit(data,state),'BOOTSTRAP_MEMBER_INGRESS_UNIT_CHANGED');absent(SYSTEM/(unit(data)+'.d'));return state
def service(data,state):
    def prop(name):return run(['/usr/bin/systemctl','show',unit(data),'--property='+name,'--value']).decode().strip()
    require(prop('ActiveState')=='active' and prop('FragmentPath')==str(SYSTEM/unit(data)) and not prop('DropInPaths')
        and run(['/usr/bin/systemctl','is-enabled',unit(data)]).strip()==b'enabled','BOOTSTRAP_MEMBER_INGRESS_SERVICE_UNCONFIRMED')
    pid=prop('MainPID');require(pid.isdigit() and int(pid)>1);proc=Path('/proc')/pid
    require(proc.stat().st_uid==ROOT_UID and (proc/'exe').resolve()==Path('/usr/bin/java').resolve()
        and [p.decode() for p in (proc/'cmdline').read_bytes().split(b'\0') if p]==java_args(data,state),'BOOTSTRAP_MEMBER_INGRESS_PROCESS_CHANGED')
def tls_probe(address,ca,source=None):
    context=ssl.create_default_context(cadata=ca.decode('ascii'));context.minimum_version=ssl.TLSVersion.TLSv1_2
    for path,status in [('/actuator/health/readiness',200),('/internal/control/v1/snapshot',401)]:
        connection=http.client.HTTPSConnection(address,28490,context=context,timeout=5,source_address=None if source is None else (source,0))
        try:
            connection.request('GET',path,headers={'Connection':'close'});response=connection.getresponse();body=response.read(65537)
            require(response.status==status and len(body)<=65536,'BOOTSTRAP_MEMBER_INGRESS_HTTP_UNCONFIRMED')
            if status==200:require(json.loads(body).get('status')=='UP','BOOTSTRAP_MEMBER_INGRESS_HTTP_UNCONFIRMED')
        finally:connection.close()
def probes(data,state):tls_probe(data['targetAddress'],private(folder(data)/'ca.pem'))
def verify_source(data):
    require(set(data)==set(header(data)) and {'caPem','caSha256'}<=set(data));h={k:v for k,v in data.items() if k not in ['caPem','caSha256']};validate(h)
    require(isinstance(data['caPem'],str) and len(data['caPem'])<=65536 and 'PRIVATE KEY' not in data['caPem'] and digest(data['caPem'].encode())==sha(data['caSha256']))
    net=json.loads(run(['/usr/sbin/ip','-j','-4','address','show','dev','ewctrl0']));require(len(net)==1 and any(a.get('local')==data['sourceAddress'] for a in net[0]['addr_info']),'BOOTSTRAP_MEMBER_INGRESS_SOURCE_ROUTE_REQUIRED')
    route=json.loads(run(['/usr/sbin/ip','-j','-4','route','get',data['targetAddress'],'from',data['sourceAddress']]));require(len(route)==1 and route[0].get('dev')=='ewctrl0','BOOTSTRAP_MEMBER_INGRESS_SOURCE_ROUTE_REQUIRED')
    tls_probe(data['targetAddress'],data['caPem'].encode(),data['sourceAddress'])
    return {**fields(data,'VERIFY_SOURCE'),'state':'PEER_VERIFIED','sourceRouteVerified':True,'targetTlsVerified':True,'healthReady':True,'anonymousSnapshotDenied':True,'memberConfigured':False,'joinAuthorized':False}
def receipt(data):
    path=task(data)/'receipt.json'
    if not path.exists() and not path.is_symlink():return None
    saved=json.loads(private(path));require(all(saved.get(k)==v for k,v in fields(data).items()),'BOOTSTRAP_MEMBER_INGRESS_RECEIPT_CHANGED');return saved
def status(data):
    original(data);saved=receipt(data)
    if saved is None:return {**fields(data),'state':'NOT_STARTED','effectsStarted':False}
    if saved['state']=='HTTPS_READY':
        try:state=guard(data);service(data,state);probes(data,state);return saved
        except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_MEMBER_INGRESS_REVALIDATION_REQUIRED'}
    if saved['state'] in ['ACCEPTED','RUNNING']:
        try:
            active=run(['/usr/bin/systemctl','show','eatwhat-member-ingress-worker-'+data['id'],'--property=ActiveState','--value']).decode().strip()
            if active in ['failed','inactive']:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_MEMBER_INGRESS_WORKER_NOT_RUNNING'}
        except Exception:pass
    return saved
def materials(data):
    require(status(data)['state']=='HTTPS_READY','BOOTSTRAP_MEMBER_INGRESS_NOT_READY');ca=private(folder(data)/'ca.pem')
    return {**fields(data,'MATERIALS'),'state':'HTTPS_READY','caPem':ca.decode('ascii'),'caSha256':digest(ca),'targetAddress':data['targetAddress']}
def dispatch(data):
    original(data);saved=status(data)
    if saved['state']!='NOT_STARTED':return saved
    require(inspect(inspection_input(data))['proof']==data['expectedProof'],'BOOTSTRAP_MEMBER_INGRESS_APPROVAL_EXPIRED')
    with lock():
        if receipt(data) is not None:return status(data)
        saved={**fields(data),'state':'ACCEPTED','effectsStarted':False,'acceptedAt':time.time()};atomic(task(data)/'receipt.json',saved)
    try:run(['/usr/bin/systemd-run','--quiet','--no-block','--unit=eatwhat-member-ingress-worker-'+data['id'],'--property=Type=exec','--property=UMask=0077',
        '--property=RuntimeMaxSec=600','--property=StandardOutput=null','--property=StandardError=null','/usr/bin/python3','-I',str(task(data)/'agent.py'),'run',data['id']])
    except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_MEMBER_INGRESS_DISPATCH_UNCONFIRMED'}
    return saved
def perform(id):
    data=json.loads(private(BASE/identifier(id)/'input.json'));original(data)
    with lock():
        saved=receipt(data);require(saved is not None and saved['state']=='ACCEPTED','BOOTSTRAP_MEMBER_INGRESS_ALREADY_DISPATCHED')
        try:
            require(inspect(inspection_input(data))['proof']==data['expectedProof'],'BOOTSTRAP_MEMBER_INGRESS_APPROVAL_EXPIRED');state=current(data)
            saved.update(state='RUNNING',effectsStarted=True);atomic(task(data)/'receipt.json',saved);mint(data,state)
            atomic(task(data)/'materials.json',material_manifest(data,state));atomic(SYSTEM/unit(data),rendered_unit(data,state))
            run(['/usr/bin/systemctl','daemon-reload']);run(['/usr/bin/systemctl','enable','--now',unit(data)],120);state=guard(data);service(data,state);probes(data,state)
            saved.update(state='HTTPS_READY',finishedAt=time.time(),networkId=state['config']['networkId'],caSha256=digest(private(folder(data)/'ca.pem')),
                targetAddress=data['targetAddress'],tlsListenerVerified=True,healthReady=True,anonymousSnapshotDenied=True,memberConfigured=False,joinAuthorized=False)
        except Refusal as failure:saved.update(state='OUTCOME_UNKNOWN' if saved['effectsStarted'] else 'FAILED',code=failure.code)
        except Exception:saved.update(state='OUTCOME_UNKNOWN' if saved['effectsStarted'] else 'FAILED',code='BOOTSTRAP_MEMBER_INGRESS_PROVISIONING_UNCONFIRMED')
        atomic(task(data)/'receipt.json',saved);return saved
def main():
    try:
        require(os.geteuid()==0 and sys.platform=='linux' and len(sys.argv)>=2)
        if sys.argv[1] in ['run','guard']:
            require(len(sys.argv)==3)
            if sys.argv[1]=='run':perform(sys.argv[2])
            else:guard(json.loads(private(BASE/identifier(sys.argv[2])/'input.json')),False)
            return
        require(len(sys.argv)==2);raw=sys.stdin.buffer.read(262145);require(len(raw)<=262144);data=json.loads(raw)
        result={'inspect':inspect,'prepare':prepare,'prepare-status':prepare_status,'dispatch':dispatch,'status':status,'materials':materials,'verify-source':verify_source}[sys.argv[1]](data)
        print(json.dumps(result,separators=(',',':')))
    except Refusal as failure:print(json.dumps({'protocol':PROTOCOL,'code':failure.code}));sys.exit(1)
    except Exception:print(json.dumps({'protocol':PROTOCOL,'code':'BOOTSTRAP_MEMBER_INGRESS_UNCONFIRMED'}));sys.exit(1)
if __name__=='__main__':main()

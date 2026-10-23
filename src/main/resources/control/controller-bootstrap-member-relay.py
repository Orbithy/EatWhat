"""Once-only TARGET TLS relay provisioning, before member configuration/JOIN.

Loads the exact original business worker and its saved baseline. Never calls
the pre-Docker controller live context, reruns an installer, or exports a key.
"""
import base64, fcntl, hashlib, json, os, re, secrets, socket, ssl, stat, subprocess, sys, tempfile, time, types, uuid
from pathlib import Path
from urllib.parse import urlsplit, parse_qsl

PROTOCOL='eatwhat-controller-bootstrap-member-relay-v1'
PERMISSION='CREATE_BUSINESS_TLS_RELAY'
BASE=Path('/var/lib/eatwhat-controller-bootstrap-member-relay')
BUSINESS=Path('/var/lib/eatwhat-controller-bootstrap-business')
SITES=Path('/opt/eatwhat-sites')
CONTROL=Path('/opt/eatwhat-control')
SYSTEM=Path('/etc/systemd/system')
ROOT_UID=ROOT_GID=0
MAX_INPUT=65536
_BUSINESS=None
class Refusal(Exception):
    def __init__(self,code='BOOTSTRAP_MEMBER_RELAY_INPUT_INVALID'):self.code=code;super().__init__(code)
def require(value,code='BOOTSTRAP_MEMBER_RELAY_INPUT_INVALID'):
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
    require(path.is_dir() and all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_MEMBER_RELAY_UNSAFE_DIRECTORY')
    st=path.stat();require(st.st_uid==ROOT_UID and st.st_gid==ROOT_GID and stat.S_IMODE(st.st_mode)==0o700,'BOOTSTRAP_MEMBER_RELAY_UNSAFE_DIRECTORY')
def private(path,maximum=MAX_INPUT):
    require(path.is_file() and all(not p.is_symlink() for p in [path,*path.parents]),'BOOTSTRAP_MEMBER_RELAY_UNSAFE_FILE')
    st=path.stat();require(st.st_uid==ROOT_UID and st.st_gid==ROOT_GID and stat.S_IMODE(st.st_mode)==0o600 and 0<st.st_size<=maximum,'BOOTSTRAP_MEMBER_RELAY_UNSAFE_FILE')
    raw=path.read_bytes();require(len(raw)<=maximum,'BOOTSTRAP_MEMBER_RELAY_UNSAFE_FILE');return raw
def atomic(path,value):
    require(not path.is_symlink(),'BOOTSTRAP_MEMBER_RELAY_UNSAFE_FILE');raw=value if isinstance(value,bytes) else canonical(value)
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
    require(len(p.stdout)<=65536,'BOOTSTRAP_MEMBER_RELAY_COMMAND_UNCONFIRMED');return p.stdout
def header(data):return {k:v for k,v in data.items() if k!='agent'}
def fields(data,operation='START'):return {'protocol':PROTOCOL,'id':data['id'],'serverId':data['serverId'],'binding':digest(canonical(header(data))),'operation':operation}
def validate(data,mode='header'):
    keys={'id','bootstrapId','serverId','businessId','businessBinding','businessAgentSha256','approvalDigest','permission'}
    if mode!='inspect':keys|={'expectedProof','agentSha256'}
    if mode=='prepare':keys.add('agent')
    require(isinstance(data,dict) and set(data)==keys)
    for key in ['id','bootstrapId','serverId','businessId']:identifier(data[key])
    require(data['id']==phase(data['bootstrapId'],'MEMBER_TLS_RELAY') and data['businessId']==phase(data['bootstrapId'],'BUSINESS_INSTALL'))
    for key in ['businessBinding','businessAgentSha256','approvalDigest','expectedProof','agentSha256']:
        if key in data:sha(data[key])
    require(data['permission']==PERMISSION,'BOOTSTRAP_MEMBER_RELAY_SCOPE_REQUIRED')
    if mode=='prepare':require(isinstance(data['agent'],str) and digest(data['agent'].encode())==data['agentSha256'])
    require(len(canonical(data))<=262144)
def original_business(data,fresh=True):
    global _BUSINESS
    directory(BUSINESS);folder=BUSINESS/data['businessId'];directory(folder)
    raw=private(folder/'input.json',2*1024*1024);prior=json.loads(raw);source=private(folder/'agent.py',524288)
    require(digest(canonical(prior))==data['businessBinding'] and digest(source)==data['businessAgentSha256']==prior['agentSha256']
        and prior['id']==data['businessId'] and prior['bootstrapId']==data['bootstrapId'] and prior['approvalDigest']==data['approvalDigest']
        and prior['business']['serverId']==data['serverId'],'BOOTSTRAP_MEMBER_RELAY_BUSINESS_CHANGED')
    require({'networkPolicySha256','servicesPolicySha256','firewallPolicySha256','expectedIpv6BaselineSha256'}<=set(prior),
        'BOOTSTRAP_MEMBER_RELAY_ORIGINAL_PROOFS_REQUIRED')
    if _BUSINESS is None:
        _BUSINESS=types.ModuleType('original_member_relay_business');exec(compile(source,'<original-business>','exec'),_BUSINESS.__dict__)
    b=_BUSINESS;b.original(prior)
    saved=b.status(prior);require(saved.get('state')=='SUCCEEDED','BOOTSTRAP_MEMBER_RELAY_BUSINESS_REQUIRED')
    # Fresh kernel, services, firewall and IPv6 verification against the original
    # pre-Docker baseline. Historical success alone is insufficient.
    live=saved
    if fresh:
        seen=b.installer().status({'serverId':data['serverId'],'jobId':data['businessId']})
        live=b.finalized(prior,seen,saved);require(live.get('state')=='SUCCEEDED','BOOTSTRAP_MEMBER_RELAY_BUSINESS_REQUIRED')
    return b,prior,live
def referenced(bundle,files,value):
    path=Path(value);require(path.parent==CONTROL/'config' and path.name in files,'BOOTSTRAP_MEMBER_RELAY_UPSTREAM_CA_REQUIRED')
    raw=files[path.name];require(len(raw)<=65536 and b'PRIVATE KEY' not in raw and b'BEGIN CERTIFICATE' in raw,'BOOTSTRAP_MEMBER_RELAY_UPSTREAM_CA_REQUIRED')
    return raw
def transport(bundle):
    p=bundle['properties'];db=urlsplit(p['spring.datasource.url'][5:]);pairs=parse_qsl(db.query,keep_blank_values=True)
    require(len(pairs)==len(dict(pairs)) and db.scheme=='postgresql' and db.hostname=='127.0.0.1' and db.port==25432 and not db.username and not db.password and not db.fragment and re.fullmatch('/[A-Za-z_][A-Za-z0-9_]{0,62}',db.path),
        'BOOTSTRAP_MEMBER_RELAY_UPSTREAM_ROUTE_REQUIRED')
    query=dict(pairs);mode=query.get('sslmode','disable')
    require(mode in ['disable','verify-full'] and not {'sslcert','sslkey','sslpassword'}&query.keys(),'BOOTSTRAP_MEMBER_RELAY_UPSTREAM_TLS_POLICY_REQUIRED')
    require(set(query)<={'sslmode','sslrootcert','connectTimeout','socketTimeout','ApplicationName'} and ('sslrootcert' not in query or mode=='verify-full'),
        'BOOTSTRAP_MEMBER_RELAY_UPSTREAM_TLS_POLICY_REQUIRED')
    origin=urlsplit(p['app.control.deployment.authority-origin']);require(origin.scheme in ['http','https'] and origin.hostname=='127.0.0.1' and origin.port==28090)
    require(origin.path in ['','/'] and not origin.query and not origin.fragment and origin.username is None)
    http=origin.scheme=='https';database=mode=='verify-full';files={k:base64.b64decode(v,validate=True) for k,v in bundle['privateFiles'].items()};cas={}
    if http:
        name=p.get('app.control.deployment.authority-ca-file',p.get('app.control.node-ca-file',p.get('app.cluster.node-ca-file','')));require(name,'BOOTSTRAP_MEMBER_RELAY_UPSTREAM_CA_REQUIRED');cas['http']=referenced(bundle,files,name)
    if database:
        require(query.get('sslrootcert'),'BOOTSTRAP_MEMBER_RELAY_UPSTREAM_CA_REQUIRED');cas['database']=referenced(bundle,files,query['sslrootcert'])
    for raw in cas.values():ssl.create_default_context(cadata=raw.decode('ascii'))
    return {'httpUpstreamTls':http,'databaseUpstreamTls':database,'httpUpstreamPeerName':'127.0.0.1' if http else '',
        'databaseUpstreamPeerName':'127.0.0.1' if database else '', 'databaseChannelBinding':'disable','databaseName':db.path[1:]},{name:base64.b64encode(raw).decode() for name,raw in cas.items()}
def current(data,fresh=True):
    b,prior,live=original_business(data,fresh);n=b.network_policy();q={'serverId':data['serverId'],'jobId':data['businessId'],
        'approvalDigest':data['approvalDigest'],'inputBinding':data['businessBinding'],'expectedNetworkId':live['createdNetworkId'],**prior['networkEffects']}
    graph=n.observe_kernel_graph(q) if fresh else {'dockerNetworks':n.docker_networks()}
    own=graph['dockerNetworks'][live['createdNetworkId']]
    require(own['Labels'].get(n.SERVER_LABEL)==data['serverId'] and own['Labels'].get(n.JOB_LABEL)==data['businessId']
        and own['Name']=='eatwhat-site-'+data['serverId'][:8]+'_default' and len(own['Containers'])==3,'BOOTSTRAP_MEMBER_RELAY_NETWORK_CHANGED')
    if not fresh:
        # A service restart may legitimately see new container PIDs/IDs. It
        # must still see exactly the three original job-owned services on the
        # same full network ID; never persist Docker inspect's secret Env.
        rows=n.json_command([n.executable('docker'),'inspect',*own['Containers']]);services=set()
        require(len(rows)==3)
        for row in rows:
            lab=n.labels(row['Config'].get('Labels'));name=lab.get('com.docker.compose.service')
            require(row['Id'] in own['Containers'] and row['State']['Running'] is True and lab.get(n.SERVER_LABEL)==data['serverId']
                and lab.get(n.JOB_LABEL)==data['businessId'] and lab.get('com.docker.compose.project')=='eatwhat-site-'+data['serverId'][:8]
                and name in ['app','postgres','redis'] and name not in services and row['HostConfig']['NetworkMode']==own['Name']
                and set(row['NetworkSettings']['Networks'])=={own['Name']},'BOOTSTRAP_MEMBER_RELAY_NETWORK_CHANGED');services.add(name)
    bridge,subnet,gateway=n.network_descriptor(own)
    require(bridge=='br-'+live['createdNetworkId'][:12],'BOOTSTRAP_MEMBER_RELAY_BRIDGE_REQUIRED')
    c,controller=b.controller(prior,False);bundle=controller['bundle'];upstream,ca=transport(bundle)
    artifact=CONTROL/'acceptance'/(bundle['controllerSha256']+'.jar')
    # The capability-empty root service cannot traverse the controller user's
    # private acceptance directory. Its public executable is staged, byte for
    # byte, in the relay's own root-only directory by the privileged provisioner.
    jar=folder(data)/'runtime.jar'
    checked=artifact if fresh else jar
    if not fresh:private(jar,256*1024*1024)
    c.policy().verify_jar(checked,bundle)
    # The original accepted artifact must contain this executable component.
    import zipfile
    with zipfile.ZipFile(checked) as archive:
        require(archive.read('META-INF/eatwhat-business-dependency-relay-v1')==b'eatwhat-business-dependency-relay-v1\n')
        require(archive.read('META-INF/eatwhat-business-dependency-relay-scoped-trust-v1')==b'eatwhat-business-dependency-relay-scoped-trust-v1\n')
        require('BOOT-INF/classes/you/v50to/eatwhat/control/bootstrap/BusinessDependencyRelay.class' in archive.namelist())
    config={'serverId':data['serverId'],'networkId':live['createdNetworkId'],'listenAddress':str(gateway),'clientSubnet':str(subnet),'maxConnections':16,
        'databaseUsername':'eatwhat_auth_'+data['serverId'].replace('-',''),**upstream}
    return {'config':config,'upstreamCa':ca,'jar':str(jar),'artifactSource':str(artifact),
        'controllerSha256':bundle['controllerSha256'],'controllerSize':bundle['controllerSize'],
        'createdNetworkProofSha256':live['createdNetworkProofSha256'],'networkBaselineSha256':live['networkBaselineSha256'],
        'networkDescriptionSha256':digest(canonical({k:v for k,v in own.items() if k!='Containers'}))}
def folder(data):return SITES/data['serverId']/'private/dependency-relay'
def unit(data):return 'eatwhat-business-relay-'+data['serverId']+'.service'
def absent(path):require(not path.exists() and not path.is_symlink(),'BOOTSTRAP_MEMBER_RELAY_RETAINED')
def inspect(data):
    validate(data,'inspect');state=current(data);directory(SITES/data['serverId']/'private');absent(folder(data));absent(SYSTEM/unit(data));absent(SYSTEM/(unit(data)+'.d'))
    for port in [28443,25443]:
        with socket.socket() as stream:stream.bind((state['config']['listenAddress'],port))
    return {**fields(data,'INSPECT'),'effectsStarted':False,'proof':digest(canonical({'header':data,'state':state}))}
def inspection_input(data):return {k:v for k,v in data.items() if k not in ['agent','expectedProof','agentSha256']}
def task(data):return BASE/data['id']
def lock(create=False):
    if create and not BASE.exists():BASE.mkdir(mode=0o700)
    directory(BASE);path=BASE/'host.lock'
    if create and not path.exists():
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600);os.write(fd,b'lock\n');os.close(fd)
    private(path);stream=path.open('r+')
    try:fcntl.lockf(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except Exception:stream.close();raise Refusal('BOOTSTRAP_MEMBER_RELAY_HOST_BUSY')
    return stream
def original(data):
    validate(data);directory(BASE);directory(task(data));require(json.loads(private(BASE/'reservation.json'))=={'id':data['id'],'binding':fields(data)['binding']},'BOOTSTRAP_MEMBER_RELAY_HOST_RESERVED')
    require(json.loads(private(task(data)/'input.json'))==data and digest(private(task(data)/'agent.py',262144))==data['agentSha256'],'BOOTSTRAP_MEMBER_RELAY_ORIGINAL_CHANGED')
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
            require(all(p.name=='host.lock' for p in BASE.iterdir()),'BOOTSTRAP_MEMBER_RELAY_RETAINED')
    require(inspect(inspection_input(h))['proof']==h['expectedProof'],'BOOTSTRAP_MEMBER_RELAY_APPROVAL_EXPIRED')
    with lock(True):
        absent(BASE/'reservation.json');task(h).mkdir(mode=0o700);atomic(task(h)/'input.json',h);atomic(task(h)/'agent.py',data['agent'].encode())
        atomic(BASE/'reservation.json',{'id':h['id'],'binding':fields(h)['binding']})
    return prepare_status(h)
def java_args(data,state):return ['/usr/bin/java','-Xms32m','-Xmx128m','-XX:MaxDirectMemorySize=32m','-XX:+ExitOnOutOfMemoryError',
    '-Dloader.main=you.v50to.eatwhat.control.bootstrap.BusinessDependencyRelay','-cp',state['jar'],'org.springframework.boot.loader.launch.PropertiesLauncher',data['serverId']]
def rendered_unit(data,state):
    # All interpolated fields are fixed paths, canonical UUIDs or proved IPv4/CIDR.
    return ('[Unit]\nDescription=EatWhat private business TLS relay\nAfter=network-online.target docker.service eatwhat-control.service\n'
        'Requires=docker.service eatwhat-control.service\nPartOf=docker.service eatwhat-control.service\nStartLimitIntervalSec=60\nStartLimitBurst=3\n[Service]\nType=exec\nUser=root\nGroup=root\nUMask=0077\n'
        'ExecStartPre=/usr/bin/env -i PATH=/usr/bin:/bin LANG=C.UTF-8 /usr/bin/python3 -I '+str(task(data)/'agent.py')+' guard '+data['id']+'\n'
        'ExecStart=/usr/bin/env -i PATH=/usr/bin:/bin LANG=C.UTF-8 '+' '.join(java_args(data,state))+'\n'
        'Restart=on-failure\nRestartSec=5\nTimeoutStartSec=90\nTimeoutStopSec=15\nKillMode=control-group\n'
        'MemoryMax=384M\nTasksMax=96\nCPUQuota=100%\nLimitNOFILE=256\nNoNewPrivileges=yes\nCapabilityBoundingSet=\n'
        'ProtectSystem=strict\nProtectHome=yes\nPrivateTmp=yes\nPrivateDevices=yes\nProtectKernelTunables=yes\nProtectKernelModules=yes\nProtectControlGroups=yes\n'
        'RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6\nIPAddressDeny=any\nIPAddressAllow=127.0.0.0/8\nIPAddressAllow='+state['config']['clientSubnet']+'\n'
        'StandardOutput=null\nStandardError=null\n[Install]\nWantedBy=multi-user.target\n').encode()
def mint(data,state):
    path=folder(data);absent(path);path.mkdir(mode=0o700);atomic(path/'server.password',(secrets.token_urlsafe(32)+'\n').encode())
    password=['-storepass:file',str(path/'server.password')];keytool='/usr/bin/keytool'
    common=['-keyalg','EC','-groupname','secp256r1','-storetype','PKCS12','-noprompt']
    run([keytool,'-genkeypair','-alias','ca','-keystore',str(path/'ca.p12'),*password,*common,'-dname','CN=EatWhat Relay CA '+data['serverId'],'-ext','bc:c','-ext','ku=keyCertSign,cRLSign','-validity','3650'],30)
    run([keytool,'-exportcert','-rfc','-alias','ca','-keystore',str(path/'ca.p12'),*password,'-file',str(path/'ca.pem')],30)
    run([keytool,'-genkeypair','-alias','relay','-keystore',str(path/'server.p12'),*password,*common,'-dname','CN=EatWhat Private Relay '+data['serverId'],'-validity','365'],30)
    run([keytool,'-certreq','-alias','relay','-keystore',str(path/'server.p12'),*password,'-file',str(path/'relay.csr')],30)
    run([keytool,'-gencert','-rfc','-alias','ca','-keystore',str(path/'ca.p12'),*password,'-infile',str(path/'relay.csr'),'-outfile',str(path/'relay.crt'),
        '-ext','bc=ca:false','-ext','ku=digitalSignature','-ext','eku=serverAuth','-ext','san=ip:'+state['config']['listenAddress'],'-validity','365'],30)
    for alias,name in [('ca','ca.pem'),('relay','relay.crt')]:run([keytool,'-importcert','-noprompt','-alias',alias,'-keystore',str(path/'server.p12'),*password,'-file',str(path/name)],30)
    # UMask=0077 is set by the worker; keytool outputs are independently checked.
    for p in path.iterdir():private(p)
    atomic(path/'config.json',state['config'])
    for name,raw in state['upstreamCa'].items():atomic(path/('upstream-'+name+'-ca.pem'),base64.b64decode(raw,validate=True))
def stage_runtime_artifact(data,state):
    path=folder(data);directory(path);absent(path/'runtime.jar')
    require(type(state['controllerSize']) is int and 0<state['controllerSize']<=256*1024*1024,'BOOTSTRAP_MEMBER_RELAY_ARTIFACT_CHANGED')
    executable=Path(state['artifactSource']).read_bytes()
    require(len(executable)==state['controllerSize'] and digest(executable)==state['controllerSha256'],'BOOTSTRAP_MEMBER_RELAY_ARTIFACT_CHANGED')
    atomic(path/'runtime.jar',executable)
def material_manifest(data,state):
    path=folder(data);directory(path);files={p.name:digest(private(p,256*1024*1024 if p.name=='runtime.jar' else MAX_INPUT)) for p in path.iterdir()}
    expected={'server.password','ca.p12','ca.pem','server.p12','relay.csr','relay.crt','config.json','runtime.jar'}|{'upstream-'+name+'-ca.pem' for name in state['upstreamCa']}
    require(set(files)==expected);return {'files':files,'unitSha256':digest(rendered_unit(data,state)),'stateSha256':digest(canonical(state))}
def guard(data,fresh=True):
    # The sandboxed unit's pre-start check validates immutable materials and
    # the full owned Docker network ID/labels/members. It does not need nsenter,
    # nft capabilities or private NFS connectivity. The privileged provisioner
    # and status observer also repeat the complete original successor proof.
    original(data);state=current(data,fresh);saved=json.loads(private(task(data)/'materials.json'))
    require(material_manifest(data,state)==saved,'BOOTSTRAP_MEMBER_RELAY_MATERIAL_CHANGED')
    path=SYSTEM/unit(data);require(private(path)==rendered_unit(data,state),'BOOTSTRAP_MEMBER_RELAY_UNIT_CHANGED');absent(SYSTEM/(unit(data)+'.d'))
    return state
def service(data,state):
    def prop(name):return run(['/usr/bin/systemctl','show',unit(data),'--property='+name,'--value']).decode().strip()
    require(prop('ActiveState')=='active' and prop('FragmentPath')==str(SYSTEM/unit(data)) and not prop('DropInPaths')
        and run(['/usr/bin/systemctl','is-enabled',unit(data)]).strip()==b'enabled','BOOTSTRAP_MEMBER_RELAY_SERVICE_UNCONFIRMED')
    pid=prop('MainPID');require(pid.isdigit() and int(pid)>1);proc=Path('/proc')/pid
    require(proc.stat().st_uid==ROOT_UID and (proc/'exe').resolve()==Path('/usr/bin/java').resolve()
        and [p.decode() for p in (proc/'cmdline').read_bytes().split(b'\0') if p]==java_args(data,state),'BOOTSTRAP_MEMBER_RELAY_PROCESS_CHANGED')
def tls_probes(data,state):
    context=ssl.create_default_context(cafile=str(folder(data)/'ca.pem'));address=state['config']['listenAddress']
    with socket.create_connection((address,25443),3) as raw:
        raw.sendall(bytes.fromhex('0000000804d2162f'));require(raw.recv(1)==b'S')
        with context.wrap_socket(raw,server_hostname=address) as tls:require(tls.version() in ['TLSv1.2','TLSv1.3'])
    with socket.create_connection((address,28443),3) as raw:
        with context.wrap_socket(raw,server_hostname=address) as tls:
            tls.sendall(b'GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n');response=b''
            while block:=tls.recv(4096):
                response+=block;require(len(response)<=65536)
    require(response.startswith(b'HTTP/1.1 200 ') or response.startswith(b'HTTP/1.0 200 '),'BOOTSTRAP_MEMBER_RELAY_HTTP_UNCONFIRMED')
def probe_process(data,state):
    # Probe the approved client path, not host-to-own-bridge traffic. The latter
    # can be filtered by the unit's IP allowlist even while container TLS works.
    b,prior,live=original_business(data,False);n=b.network_policy();networks=n.docker_networks()
    own=networks.get(state['config']['networkId']);q={'serverId':data['serverId'],'jobId':data['businessId']}
    require(isinstance(own,dict) and own['Id']==state['config']['networkId']
        and own['Name']=='eatwhat-site-'+data['serverId'][:8]+'_default'
        and own['Labels'].get(n.SERVER_LABEL)==data['serverId'] and own['Labels'].get(n.JOB_LABEL)==data['businessId']
        and len(own['Containers'])==3,'BOOTSTRAP_MEMBER_RELAY_NETWORK_CHANGED')
    rows=[n.container_observation(cid,own['Name'],q) for cid in own['Containers']]
    require({row['Labels'].get('com.docker.compose.service') for row in rows}=={'app','postgres','redis'},'BOOTSTRAP_MEMBER_RELAY_NETWORK_CHANGED')
    app=next(row for row in rows if row['Labels']['com.docker.compose.service']=='app');process=app['process']
    require(n.namespace_identity(process['pid'])==process,'BOOTSTRAP_MEMBER_RELAY_NETWORK_CHANGED');return n,process
def probes(data,state):
    n,process=probe_process(data,state)
    # Hold the namespace inode itself across nsenter; a recycled PID cannot
    # select a different namespace between observation and exec. The host
    # filesystem remains unchanged and no container command or shell is used.
    fd=os.open('/proc/'+str(process['pid'])+'/ns/net',os.O_RDONLY|os.O_CLOEXEC)
    try:
        require(os.fstat(fd).st_ino==process['netnsInode'] and n.namespace_identity(process['pid'])==process,'BOOTSTRAP_MEMBER_RELAY_NETWORK_CHANGED')
        child="import hashlib,json,pathlib,sys,types;h=json.load(sys.stdin);raw=pathlib.Path(h['agentPath']).read_bytes();assert hashlib.sha256(raw).hexdigest()==h['agentSha256'];m=types.ModuleType('sealed_relay_probe');exec(compile(raw,'<sealed-relay-probe>','exec'),m.__dict__);m.tls_probes({'serverId':h['serverId']},{'config':{'listenAddress':h['address']}});print('TLS_PROBES_READY')"
        payload={key:data[key] for key in ['id','serverId','agentSha256']};payload.update(address=state['config']['listenAddress'],agentPath=str(task(data)/'agent.py'))
        result=subprocess.run([n.executable('nsenter'),'--net=/proc/self/fd/'+str(fd),'--','/usr/bin/python3','-I','-c',child],
            input=canonical(payload),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=20,pass_fds=(fd,),cwd='/',env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','HOME':'/root'})
        require(n.namespace_identity(process['pid'])==process,'BOOTSTRAP_MEMBER_RELAY_NETWORK_CHANGED')
        require(result.returncode==0 and result.stdout==b'TLS_PROBES_READY\n','BOOTSTRAP_MEMBER_RELAY_CLIENT_PROBES_UNCONFIRMED')
    finally:os.close(fd)
def receipt(data):
    path=task(data)/'receipt.json'
    if not path.exists() and not path.is_symlink():return None
    saved=json.loads(private(path));require(all(saved.get(k)==v for k,v in fields(data).items()),'BOOTSTRAP_MEMBER_RELAY_RECEIPT_CHANGED');return saved
def status(data):
    original(data);saved=receipt(data)
    if saved is None:return {**fields(data),'state':'NOT_STARTED','effectsStarted':False}
    # Reading status never repairs, rotates, starts or enables anything.
    if saved['state']=='RELAY_READY':
        try:state=guard(data);service(data,state);probes(data,state);return saved
        except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_MEMBER_RELAY_REVALIDATION_REQUIRED'}
    if saved['state'] in ['ACCEPTED','RUNNING']:
        try:
            active=run(['/usr/bin/systemctl','show','eatwhat-member-relay-'+data['id'],'--property=ActiveState','--value']).decode().strip()
            if active in ['failed','inactive']:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_MEMBER_RELAY_WORKER_NOT_RUNNING'}
        except Exception:pass
    return saved
def dispatch(data):
    original(data);saved=status(data)
    if saved['state']!='NOT_STARTED':return saved
    require(inspect(inspection_input(data))['proof']==data['expectedProof'],'BOOTSTRAP_MEMBER_RELAY_APPROVAL_EXPIRED')
    with lock():
        if receipt(data) is not None:return status(data)
        saved={**fields(data),'state':'ACCEPTED','effectsStarted':False,'acceptedAt':time.time()};atomic(task(data)/'receipt.json',saved)
    try:run(['/usr/bin/systemd-run','--quiet','--no-block','--unit=eatwhat-member-relay-'+data['id'],'--property=Type=exec','--property=UMask=0077',
        '--property=RuntimeMaxSec=600','--property=StandardOutput=null','--property=StandardError=null','/usr/bin/python3','-I',str(task(data)/'agent.py'),'run',data['id']])
    except Exception:return {**saved,'state':'OUTCOME_UNKNOWN','code':'BOOTSTRAP_MEMBER_RELAY_DISPATCH_UNCONFIRMED'}
    return saved
def perform(id):
    data=json.loads(private(BASE/identifier(id)/'input.json'));original(data)
    with lock():
        saved=receipt(data);require(saved is not None and saved['state']=='ACCEPTED','BOOTSTRAP_MEMBER_RELAY_ALREADY_DISPATCHED')
        try:
            require(inspect(inspection_input(data))['proof']==data['expectedProof'],'BOOTSTRAP_MEMBER_RELAY_APPROVAL_EXPIRED')
            state=current(data);saved.update(state='RUNNING',effectsStarted=True);atomic(task(data)/'receipt.json',saved)
            mint(data,state);stage_runtime_artifact(data,state);atomic(task(data)/'materials.json',material_manifest(data,state));atomic(SYSTEM/unit(data),rendered_unit(data,state))
            run(['/usr/bin/systemctl','daemon-reload']);run(['/usr/bin/systemctl','enable','--now',unit(data)],120)
            state=guard(data);service(data,state);probes(data,state)
            saved.update(state='RELAY_READY',finishedAt=time.time(),networkId=state['config']['networkId'],listenAddress=state['config']['listenAddress'],
                caSha256=digest(private(folder(data)/'ca.pem')),materialManifestSha256=digest(private(task(data)/'materials.json')),
                tlsListenerVerified=True,authorityHttpVerified=True,databaseAuthenticated=False,memberConfigured=False,joinAuthorized=False)
        except Refusal as failure:saved.update(state='OUTCOME_UNKNOWN' if saved['effectsStarted'] else 'FAILED',code=failure.code)
        except Exception:saved.update(state='OUTCOME_UNKNOWN' if saved['effectsStarted'] else 'FAILED',code='BOOTSTRAP_MEMBER_RELAY_PROVISIONING_UNCONFIRMED')
        atomic(task(data)/'receipt.json',saved)
    return saved
def materials(data):
    require(status(data)['state']=='RELAY_READY','BOOTSTRAP_MEMBER_RELAY_NOT_READY')
    return {**fields(data,'MATERIALS'),'caPem':private(folder(data)/'ca.pem').decode('ascii'),'config':json.loads(private(folder(data)/'config.json'))}
def main():
    try:
        require(os.geteuid()==0 and sys.platform=='linux');require(len(sys.argv)==3 or len(sys.argv)==2)
        action=sys.argv[1]
        if action in ['run','guard']:
            require(len(sys.argv)==3);id=identifier(sys.argv[2]);data=json.loads(private(BASE/id/'input.json'));result=perform(id) if action=='run' else guard(data,False)
            if action=='guard':return
        else:
            raw=sys.stdin.buffer.read(262145);require(len(raw)<=262144);data=json.loads(raw)
            result={'inspect':inspect,'prepare':prepare,'prepare-status':prepare_status,'dispatch':dispatch,'status':status,'materials':materials}[action](data)
        print(json.dumps(result,separators=(',',':')))
    except Exception:print(json.dumps({'protocol':PROTOCOL,'state':'REJECTED','code':'BOOTSTRAP_MEMBER_RELAY_PRECONDITION_FAILED'}));sys.exit(1)
if __name__=='__main__':main()

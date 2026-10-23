"""Read-only checks shared by the pending registered-cluster release runner.

This module has no command entry point and cannot install, recreate or restore a
service. The eventual runner embeds the reviewed legacy helper as RELEASE_SOURCE.
"""
import base64, contextlib, fcntl, hashlib, hmac, json, os, re, stat, sys, types, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path

BASE=Path('/opt/eatwhat-sites')
ROOT_UID=0
APP_UID=10001
APP_GID=10001
RELEASE_SOURCE='__EATWHAT_RELEASE_AGENT_BASE64__'

class ReleaseRejected(Exception):
    def __init__(self,code):self.code=code

def require(value,code):
    if not value:raise ReleaseRejected(code)

def canonical(value):
    try:require(isinstance(value,str) and str(uuid.UUID(value))==value,'RELEASE_INVALID_ID');return value
    except (ValueError,TypeError,AttributeError):raise ReleaseRejected('RELEASE_INVALID_ID') from None

def safe(path):
    path=Path(path)
    require(path.is_absolute(),'RELEASE_UNSAFE_PATH')
    for parent in [path,*path.parents]:require(not parent.is_symlink(),'RELEASE_UNSAFE_PATH')
    return path

def folder(path,owners,mode=0o700):
    path=safe(path)
    try:info=path.stat()
    except OSError:raise ReleaseRejected('RELEASE_FILES_INCOMPLETE') from None
    require(stat.S_ISDIR(info.st_mode) and info.st_uid in owners and stat.S_IMODE(info.st_mode)==mode,'RELEASE_UNSAFE_DIRECTORY')
    return path

def trusted_ancestors(root):
    for parent in root.parents:
        info=safe(parent).stat();require(info.st_uid==ROOT_UID and not stat.S_IMODE(info.st_mode)&0o022,'RELEASE_UNSAFE_DIRECTORY')

def private(path,limit=262144,owners=None):
    safe(path)
    try:
        fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(fd,'rb') as source:
            info=os.fstat(source.fileno());require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode)==0o600 and info.st_uid in (owners or {ROOT_UID}) and info.st_size<=limit,'RELEASE_PRIVATE_FILE_REQUIRED')
            data=source.read(limit+1);require(len(data)<=limit,'RELEASE_FILE_TOO_LARGE');return data
    except ReleaseRejected:raise
    except OSError:raise ReleaseRejected('RELEASE_FILES_INCOMPLETE') from None

def document(path):
    try:value=json.loads(private(path));require(isinstance(value,dict),'RELEASE_INVALID_DOCUMENT');return value
    except (ValueError,UnicodeError):raise ReleaseRejected('RELEASE_INVALID_DOCUMENT') from None

def properties(raw):
    def unescape(value):
        require(not re.search(r'\\u(?![0-9A-Fa-f]{4})',value),'RELEASE_PROPERTIES_UNSUPPORTED')
        return re.sub(r'\\u([0-9A-Fa-f]{4})|\\(.)',lambda m:chr(int(m[1],16)) if m[1] else {'t':'\t','n':'\n','r':'\r','f':'\f'}.get(m[2],m[2]),value)
    values={}
    for line in raw.decode('ISO-8859-1').splitlines():
        if not line.strip() or line.lstrip().startswith(('#','!')):continue
        require(len(line)-len(line.rstrip('\\'))<2 and not line.endswith('\\'),'RELEASE_PROPERTIES_UNSUPPORTED')
        match=re.fullmatch(r'\s*([^=:\s\\]+)\s*[=:]\s*(.*)',line);require(match is not None,'RELEASE_PROPERTIES_UNSUPPORTED')
        key=match[1];require(key not in values,'RELEASE_PROPERTIES_DUPLICATE');values[key]=unescape(match[2])
    return values

def digest(path):
    safe(path)
    try:
        fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(fd,'rb') as source:
            info=os.fstat(source.fileno());require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode)==0o600 and info.st_uid in {ROOT_UID,APP_UID} and 0<info.st_size<=536870912,'RELEASE_PRIVATE_ARTIFACT_REQUIRED')
            return hashlib.file_digest(source,'sha256').hexdigest()
    except OSError:raise ReleaseRejected('RELEASE_FILES_INCOMPLETE') from None

def validated_header(header):
    require(isinstance(header,dict),'RELEASE_INVALID_REQUEST')
    for key in ['id','serverId','instanceId','clusterId']:canonical(header.get(key))
    require(isinstance(header.get('siteId'),str) and re.fullmatch(r'[A-Za-z0-9_-]{1,64}',header['siteId']),'RELEASE_INVALID_REQUEST')
    for key,size in [('databaseFingerprint',32),('artifactSha256',64),('fromSha256',64),('manifestHash',64)]:require(isinstance(header.get(key),str) and re.fullmatch('[0-9a-f]{'+str(size)+'}',header[key]),'RELEASE_INVALID_REQUEST')
    require(type(header.get('epoch')) is int and header['epoch']>0,'RELEASE_INVALID_REQUEST')
    require(isinstance(header.get('restoreEvidenceId'),str) and 0<len(header['restoreEvidenceId'])<=256,'RELEASE_RESTORE_EVIDENCE_REQUIRED')
    return header

def callback_origin(value):
    try:
        uri=urllib.parse.urlsplit(value);port=uri.port
        require(uri.scheme=='https' or uri.scheme=='http' and uri.hostname=='127.0.0.1','RELEASE_AUTHORITY_ORIGIN_INVALID')
        require(uri.hostname and uri.username is None and uri.password is None and not uri.query and not uri.fragment and uri.path in ['','/'] and (port is None or 1<=port<=65535),'RELEASE_AUTHORITY_ORIGIN_INVALID')
        return value.rstrip('/')
    except (ValueError,TypeError,AttributeError):raise ReleaseRejected('RELEASE_AUTHORITY_ORIGIN_INVALID') from None

def request_json(url,headers=None,ca_pem=None):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs):return None
    try:
        handlers=[urllib.request.ProxyHandler({}),NoRedirect()]
        if ca_pem is not None:
            import ssl
            require(urllib.parse.urlsplit(url).scheme=='https' and isinstance(ca_pem,str) and 0<len(ca_pem)<=16384 and ca_pem.strip().startswith('-----BEGIN CERTIFICATE-----') and ca_pem.strip().endswith('-----END CERTIFICATE-----') and 'PRIVATE KEY' not in ca_pem,'RELEASE_AUTHORITY_CA_INVALID')
            context=ssl.create_default_context();context.load_verify_locations(cadata=ca_pem)
            handlers.append(urllib.request.HTTPSHandler(context=context))
        opener=urllib.request.build_opener(*handlers)
        with opener.open(urllib.request.Request(url,headers=headers or {}),timeout=3) as response:
            raw=response.read(65537);require(response.status==200 and len(raw)<=65536,'RELEASE_AUTHORITY_UNAVAILABLE');value=json.loads(raw);require(isinstance(value,dict),'RELEASE_AUTHORITY_INVALID_RESPONSE');return value
    except ReleaseRejected:raise
    except urllib.error.HTTPError as failure:
        failure.close();raise ReleaseRejected('RELEASE_AUTHORITY_UNAVAILABLE') from None
    except Exception:raise ReleaseRejected('RELEASE_AUTHORITY_UNAVAILABLE') from None

def read_authority(header,fetch=request_json):
    origin=callback_origin(header.get('authorityOrigin'));token=header.get('authorityToken')
    require(isinstance(token,str) and re.fullmatch(r'[A-Za-z0-9_-]{43}',token),'RELEASE_AUTHORITY_CAPABILITY_REQUIRED')
    url=origin+'/internal/deployment/v1/fences/'+header['id']+'?manifest='+header['manifestHash'];headers={'X-Deployment-Token':token}
    proof=fetch(url,headers,header['authorityCaPem']) if 'authorityCaPem' in header else fetch(url,headers)
    require(proof.get('protocol')=='eatwhat-deployment-fence-v1','RELEASE_AUTHORITY_INVALID_RESPONSE')
    require(type(proof.get('epoch')) is int,'RELEASE_AUTHORITY_INVALID_RESPONSE')
    for key in ['id','clusterId','epoch','manifestHash']:require(proof.get(key)==header[key],'RELEASE_AUTHORITY_BINDING_CHANGED')
    expected={key:header[key] for key in ['siteId','instanceId','databaseFingerprint']}
    targets=proof.get('targets');require(isinstance(targets,list) and 1<=len(targets)<=16 and sum(target==expected for target in targets)==1,'RELEASE_AUTHORITY_BINDING_CHANGED')
    return proof

def verify_authority(header,fetch=request_json):
    proof=read_authority(header,fetch)
    require(proof.get('state')=='ACTIVE' and proof.get('authorized') is True and proof.get('dispatchAuthorized') is True,'RELEASE_AUTHORITY_NOT_AUTHORIZED')
    return proof

def validate_compose(root,value,port):
    require(isinstance(value,dict) and set(value)<= {'name','services','volumes','networks'} and set(value.get('services',{}))=={'app','postgres','redis'},'RELEASE_COMPOSE_UNSUPPORTED')
    services=value['services'];app=services['app'];pg=services['postgres'];redis=services['redis']
    allowed={'image','restart','pull_policy','labels','user','read_only','tmpfs','volumes','entrypoint','command','ports','depends_on','environment','healthcheck','shm_size','extra_hosts'}
    for name,service in services.items():
        require(isinstance(service,dict) and set(service)<=allowed,'RELEASE_UNSAFE_COMPOSE')
        require(isinstance(service.get('image'),str) and re.fullmatch(r'sha256:[0-9a-f]{64}',service['image']),'RELEASE_IMAGE_NOT_PINNED')
        require(service.get('pull_policy')=='never','RELEASE_IMAGE_PULL_FORBIDDEN')
        labels=service.get('labels',{});require(isinstance(labels,dict) and labels.get('you.v50to.eatwhat.server-id',root.name)==root.name,'RELEASE_PROJECT_BINDING_CHANGED')
    require(app.get('user')==f'{APP_UID}:{APP_GID}' and app.get('read_only') is True and app.get('entrypoint')==['java'],'RELEASE_UNSAFE_APPLICATION')
    require(app.get('command')==['-Xms64m','-Xmx384m','-jar','/app/app.jar','--spring.config.location=file:/config/application.properties','--spring.config.additional-location=','--spring.config.import='],'RELEASE_UNSAFE_APPLICATION')
    require(app.get('volumes')==['${APP_JAR}:/app/app.jar:ro',str(root/'config')+':/config:ro',str(root/'app-state')+':/state'],'RELEASE_UNSAFE_MOUNTS')
    require(app.get('ports')==['127.0.0.1:'+str(port)+':8080'],'RELEASE_UNSAFE_APPLICATION_PORT')
    require(not app.get('environment') and app.get('tmpfs')==['/tmp'],'RELEASE_UNSAFE_APPLICATION')
    require(pg.get('volumes')==['pg_data:/var/lib/postgresql/data',str(root/'deploy/initdb.sh')+':/docker-entrypoint-initdb.d/20-create-application-db.sh:ro'] and redis.get('volumes')==['redis_data:/data'],'RELEASE_UNSAFE_MOUNTS')
    require(not pg.get('command') and not pg.get('entrypoint') and not redis.get('entrypoint') and not redis.get('ports'),'RELEASE_UNSAFE_COMPOSE')
    for published in pg.get('ports',[]):require(isinstance(published,str) and re.fullmatch(r'127\.0\.0\.1:[1-9][0-9]{3,4}:5432',published) and 1024<=int(published.split(':')[1])<=65535,'RELEASE_UNSAFE_DATABASE_PORT')
    for key,names in [('volumes',{'pg_data','redis_data'}),('networks',{'default'})]:
        entries=value.get(key,{});require(isinstance(entries,dict) and (set(entries)==names if key=='volumes' else set(entries)<=names),'RELEASE_UNSAFE_COMPOSE')
        for entry in entries.values():require(isinstance(entry,dict) and set(entry)<= {'labels'},'RELEASE_UNSAFE_COMPOSE')

class ReleaseContext:
    """Construction, prepare and status checks have no filesystem mutations."""
    def __init__(self,header):
        self.h=validated_header(header);require(sys.platform=='linux' and os.geteuid()==ROOT_UID,'RELEASE_ROOT_LINUX_REQUIRED')
        self.root=folder(BASE/self.h['serverId'],{ROOT_UID})
        # Trusted ancestors may be searchable, but cannot be writable by other users.
        trusted_ancestors(self.root)
        for name in ['deploy','releases','private','config','app-state']:folder(self.root/name,{ROOT_UID,APP_UID})
        receipt=document(self.root/'installation.json');require(receipt.get('state')=='SUCCEEDED' and receipt.get('protocol') in ['eatwhat-install-v1','eatwhat-install-recovery-v1'],'RELEASE_INSTALLATION_NOT_SUCCEEDED')
        for key in ['serverId','siteId','instanceId']:require(receipt.get(key)==self.h[key],'RELEASE_INSTALLATION_IDENTITY_CHANGED')
        require(receipt.get('root')==str(self.root),'RELEASE_INSTALLATION_IDENTITY_CHANGED')
        self.c=document(self.root/'deploy/release.json')
        expected={'site_id':self.h['siteId'],'instance_id':self.h['instanceId'],'project':'eatwhat-site-'+self.h['serverId'][:8],'active_jar':str(self.root/'releases/current.jar'),'env_file':str(self.root/'deploy/.env'),'compose_file':str(self.root/'deploy/compose.json'),'state_directory':str(self.root/'private/deployments'),'node_token_file':str(self.root/'config/control.token'),'restore_evidence_id':self.h['restoreEvidenceId']}
        for key,value in expected.items():require(self.c.get(key)==value,'RELEASE_CONFIG_BINDING_CHANGED')
        port=receipt.get('httpPort');require(type(port) is int and 1024<=port<=65535 and self.c.get('origin')=='http://127.0.0.1:'+str(port),'RELEASE_UNSAFE_APPLICATION_PORT')
        require(self.c.get('docker') in ['/usr/bin/docker','/usr/local/bin/docker'],'RELEASE_UNSAFE_COMMAND')
        for key,default in [('command_timeout_seconds',180),('health_timeout_seconds',180)]:require(type(self.c.get(key,default)) is int and 5<=self.c.get(key,default)<=600,'RELEASE_UNBOUNDED_COMMAND')
        raw=private(self.root/'config/application.properties',65536,{ROOT_UID,APP_UID});self.props=properties(raw)
        for key,value in {'app.site.id':self.h['siteId'],'app.site.instance-id':self.h['instanceId'],'app.site.cluster-id':self.h['clusterId'],'app.cluster.enabled':'true','app.control.node-enabled':'true'}.items():require(self.props.get(key)==value,'RELEASE_NODE_CONFIG_CHANGED')
        token=private(self.root/'config/control.token',256,{ROOT_UID,APP_UID}).decode().strip();require(re.fullmatch(r'[A-Za-z0-9_-]{43,128}',token),'RELEASE_NODE_TOKEN_INVALID')
        env=private(self.root/'deploy/.env');require(env==('APP_JAR='+expected['active_jar']+'\n').encode(),'RELEASE_ENVIRONMENT_CHANGED')
        compose=document(self.root/'deploy/compose.json');validate_compose(self.root,compose,port)
        files={'properties':raw,'token':token.encode(),'env':env,'compose':private(self.root/'deploy/compose.json'),'release':private(self.root/'deploy/release.json')}
        manifest=json.dumps({name:hashlib.sha256(data).hexdigest() for name,data in files.items()},sort_keys=True,separators=(',',':')).encode()
        self.configuration_proof=hmac.new(token.encode(),manifest,hashlib.sha256).hexdigest()
        helper=types.ModuleType('eatwhat_reviewed_release');exec(compile(base64.b64decode(RELEASE_SOURCE),'<reviewed-release>','exec'),helper.__dict__)
        # Bypass the legacy constructor's mkdir. Read-only prepare/status must not
        # manufacture an execution directory or an apparently successful receipt.
        self.agent=helper.Agent.__new__(helper.Agent);self.agent.c=self.c;self.agent.state=self.root/'private';self.agent.jar=self.root/'releases/current.jar';self.agent.env_file=self.root/'deploy/.env';self.agent.compose_file=self.root/'deploy/compose.json';self.agent.token=token
        self.agent.base=[self.c['docker'],'compose','--project-name',self.c['project'],'--env-file',str(self.agent.env_file),'-f',str(self.agent.compose_file)]
        self.agent.environment={'PATH':'/usr/local/bin:/usr/bin:/bin','HOME':'/root','APP_JAR':str(self.agent.jar),'DOCKER_HOST':'unix:///var/run/docker.sock'}
        private(self.root/'installation.lock',4096)

    @contextlib.contextmanager
    def project_lock(self):
        fd=os.open(self.root/'installation.lock',os.O_RDONLY|os.O_NOFOLLOW)
        try:
            try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError:raise ReleaseRejected('RELEASE_PROJECT_BUSY') from None
            yield fd
        finally:os.close(fd)

    def identity(self,sha):
        require(digest(self.agent.jar)==sha and self.agent.runtime_sha()==sha,'RELEASE_RUNTIME_ARTIFACT_MISMATCH')
        self.agent.health(self.h['siteId'],self.h['instanceId'])
        probe=self.agent.request('/internal/cluster/v1/probe',True)
        for key in ['siteId','instanceId','clusterId','databaseFingerprint']:require(str(probe.get(key))==self.h[key],'RELEASE_RUNTIME_IDENTITY_CHANGED')
        membership=probe.get('membership',{});require(probe.get('protocolVersion')==1 and type(membership.get('epoch')) is int and membership.get('epoch')==self.h['epoch'] and membership.get('state')=='ACTIVE' and 'pending_change' in membership and membership['pending_change'] is None and probe.get('dataPlaneReady') is True,'RELEASE_MEMBERSHIP_NOT_CURRENT')
        require(probe.get('mode')==self.props.get('app.site.mode') and str(probe.get('allocationSlot'))==self.props.get('app.site.allocation-slot'),'RELEASE_RUNTIME_IDENTITY_CHANGED')
        return probe

    def prepare(self,fetch=request_json):
        with self.project_lock():
            self.identity(self.h['fromSha256']);verify_authority(self.h,fetch)
            expected=self.h.get('configurationProof');require(expected is None or hmac.compare_digest(expected,self.configuration_proof),'RELEASE_CONFIGURATION_CHANGED')
            return {'configurationProof':self.configuration_proof,'runtimeSha256':self.h['fromSha256'],'readinessVerified':True,'epoch':self.h['epoch']}

    def recovery_database(self):
        # App HTTP may be down. Query only fixed metadata in the already proven
        # PostgreSQL container; no caller SQL, database restore or identity rebind.
        env=document(self.root/'deploy/compose.json')['services']['postgres']['environment']
        for key in ['POSTGRES_USER','PG_DB']:require(isinstance(env.get(key),str) and re.fullmatch(r'[a-z_][a-z0-9_]{0,62}',env[key]),'RELEASE_DATABASE_CONFIG_INVALID')
        sql="""SELECT json_build_object(
          'systemIdentifier',(SELECT system_identifier::text FROM pg_control_system()),
          'databaseOid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),
          'databaseName',current_database(),'node',row_to_json(n),
          'allocation',row_to_json(a),'local',row_to_json(l))
          FROM eatwhat_identity.node_state n
          JOIN eatwhat_identity.allocation a ON a.singleton
          JOIN eatwhat_cluster.local_state l ON l.singleton WHERE n.singleton"""
        raw=self.agent.command(['exec','-T','postgres','psql','--no-psqlrc','--set','ON_ERROR_STOP=1','--username='+env['POSTGRES_USER'],'--dbname='+env['PG_DB'],'--tuples-only','--no-align','--command',sql])
        require(len(raw)<=16384,'RELEASE_DATABASE_PROOF_INVALID')
        try:proof=json.loads(raw);node=proof['node'];allocation=proof['allocation'];local=proof['local']
        except (ValueError,TypeError,KeyError):raise ReleaseRejected('RELEASE_DATABASE_PROOF_INVALID') from None
        for key in ['systemIdentifier','databaseOid']:require(isinstance(proof.get(key),str) and re.fullmatch('[0-9]{1,20}',proof[key]),'RELEASE_DATABASE_PROOF_INVALID')
        for value in [node,allocation,local]:
            require(isinstance(value,dict) and value.get('site_id')==self.h['siteId'] and value.get('instance_id')==self.h['instanceId'],'RELEASE_DATABASE_IDENTITY_CHANGED')
        require(proof.get('systemIdentifier')==node.get('cluster_identifier') and proof.get('databaseOid')==str(node.get('database_oid')) and proof.get('databaseName')==node.get('database_name')==env['PG_DB'],'RELEASE_DATABASE_IDENTITY_CHANGED')
        fingerprint=hashlib.md5((proof['systemIdentifier']+':'+proof['databaseOid']).encode()).hexdigest()
        require(fingerprint==self.h['databaseFingerprint'] and node.get('mode')==self.props.get('app.site.mode'),'RELEASE_DATABASE_IDENTITY_CHANGED')
        require(allocation.get('cluster_id')==self.h['clusterId'] and str(allocation.get('slot'))==self.props.get('app.site.allocation-slot'),'RELEASE_DATABASE_IDENTITY_CHANGED')
        require(local.get('cluster_id')==self.h['clusterId'] and type(local.get('epoch')) is int and local['epoch']==self.h['epoch'] and local.get('state')=='ACTIVE' and 'pending_change' in local and local['pending_change'] is None,'RELEASE_MEMBERSHIP_NOT_CURRENT')
        return {'siteId':self.h['siteId'],'instanceId':self.h['instanceId'],'clusterId':self.h['clusterId'],'databaseFingerprint':fingerprint,'epoch':local['epoch'],'mode':node['mode'],'allocationSlot':allocation['slot'],'systemIdentifier':proof['systemIdentifier'],'databaseOid':proof['databaseOid']}

"""Retained-install recovery agent. Only fixed paths, frozen existing files and known services.
The Java control embeds its reviewed bootstrap source in BOOTSTRAP_SOURCE before sending this script.
"""
import base64, copy, fcntl, hashlib, json, os, re, shutil, stat, sys, types, urllib.parse, uuid
from pathlib import Path

BOOTSTRAP_SOURCE='__EATWHAT_BOOTSTRAP_BASE64__'
_BOOTSTRAP=None
ROOT_UID=0
APP_UID=10001
APP_GID=10001
PROTOCOL='eatwhat-install-recovery-v1'
class RecoveryRejected(Exception):
    def __init__(self,code):self.code=code

def require(value,code='RECOVERY_STATE_UNAVAILABLE'):
    if not value:raise RecoveryRejected(code)

def bootstrap():
    global _BOOTSTRAP
    if _BOOTSTRAP is None:
        module=types.ModuleType('eatwhat_reviewed_bootstrap');exec(compile(base64.b64decode(BOOTSTRAP_SOURCE),'<reviewed-bootstrap>','exec'),module.__dict__);_BOOTSTRAP=module
    return _BOOTSTRAP

def canonical_uuid(value):
    try:require(str(uuid.UUID(value))==value,'RECOVERY_INVALID_ID');return value
    except Exception:raise RecoveryRejected('RECOVERY_INVALID_ID')

def checked_identity(data):
    try:return bootstrap().installation_identity(data)
    except Exception:raise RecoveryRejected('RECOVERY_NODE_IDENTITY_MISMATCH')

def read_file(path,limit,modes,owners=None):
    try:
        fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(fd,'rb') as stream:
            info=os.fstat(stream.fileno());require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) in modes and info.st_size<=limit,'RECOVERY_PRIVATE_FILE_REQUIRED')
            if owners is not None:require(info.st_uid in owners,'RECOVERY_FILE_OWNER_MISMATCH')
            return stream.read(limit+1)
    except RecoveryRejected:raise
    except Exception:raise RecoveryRejected('RECOVERY_FILES_INCOMPLETE')

def read_json(path):
    try:
        document=json.loads(read_file(path,262144,{0o600},{ROOT_UID}));require(isinstance(document,dict),'RECOVERY_INVALID_RECEIPT');return document
    except RecoveryRejected:raise
    except Exception:raise RecoveryRejected('RECOVERY_INVALID_RECEIPT')

def digest(path,limit=536870912):
    try:
        fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(fd,'rb') as stream:
            info=os.fstat(stream.fileno());require(stat.S_ISREG(info.st_mode) and info.st_size<=limit,'RECOVERY_FILE_TOO_LARGE');result=hashlib.sha256()
            for chunk in iter(lambda:stream.read(65536),b''):result.update(chunk)
            return result.hexdigest()
    except RecoveryRejected:raise
    except Exception:raise RecoveryRejected('RECOVERY_FILES_INCOMPLETE')

def properties(raw):
    def unescape(value):
        return re.sub(r'\\u([0-9a-fA-F]{4})|\\(.)',lambda m:chr(int(m[1],16)) if m[1] else {'t':'\t','n':'\n','r':'\r','f':'\f'}.get(m[2],m[2]),value)
    result={}
    for line in raw.decode('ISO-8859-1').splitlines():
        if not line.strip() or line.lstrip().startswith(('#','!')):continue
        match=re.fullmatch(r'\s*([^=:\s]+)\s*[=:]\s*(.*)',line)
        require(match is not None,'RECOVERY_PROPERTIES_UNSUPPORTED');result[unescape(match[1])]=unescape(match[2])
    return result

def assert_idle(path):
    require(not path.is_symlink(),'RECOVERY_UNSAFE_LOCK')
    if not path.exists():return
    read_file(path,4096,{0o600},{ROOT_UID})
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RecoveryRejected('RECOVERY_REMOTE_TASK_RUNNING')
    finally:os.close(fd)

def observed_source(data,receipt):
    b=bootstrap();state=receipt.get('state')
    if state in ['INSTALLING','RUNNING']:
        proof=b.lock_state({'serverId':data['serverId'],'jobId':data['sourceJobId']})
        require(proof=='RELEASED','RECOVERY_SOURCE_OUTCOME_UNRESOLVED');receipt=copy.deepcopy(receipt);receipt.update(state='FAILED',code='RECOVERY_SOURCE_INTERRUPTED',lockProof=proof)
    require(receipt.get('state')=='FAILED','RECOVERY_SOURCE_NOT_FAILED')
    return receipt

def validate_compose(root,document):
    require(isinstance(document,dict) and isinstance(document.get('services'),dict),'RECOVERY_COMPOSE_UNSUPPORTED')
    services=document['services'];require(set(services) in [{'app','postgres','redis'},{'app','postgres','redis','restore-check'}],'RECOVERY_COMPOSE_UNSUPPORTED')
    require(all(isinstance(service,dict) for service in services.values()),'RECOVERY_COMPOSE_UNSUPPORTED')
    app=services['app'];pg=services['postgres'];redis=services['redis']
    require(app.get('user')==f'{APP_UID}:{APP_GID}' and app.get('read_only') is True and app.get('entrypoint')==['java'],'RECOVERY_COMPOSE_UNSUPPORTED')
    for service in services.values():
        require(not any(service.get(key) for key in ['privileged','network_mode','pid','ipc','devices','cap_add','volumes_from','build','configs','secrets','post_start','pre_stop']),'RECOVERY_UNSAFE_COMPOSE')
    require(app.get('command')==['-Xms64m','-Xmx384m','-jar','/app/app.jar','--spring.config.location=file:/config/application.properties','--spring.config.additional-location=','--spring.config.import='],'RECOVERY_COMPOSE_UNSUPPORTED')
    require(not pg.get('command') and not pg.get('entrypoint') and not redis.get('entrypoint'),'RECOVERY_COMPOSE_UNSUPPORTED')
    require(app.get('volumes')==['${APP_JAR}:/app/app.jar:ro',str(root/'config')+':/config:ro',str(root/'app-state')+':/state'],'RECOVERY_UNSAFE_MOUNTS')
    require(pg.get('volumes')==['pg_data:/var/lib/postgresql/data',str(root/'deploy/initdb.sh')+':/docker-entrypoint-initdb.d/20-create-application-db.sh:ro'] and redis.get('volumes')==['redis_data:/data'],'RECOVERY_UNSAFE_MOUNTS')
    require(not pg.get('ports') and not redis.get('ports'),'RECOVERY_UNEXPECTED_DATABASE_EXPOSURE')
    ports=app.get('ports');require(isinstance(ports,list) and len(ports)==1 and isinstance(ports[0],str),'RECOVERY_COMPOSE_UNSUPPORTED')
    match=re.fullmatch(r'127\.0\.0\.1:([0-9]{4,5}):8080',ports[0]);require(match is not None,'RECOVERY_UNSAFE_APPLICATION_PORT');port=int(match[1]);require(1024<=port<=65535 and port not in [18080,18081],'RECOVERY_UNSAFE_APPLICATION_PORT')
    volumes=document.get('volumes');require(isinstance(volumes,dict) and set(volumes)=={'pg_data','redis_data'} and all(isinstance(value,dict) and set(value)<= {'labels'} for value in volumes.values()),'RECOVERY_UNSAFE_VOLUMES')
    networks=document.get('networks',{});require(isinstance(networks,dict) and set(networks)<= {'default'} and all(isinstance(value,dict) and set(value)<= {'labels'} for value in networks.values()),'RECOVERY_UNSAFE_NETWORKS')
    images={name:services[name].get('image') for name in ['app','postgres','redis']};require(all(isinstance(value,str) and re.fullmatch(r'sha256:[0-9a-f]{64}',value) for value in images.values()),'RECOVERY_IMAGE_NOT_PINNED')
    environment=pg.get('environment');require(isinstance(environment,dict),'RECOVERY_DATABASE_CONFIG_UNSUPPORTED')
    fields=['POSTGRES_USER','POSTGRES_PASSWORD','PG_DB','PG_APP_USER','PG_APP_PASSWORD'];require(all(isinstance(environment.get(key),str) and re.fullmatch(r'[A-Za-z0-9_-]{1,128}',environment[key]) for key in fields),'RECOVERY_DATABASE_CONFIG_UNSUPPORTED')
    env={key:environment[key] for key in fields};require(env['PG_DB']=='eatwhat' and env['POSTGRES_USER']!=env['PG_APP_USER'] and all(re.fullmatch('[a-z_][a-z0-9_]{0,62}',env[key]) for key in ['POSTGRES_USER','PG_APP_USER']),'RECOVERY_DATABASE_CONFIG_UNSUPPORTED')
    command=redis.get('command');require(isinstance(command,list) and command.count('--requirepass')==1,'RECOVERY_REDIS_CONFIG_UNSUPPORTED');index=command.index('--requirepass');require(index+1<len(command) and re.fullmatch(r'[A-Za-z0-9_-]{1,128}',command[index+1]),'RECOVERY_REDIS_CONFIG_UNSUPPORTED');env['REDIS_PASSWORD']=command[index+1]
    return port,images,env

def load_installation(data,check_locks=True):
    b=bootstrap();canonical_uuid(data['serverId']);canonical_uuid(data['sourceJobId']);root=b.safe_root(data['serverId'])
    identity_binding=checked_identity(data)
    require(root.is_dir() and not root.is_symlink() and root.stat().st_uid==ROOT_UID and stat.S_IMODE(root.stat().st_mode)==0o700,'RECOVERY_INSTALLATION_NOT_PRESERVED')
    for name in ['config','deploy','releases','private','app-state','restore-state']:
        folder=root/name;require(folder.is_dir() and not folder.is_symlink() and folder.stat().st_uid in {ROOT_UID,APP_UID} and stat.S_IMODE(folder.stat().st_mode)==0o700,'RECOVERY_UNSAFE_PATH')
    for name in ['.project-locks','.install-results']:
        folder=b.BASE/name
        if folder.exists() or folder.is_symlink():require(folder.is_dir() and not folder.is_symlink() and folder.stat().st_uid==ROOT_UID and stat.S_IMODE(folder.stat().st_mode)==0o700,'RECOVERY_UNSAFE_PATH')
    if check_locks:assert_idle(b.BASE/'.project-locks'/('eatwhat-site-'+data['serverId'][:8]+'.lock'));assert_idle(root/'installation.lock')
    current=read_json(root/'installation.json');source_path=b.result_path({'serverId':data['serverId'],'jobId':data['sourceJobId']})
    require(current.get('state')!='SUCCEEDED','RECOVERY_INSTALLATION_ALREADY_SUCCEEDED')
    source=read_json(source_path) if source_path.exists() else current
    assert_idle(source_path.with_suffix('.lock'))
    identity=['serverId','siteId','instanceId','artifactSha256']
    require(source.get('jobId')==data['sourceJobId'] and all(source.get(key)==data.get(key) for key in identity),'RECOVERY_NODE_IDENTITY_MISMATCH')
    require(source.get('protocol') in ['eatwhat-install-v1',PROTOCOL],'RECOVERY_PROTOCOL_UNSUPPORTED')
    if source.get('state') in ['INSTALLING','RUNNING'] and current.get('jobId')==source.get('jobId') and current.get('state') in ['FAILED','SUCCEEDED'] and all(current.get(key)==source.get(key) for key in identity):source=current
    source=observed_source(data,source)
    require(all(source.get(key,'CAMPUS' if key=='mode' else None)==value for key,value in identity_binding.items()),'RECOVERY_NODE_IDENTITY_MISMATCH')
    original=source['jobId'] if source['protocol']=='eatwhat-install-v1' else canonical_uuid(source['originalInstallJobId'])
    ancestors={source['jobId'],source.get('sourceJobId'),original}
    require(current.get('jobId') in ancestors and all(current.get(key)==data.get(key) for key in identity),'RECOVERY_NODE_IDENTITY_MISMATCH')
    prop_raw=read_file(root/'config/application.properties',65536,{0o600},{ROOT_UID,APP_UID});props=properties(prop_raw)
    require(props.get('app.site.id')==data['siteId'] and props.get('app.site.instance-id')==data['instanceId'] and props.get('app.site.mode')==data.get('mode','CAMPUS'),'RECOVERY_NODE_IDENTITY_MISMATCH')
    require(props.get('app.cluster.enabled')=='false','RECOVERY_JOINED_SITE_FORBIDDEN')
    require(props.get('app.site.cluster-id')==data.get('clusterId') and props.get('app.site.allocation-slot')==(str(data['allocationSlot']) if 'allocationSlot' in data else None),'RECOVERY_NODE_IDENTITY_MISMATCH')
    if 'clusterId' in data:require(props.get('app.control.enrollment-required')=='true','RECOVERY_ENROLLMENT_GATE_REQUIRED')
    token=read_file(root/'config/control.token',256,{0o600},{ROOT_UID,APP_UID}).decode().strip();require(re.fullmatch('[A-Za-z0-9_-]{43,128}',token),'RECOVERY_PRIVATE_FILE_REQUIRED')
    # Starting the application can resume durable control work. Recovery must not
    # unexpectedly redispatch an unrelated accepted deployment or enrollment.
    for folder in [root/'app-state',root/'app-state'/'control']:
        if not folder.exists():continue
        require(not folder.is_symlink() and folder.is_dir(),'RECOVERY_UNSAFE_PATH')
        records=list(folder.glob('*.json'));require(len(records)<=10000,'RECOVERY_STATE_SCAN_LIMIT')
        for path in records:
            if re.fullmatch(r'(deployment|server-job|server-cluster-job|cluster-enrollment)-[0-9a-f-]{36}\.json',path.name):
                record=json.loads(read_file(path,1048576,{0o600},{ROOT_UID,APP_UID}));require(isinstance(record,dict),'RECOVERY_INVALID_RECEIPT');require(record.get('state') not in ['ACCEPTED','QUEUED','RUNNING','APPLYING','OUTCOME_UNKNOWN'],'RECOVERY_PENDING_CONTROL_TASK')
    document=read_json(root/'deploy/compose.json');port,images,env=validate_compose(root,document)
    require(props.get('spring.datasource.url')=='jdbc:postgresql://postgres:5432/eatwhat' and props.get('spring.flyway.url')==props['spring.datasource.url'] and props.get('spring.datasource.username')==env['PG_APP_USER'] and props.get('spring.datasource.password')==env['PG_APP_PASSWORD'] and props.get('spring.flyway.user')==env['POSTGRES_USER'] and props.get('spring.flyway.password')==env['POSTGRES_PASSWORD'] and props.get('spring.data.redis.password')==env['REDIS_PASSWORD'],'RECOVERY_DATABASE_CONFIG_MISMATCH')
    app_url=props.get('app.base-url','');parsed=urllib.parse.urlsplit(app_url);require(parsed.scheme in ['http','https'] and parsed.hostname and parsed.username is None and not parsed.query and not parsed.fragment and len(app_url)<=512,'RECOVERY_APPLICATION_URL_INVALID')
    env_raw=read_file(root/'deploy/.env',4096,{0o600},{ROOT_UID});require(env_raw.decode().strip()=='APP_JAR='+str(root/'releases/current.jar'),'RECOVERY_UNSAFE_ARTIFACT_PATH')
    read_file(root/'deploy/initdb.sh',65536,{0o600,0o644},{ROOT_UID});require(digest(root/'deploy/initdb.sh',65536)==data.get('initdbSha256'),'RECOVERY_BOOTSTRAP_SCRIPT_UNREVIEWED')
    require(digest(root/'releases/current.jar')==data['artifactSha256'],'RECOVERY_ARTIFACT_CHANGED')
    files=['installation.json','config/application.properties','config/control.token','deploy/compose.json','deploy/.env','deploy/initdb.sh','releases/current.jar']
    checks={name:{'sha256':digest(root/name),'mode':stat.S_IMODE((root/name).stat().st_mode),'uid':(root/name).stat().st_uid,'gid':(root/name).stat().st_gid} for name in files}
    if source_path.exists():checks['source-receipt']={'sha256':digest(source_path),'mode':stat.S_IMODE(source_path.stat().st_mode)}
    evidence=source.get('restoreEvidenceId');valid_evidence=None
    require(source.get('restoreCleanupVerified') is not False,'RECOVERY_RESTORE_CLEANUP_REQUIRED')
    if isinstance(evidence,str) and evidence.startswith('fresh-install-restore-'):
        evidence_job=canonical_uuid(evidence.removeprefix('fresh-install-restore-'));proof_path=b.result_path({'serverId':data['serverId'],'jobId':evidence_job});proof=read_json(proof_path)
        require(proof.get('jobId')==evidence_job and proof.get('restoreEvidenceId')==evidence and all(proof.get(key)==data.get(key) for key in identity),'RECOVERY_RESTORE_EVIDENCE_INVALID')
        require(proof.get('state') in ['FAILED','SUCCEEDED'] and (proof.get('restoreCleanupVerified') is True or proof.get('protocol')=='eatwhat-install-v1' and proof.get('stage') in ['ADMIN_INITIALIZATION','COMPLETE'] and proof.get('restoreCleanupVerified') is not False),'RECOVERY_RESTORE_CLEANUP_REQUIRED')
        require(proof.get('jobId')==original if proof.get('protocol')=='eatwhat-install-v1' else proof.get('protocol')==PROTOCOL and proof.get('originalInstallJobId')==original,'RECOVERY_RESTORE_EVIDENCE_INVALID')
        checks['restore-proof-receipt']={'sha256':digest(proof_path)}
        path=root/'private'/('first-backup.dump' if evidence_job==original else 'recovery-restore-'+evidence_job+'.dump')
        try:
            fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
            with os.fdopen(fd,'rb') as stream:
                info=os.fstat(stream.fileno());require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode)==0o600 and info.st_uid==ROOT_UID and 0<info.st_size<=536870912,'RECOVERY_RESTORE_EVIDENCE_INVALID')
            checks['restore-backup']={'sha256':digest(path),'size':info.st_size};valid_evidence=evidence
        except RecoveryRejected:raise
        except Exception:raise RecoveryRejected('RECOVERY_RESTORE_EVIDENCE_INVALID')
    freeze=hashlib.sha256(json.dumps(checks,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return {'root':root,'source':source,'sourcePath':source_path,'originalInstallJobId':original,'freezeHash':freeze,'properties':prop_raw.decode('ISO-8859-1'),'nodeToken':token,'compose':document,'database':env,'images':images,'httpPort':port,'appBaseUrl':app_url,'restoreEvidenceId':valid_evidence}

def inspect(data):
    try:
        loaded=load_installation(data);preflight(loaded,data);source=loaded['source']
        return {'protocol':PROTOCOL,'serverId':data['serverId'],'sourceJobId':data['sourceJobId'],'eligible':True,'blockers':[],**checked_identity(data),'siteId':source['siteId'],'instanceId':source['instanceId'],'artifactSha256':source['artifactSha256'],'freezeHash':loaded['freezeHash'],'root':str(loaded['root']),'httpPort':loaded['httpPort'],'appBaseUrl':loaded['appBaseUrl'],'originalInstallJobId':loaded['originalInstallJobId'],'sourceStage':source.get('stage'),'dataPreserved':True,'passwordsPreserved':True,'restoreEvidenceId':loaded['restoreEvidenceId'],'administratorAction':'AUTHORITY_ACCOUNTS_AFTER_JOIN' if data.get('mode')=='EXTERNAL' else 'PRESERVE_EXISTING_OR_INITIALIZE_ONLY_IF_EMPTY'}
    except RecoveryRejected as e:return {'protocol':PROTOCOL,'serverId':data.get('serverId'),'sourceJobId':data.get('sourceJobId'),'eligible':False,'blockers':[e.code]}

def verify_resources(docker,loaded,data):
    """Legacy resources require their original container/config provenance.
    New resources additionally carry the full UUID, avoiding prefix adoption.
    """
    b=bootstrap();project='eatwhat-site-'+data['serverId'][:8];root=loaded['root'];owned=set()
    identifiers=b.run([docker,'ps','-aq','--filter','label=com.docker.compose.project='+project],timeout=10).decode().splitlines();require(len(identifiers)<=4,'RECOVERY_FOREIGN_DOCKER_RESOURCES')
    for identifier in identifiers:
        require(re.fullmatch('[0-9a-f]{12,64}',identifier),'RECOVERY_FOREIGN_DOCKER_RESOURCES')
        row=json.loads(b.run([docker,'inspect',identifier],timeout=10))[0];labels=row.get('Config',{}).get('Labels') or {};service=labels.get('com.docker.compose.service')
        require(service in ['app','postgres','redis','restore-check'] and labels.get('com.docker.compose.project.config_files')==str(root/'deploy/compose.json'),'RECOVERY_FOREIGN_DOCKER_RESOURCES')
        require(service!='restore-check','RECOVERY_RESTORE_CLEANUP_REQUIRED');require(service not in owned,'RECOVERY_FOREIGN_DOCKER_RESOURCES')
        mounts=row.get('Mounts') or []
        if service in ['postgres','redis']:
            target='/var/lib/postgresql/data' if service=='postgres' else '/data';expected=project+('_pg_data' if service=='postgres' else '_redis_data');data_mount=[mount for mount in mounts if mount.get('Destination')==target]
            require(len(data_mount)==1 and data_mount[0].get('Type')=='volume' and data_mount[0].get('Name')==expected,'RECOVERY_FOREIGN_DOCKER_RESOURCES')
        require(labels.get('you.v50to.eatwhat.server-id',data['serverId'])==data['serverId'],'RECOVERY_FOREIGN_DOCKER_RESOURCES');owned.add(service)
    for name,service in [('pg_data','postgres'),('redis_data','redis')]:
        names=b.run([docker,'volume','ls','--filter','name='+project+'_'+name,'--format','{{.Name}}'],timeout=10).decode().splitlines()
        if project+'_'+name not in names:continue
        row=json.loads(b.run([docker,'volume','inspect',project+'_'+name],timeout=10))[0];labels=row.get('Labels') or {}
        require(labels.get('com.docker.compose.project')==project and labels.get('com.docker.compose.volume')==name,'RECOVERY_FOREIGN_DOCKER_RESOURCES')
        require(labels.get('you.v50to.eatwhat.server-id')==data['serverId'] or service in owned and 'you.v50to.eatwhat.server-id' not in labels,'RECOVERY_FOREIGN_DOCKER_RESOURCES')
    names=b.run([docker,'network','ls','--filter','name='+project+'_default','--format','{{.Name}}'],timeout=10).decode().splitlines()
    if project+'_default' in names:
        row=json.loads(b.run([docker,'network','inspect',project+'_default'],timeout=10))[0];labels=row.get('Labels') or {}
        require(labels.get('com.docker.compose.project')==project and labels.get('com.docker.compose.network')=='default' and (labels.get('you.v50to.eatwhat.server-id')==data['serverId'] or owned and 'you.v50to.eatwhat.server-id' not in labels),'RECOVERY_FOREIGN_DOCKER_RESOURCES')

def preflight(loaded,data):
    try:
        b=bootstrap();docker=shutil.which('docker');require(bool(docker),'RECOVERY_DOCKER_REQUIRED');require('restore-check' not in loaded['compose']['services'],'RECOVERY_RESTORE_CLEANUP_REQUIRED');verify_resources(docker,loaded,data)
        for image in loaded['images'].values():
            # Read-only throughout: unlike first installation, recovery must
            # never fall back to pulling a missing image, even after a race.
            raw=b.run([docker,'image','inspect',image],timeout=10);require(len(raw)<=1048576,'RECOVERY_IMAGE_CHANGED');details=json.loads(raw)[0]
            architecture={'x86_64':'amd64','aarch64':'arm64','arm64':'arm64'}.get(b.platform.machine(),b.platform.machine());require(details.get('Id')==image and details.get('Architecture')==architecture,'RECOVERY_IMAGE_CHANGED')
        require(shutil.disk_usage(loaded['root']).free>=1073741824,'RECOVERY_INSUFFICIENT_DISK_SPACE');return docker
    except RecoveryRejected:raise
    except Exception:raise RecoveryRejected('RECOVERY_DOCKER_INSPECTION_FAILED')

def sql(base,env,database,statement):
    b=bootstrap();return b.run(base+['exec','-T','postgres','psql','-X','-U',env['POSTGRES_USER'],'-d',database,'-v','ON_ERROR_STOP=1','-tAc',statement],timeout=30).decode().strip()

def prepare_database(base,loaded,data,backup):
    b=bootstrap();env=loaded['database'];db=env['PG_DB'];before=sql(base,env,'postgres','SELECT system_identifier::text FROM pg_control_system()');require(re.fullmatch('[0-9]+',before),'RECOVERY_DATABASE_IDENTITY_UNAVAILABLE')
    existing=sql(base,env,'postgres',"SELECT oid::text||':'||pg_get_userbyid(datdba) FROM pg_database WHERE datname='eatwhat'")
    if existing:require(existing.split(':',1)[1]==env['POSTGRES_USER'],'RECOVERY_DATABASE_OWNER_MISMATCH')
    if existing and sql(base,env,db,"SELECT to_regclass('eatwhat_identity.node_state') IS NOT NULL")=='t':
        row=json.loads(sql(base,env,db,"SELECT row_to_json(n) FROM eatwhat_identity.node_state n WHERE singleton"));require(row.get('site_id')==data['siteId'] and row.get('instance_id')==data['instanceId'] and row.get('mode')==data.get('mode','CAMPUS') and row.get('database_name')==db and str(row.get('database_oid'))==existing.split(':',1)[0] and str(row.get('cluster_identifier'))==before,'RECOVERY_DATABASE_IDENTITY_MISMATCH')
        if sql(base,env,db,"SELECT to_regclass('eatwhat_cluster.local_state') IS NOT NULL")=='t':require(sql(base,env,db,'SELECT count(*) FROM eatwhat_cluster.local_state')=='0','RECOVERY_JOINED_SITE_FORBIDDEN')
        if 'clusterId' in data:
            allocation=json.loads(sql(base,env,db,'SELECT row_to_json(a) FROM eatwhat_identity.allocation a WHERE singleton'))
            require(all(allocation.get(k)==v for k,v in {'cluster_id':data['clusterId'],'site_id':data['siteId'],'instance_id':data['instanceId'],'slot':data['allocationSlot']}.items()),'RECOVERY_NODE_IDENTITY_MISMATCH')
    role=sql(base,env,'postgres',"SELECT (rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls)::text FROM pg_roles WHERE rolname='"+env['PG_APP_USER']+"'")
    require(role in ['', 'true'],'RECOVERY_APPLICATION_ROLE_UNSAFE')
    require(sql(base,env,'postgres',"SELECT count(*) FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname='"+env['PG_APP_USER']+"'")=='0','RECOVERY_APPLICATION_ROLE_UNSAFE')
    for filename,args in [('globals.sql',['pg_dumpall','--globals-only','-U',env['POSTGRES_USER']]),('database.dump',['pg_dump','-Fc','-U',env['POSTGRES_USER'],'-d',db] if existing else ['pg_dump','-Fc','-U',env['POSTGRES_USER'],'-d','postgres'])]:
        with open(backup/filename,'xb') as stream:os.fchmod(stream.fileno(),0o600);b.run(base+['exec','-T','postgres']+args,output=stream,timeout=180);stream.flush();os.fsync(stream.fileno())
    b.atomic(backup/'database-identity.json',{'systemIdentifier':before,'applicationDatabase':existing or None})
    # Fixed SQL only, secrets arrive via the existing container environment. An
    # existing role's password is never reset. Validate it over TCP afterwards.
    script=r'''set -eu
psql -X --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 <<'SQL'
\getenv app_user PG_APP_USER
\getenv app_password PG_APP_PASSWORD
\getenv app_db PG_DB
\getenv migration_user POSTGRES_USER
SET log_statement='none';
SET log_min_error_statement='panic';
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', :'app_user', :'app_password') WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=:'app_user') \gexec
SELECT format('CREATE DATABASE %I OWNER %I TEMPLATE template0', :'app_db', :'migration_user') WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname=:'app_db') \gexec
SQL
PGPASSWORD="$PG_APP_PASSWORD" psql -X -h 127.0.0.1 -U "$PG_APP_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -tAc 'SELECT 1' >/dev/null
psql -X --username "$POSTGRES_USER" --dbname "$PG_DB" --set=ON_ERROR_STOP=1 <<'SQL'
\getenv app_user PG_APP_USER
\getenv app_db PG_DB
REVOKE ALL ON DATABASE :"app_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"app_db" TO :"app_user";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";
SQL
'''
    import tempfile
    with tempfile.TemporaryFile() as stream:stream.write(script.encode());stream.seek(0);b.run(base+['exec','-T','postgres','bash','-s'],stdin=stream,timeout=90)
    require(sql(base,env,'postgres','SELECT system_identifier::text FROM pg_control_system()')==before,'RECOVERY_DATABASE_IDENTITY_CHANGED')
    if existing:require(sql(base,env,'postgres',"SELECT oid::text||':'||pg_get_userbyid(datdba) FROM pg_database WHERE datname='eatwhat'")==existing,'RECOVERY_DATABASE_IDENTITY_CHANGED')

def recovery_status(data):
    b=bootstrap();canonical_uuid(data['serverId']);canonical_uuid(data['jobId']);path=b.result_path(data)
    if not path.exists():return {'protocol':PROTOCOL,'serverId':data['serverId'],'jobId':data['jobId'],'state':'NOT_STARTED','lockProof':b.lock_state(data)}
    receipt=read_json(path);require(receipt.get('protocol')==PROTOCOL and receipt.get('serverId')==data['serverId'] and receipt.get('jobId')==data['jobId'],'RECOVERY_NODE_IDENTITY_MISMATCH')
    root_receipt=b.safe_root(data['serverId'])/'installation.json'
    if receipt.get('state')=='RUNNING' and root_receipt.exists():
        terminal=read_json(root_receipt)
        if terminal.get('state') in ['SUCCEEDED','FAILED'] and all(terminal.get(key)==receipt.get(key) for key in ['protocol','serverId','jobId','siteId','instanceId','artifactSha256']):return terminal
    if receipt.get('state')=='RUNNING' and b.lock_state(data)=='RELEASED':receipt=dict(receipt,state='FAILED',code='RECOVERY_INTERRUPTED_PRESERVED',lockProof='RELEASED')
    return receipt

def recover(data):
    b=bootstrap();canonical_uuid(data['jobId']);require(data['jobId']!=data['sourceJobId'],'RECOVERY_INVALID_ID');completed=b.result_path(data,True);job_lock=b.acquire_lock(completed.with_suffix('.lock'));locks=[]
    try:
        if completed.exists():return recovery_status(data)
        loaded=load_installation(data);root=loaded['root'];directory=b.BASE/'.project-locks';directory.mkdir(mode=0o700,exist_ok=True)
        locks.append(b.acquire_lock(directory/('eatwhat-site-'+data['serverId'][:8]+'.lock')));locks.append(b.acquire_lock(root/'installation.lock'))
        loaded=load_installation(data,check_locks=False);require(data.get('freezeHash')==loaded['freezeHash'],'RECOVERY_PLAN_CHANGED')
        docker=preflight(loaded,data)
        receipt={key:data[key] for key in ['serverId','jobId','sourceJobId','siteId','instanceId','artifactSha256']};receipt.update(protocol=PROTOCOL,originalInstallJobId=loaded['originalInstallJobId'],root=str(root),state='RUNNING',stage='RECOVERY_BACKUP',httpPort=loaded['httpPort'],clusterMembership='NOT_JOINED',dataPreserved=True,passwordsPreserved=True)
        receipt.update(checked_identity(data))
        b.atomic(completed,receipt)
        try:
            backup=root/'private'/('recovery-backup-'+data['jobId']);backup.mkdir(mode=0o700)
            for name in ['installation.json','config/application.properties','config/control.token','deploy/compose.json','deploy/.env']:
                b.atomic(backup/name.replace('/','-'),read_file(root/name,262144,{0o600},{ROOT_UID,APP_UID}))
            b.atomic(root/'installation.json',receipt)
            base=[docker,'compose','--project-name','eatwhat-site-'+data['serverId'][:8],'--env-file',str(root/'deploy/.env'),'-f',str(root/'deploy/compose.json')]
            b.run(base+['stop','app']);os.chmod(root/'deploy/initdb.sh',0o644)
            # Start an existing PG volume even when its skipped initdb left the
            # application DB absent; --wait cannot be used until it is repaired.
            receipt['stage']='RECOVERY_DATABASE';b.atomic(completed,receipt);b.run(base+['up','-d','--no-deps','postgres','redis'])
            import time
            deadline=time.monotonic()+120
            while True:
                try:
                    require(sql(base,loaded['database'],'postgres','SELECT 1')=='1','RECOVERY_DATABASE_NOT_READY');break
                except Exception:
                    if time.monotonic()>deadline:raise RecoveryRejected('RECOVERY_DATABASE_NOT_READY')
                    time.sleep(1)
            prepare_database(base,loaded,data,backup);receipt['backupCreated']=True
            admin_count=sql(base,loaded['database'],'eatwhat',"SELECT to_regclass('public.users') IS NOT NULL")
            administrator=data.get('administrator')
            if admin_count=='t':
                users=int(sql(base,loaded['database'],'eatwhat','SELECT count(*) FROM public.users'));admins=int(sql(base,loaded['database'],'eatwhat',"SELECT count(*) FROM public.users WHERE role='admin' AND NOT banned"))
                if users:
                    require(admins>0,'RECOVERY_EXISTING_USERS_WITHOUT_ADMIN');administrator=None;require(loaded['restoreEvidenceId'] is not None,'RECOVERY_RESTORE_REHEARSAL_REVIEW_REQUIRED')
            require(administrator is None or isinstance(administrator,dict),'RECOVERY_ADMINISTRATOR_REQUIRED')
            if loaded['restoreEvidenceId']:receipt['restoreEvidenceId']=loaded['restoreEvidenceId'];receipt['restoreCleanupVerified']=True
            payload=dict(data,database=loaded['database'],properties=loaded['properties'],nodeToken=loaded['nodeToken'],httpPort=loaded['httpPort'],administrator=administrator)
            result=b.complete_application(payload,root,receipt,docker,loaded['images'],loaded['compose'],completed,resume=True);result['code']='RECOVERY_COMPLETE';b.atomic(root/'installation.json',result);b.atomic(completed,result);return result
        except Exception as error:
            receipt.update(state='FAILED',code=error.code if isinstance(error,RecoveryRejected) else 'RECOVERY_FAILED_PRESERVED');b.atomic(root/'installation.json',receipt);b.atomic(completed,receipt);return receipt
    finally:
        for fd in reversed(locks):os.close(fd)
        os.close(job_lock)

# No controller route uses this agent until actual retained-database acceptance passes.
def main():
    os.umask(0o077)
    try:
        b=bootstrap();data=b.input_header();require(len(sys.argv)==2,'RECOVERY_INVALID_REQUEST')
        if sys.argv[1]=='inspect':result=inspect(data)
        elif sys.argv[1]=='recover':result=recover(data)
        elif sys.argv[1]=='status':result=recovery_status(data)
        else:raise RecoveryRejected('RECOVERY_OPERATION_NOT_READY')
        print(json.dumps(result,separators=(',',':')))
    except RecoveryRejected as e:print(json.dumps({'code':e.code}));sys.exit(1)
    except Exception:print('{"code":"RECOVERY_REJECTED_OR_UNKNOWN"}');sys.exit(1)
if __name__=='__main__':main()

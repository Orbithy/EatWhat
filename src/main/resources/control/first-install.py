"""Bundled Linux bootstrap. Fixed paths and services; preserves all pre-existing installations."""
import copy, fcntl, hashlib, json, os, platform, re, shlex, shutil, socket, stat, subprocess, sys, tempfile, time, urllib.request, uuid
from pathlib import Path

BASE = Path('/opt/eatwhat-sites')
ENV = {'PATH':'/usr/local/bin:/usr/bin:/bin', 'LANG':'C.UTF-8', 'HOME':str(Path.home()), 'DOCKER_HOST':'unix:///var/run/docker.sock'}
class Rejected(Exception): pass

def require(value):
    if not value: raise Rejected()

def private(path):
    require(not path.is_symlink() and path.is_file() and stat.S_IMODE(path.stat().st_mode)==0o600)
    return path

def atomic(path, value, mode=0o600):
    data = value if isinstance(value, bytes) else json.dumps(value, separators=(',',':')).encode()
    fd, name = tempfile.mkstemp(prefix='.install-', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'wb') as out: out.write(data); out.flush(); os.fsync(out.fileno())
        os.replace(name, path)
        parent=os.open(path.parent, os.O_RDONLY|os.O_DIRECTORY)
        try: os.fsync(parent)
        finally: os.close(parent)
    finally:
        Path(name).unlink(missing_ok=True)

def run(args, *, stdin=None, output=None, timeout=240):
    result = subprocess.run(args, stdin=stdin or subprocess.DEVNULL, stdout=output or subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout, env=ENV, check=True)
    return result.stdout

def safe_root(server):
    require(str(uuid.UUID(server)) == server)
    require(BASE.is_absolute() and not BASE.is_symlink())
    for parent in [Path('/opt'), BASE]:
        if parent.exists(): require(not parent.is_symlink() and parent.is_dir() and parent.stat().st_uid==0 and stat.S_IMODE(parent.stat().st_mode)&0o022==0)
    root=BASE/server
    require(not root.is_symlink())
    return root

def input_header():
    line=sys.stdin.buffer.readline(262145);require(len(line)<=262144 and line.endswith(b'\n'))
    data=json.loads(line);require(isinstance(data,dict));return data

def can_install_docker():
    try:
        release={k:v.strip('"') for k,v in (line.strip().split('=',1) for line in open('/etc/os-release') if '=' in line)}
        return os.geteuid()==0 and shutil.which('apt-get') is not None and (release.get('ID')=='debian' and int(release.get('VERSION_ID','0').split('.')[0])>=13 or release.get('ID')=='ubuntu' and float(release.get('VERSION_ID','0'))>=24.04)
    except Exception: return False

def installed_packages():
    raw=run(['dpkg-query','-W','-f=${binary:Package}\t${Version}\t${db:Status-Status}\n'],timeout=15)
    require(len(raw)<=2097152);result={}
    for line in raw.decode().splitlines():
        fields=line.split('\t');require(len(fields)==3)
        name,version,state=fields
        if state!='installed':continue
        require(re.fullmatch(r'[a-z0-9][a-z0-9+.-]{0,127}(?::[a-z0-9][a-z0-9-]{0,31})?',name)
            and re.fullmatch(r'[0-9][A-Za-z0-9.+:~_-]{0,255}',version) and name not in result)
        result[name]=version
    require(0<len(result)<=8192);return result

def docker_package_policy(workspace):
    require(workspace.is_dir() and all(not p.is_symlink() for p in [workspace,*workspace.parents])
        and workspace.stat().st_uid==os.geteuid() and stat.S_IMODE(workspace.stat().st_mode)==0o700)
    baseline=installed_packages();fields=shlex.split(run(['apt-config','shell','Preference','Dir::Etc::preferences/f'],timeout=5).decode())
    require(len(fields)==1 and fields[0].startswith('Preference='));original=Path(fields[0].split('=',1)[1]);require(original.is_absolute() and '..' not in original.parts)
    existing=b''
    if original.exists() or original.is_symlink():
        require(all(not p.is_symlink() for p in [original,*original.parents]) and original.is_file() and original.stat().st_uid==os.geteuid()
            and original.stat().st_size<=262144);existing=original.read_bytes()
    pins=''.join('Package: '+name+'\nPin: version '+version+'\nPin-Priority: 1001\n\nPackage: '+name+'\nPin: version *\nPin-Priority: -1\n\n' for name,version in sorted(baseline.items())).encode()
    require(len(pins)+len(existing)<=2097152);path=workspace/'docker-apt-preferences';manifest=workspace/'docker-installed-baseline.json'
    require(not path.exists() and not path.is_symlink() and not manifest.exists() and not manifest.is_symlink())
    atomic(path,pins+existing);atomic(manifest,baseline);return path,baseline

def install_docker(workspace):
    require(can_install_docker())
    preferences,baseline=docker_package_policy(workspace)
    # Only distribution packages from the target's existing apt sources. Never execute downloaded shell scripts.
    run(['apt-get','update'],timeout=300)
    package=None
    for candidate in ['docker-compose-v2','docker-compose']:
        try:
            if run(['apt-cache','show',candidate],timeout=15): package=candidate;break
        except Exception: pass
    require(package is not None)
    run(['apt-get','-o','DPkg::Lock::Timeout=45','install','-y','--no-install-recommends','--no-upgrade','--no-remove',
         '-o','APT::Get::allow-downgrades=false','-o','Dir::Etc::preferences='+str(preferences),'--','docker.io',package],timeout=600)
    after=installed_packages();require(all(after.get(name)==version for name,version in baseline.items()))
    run(['systemctl','enable','--now','docker'],timeout=60)

def project_available(docker, server):
    project='eatwhat-site-'+str(uuid.UUID(server))[:8]
    # Labels catch Compose resources; exact names also catch manually created volumes/networks
    # which Compose could otherwise adopt despite belonging to another installation.
    for resource in ['container','volume','network']:
        args=[docker,'ps','-a'] if resource=='container' else [docker,resource,'ls']
        values=run(args+['--filter','label=com.docker.compose.project='+project,'--format','{{.ID}}' if resource=='container' else '{{.Name}}'],timeout=3)
        require(len(values)<=65536)
        if values.strip():return False
    volumes=run([docker,'volume','ls','--filter','name='+project,'--format','{{.Name}}'],timeout=3).decode().splitlines()
    networks=run([docker,'network','ls','--filter','name='+project,'--format','{{.Name}}'],timeout=3).decode().splitlines()
    return not ({project+'_pg_data',project+'_redis_data'}&set(volumes) or project+'_default' in networks)

def inspect(data):
    root=safe_root(data['serverId']);port=data['httpPort'];require(isinstance(port,int) and 1024<=port<=65535 and port not in [18080,18081])
    require(sys.platform=='linux' and sys.version_info>=(3,10));docker=shutil.which('docker');compose=False;access=False
    if docker:
        try: compose=run([docker,'compose','version','--short'],timeout=8).decode().strip().lstrip('v').startswith('2.');access=bool(run([docker,'info','--format','{{.ServerVersion}}'],timeout=8).strip())
        except Exception: pass
    project_free=None
    if docker and compose and access:
        try:project_free=project_available(docker,data['serverId'])
        except Exception:pass
    free=True
    try:
        with socket.socket() as s: s.bind(('127.0.0.1',port))
    except OSError: free=False
    return {'protocol':'eatwhat-install-v1','serverId':data['serverId'],'root':str(root),'rootAvailable':not root.exists(),'portAvailable':free,'rootUser':os.geteuid()==0,'dockerReady':bool(docker and compose and access),'projectAvailable':project_free,'canInstallDocker':can_install_docker(),'diskFreeMiB':shutil.disk_usage('/opt').free//1048576}

def result_path(data, create=False):
    root=safe_root(data['serverId']);job=str(uuid.UUID(data['jobId']));require(job==data['jobId'])
    directory=BASE/'.install-results'
    if create:
        BASE.mkdir(mode=0o700,exist_ok=True);directory.mkdir(mode=0o700,exist_ok=True)
    if directory.exists(): require(not directory.is_symlink() and directory.stat().st_uid==0 and stat.S_IMODE(directory.stat().st_mode)==0o700)
    return directory/(job+'.json')

def lock_state(data):
    path=result_path(data).with_suffix('.lock')
    if not path.exists():return 'UNSUPPORTED'
    private(path)
    fd=os.open(path,os.O_WRONLY|os.O_NOFOLLOW)
    try:
        try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return 'HELD'
        fcntl.flock(fd,fcntl.LOCK_UN);return 'RELEASED'
    finally:os.close(fd)

def observed_receipt(data,receipt):
    if receipt.get('state')=='INSTALLING' and lock_state(data)=='RELEASED':
        receipt=copy.deepcopy(receipt);receipt.update(state='FAILED',code='FIRST_INSTALL_INTERRUPTED_PRESERVED',retryable=False,lockProof='RELEASED')
        if not safe_root(data['serverId']).exists():receipt.update(code='FIRST_INSTALL_INTERRUPTED_BEFORE_DATABASE',retryable=True)
    return receipt

def status(data):
    completed=result_path(data)
    if completed.exists():
        receipt=json.loads(private(completed).read_text());require(receipt['serverId']==data['serverId'] and receipt['jobId']==data['jobId'])
        # The terminal root receipt is fsynced first. A crash between the two
        # writes must not downgrade a completed installation to interrupted.
        path=safe_root(data['serverId'])/'installation.json'
        if receipt.get('state')=='INSTALLING' and path.exists():
            terminal=json.loads(private(path).read_text())
            identity=['protocol','serverId','jobId','siteId','instanceId','artifactSha256','root']
            if terminal.get('state') in ['SUCCEEDED','FAILED'] and all(terminal.get(key)==receipt.get(key) for key in identity):return terminal
        return observed_receipt(data,receipt)
    root=safe_root(data['serverId']);path=root/'installation.json'
    if not path.exists(): return {'protocol':'eatwhat-install-v1','serverId':data['serverId'],'jobId':data['jobId'],'state':'NOT_STARTED','root':str(root),'lockProof':lock_state(data),'noInstallationDirectory':not root.exists()}
    receipt=json.loads(private(path).read_text());require(receipt['serverId']==data['serverId'] and receipt['jobId']==data['jobId']);return observed_receipt(data,receipt)

def pin_image(docker,image):
    require(isinstance(image,str) and len(image)<=256 and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/:@-]*',image))
    try: run([docker,'image','inspect',image],timeout=10)
    except Exception: run([docker,'pull',image],timeout=600)
    details=json.loads(run([docker,'image','inspect',image],timeout=10))[0]
    architecture={'x86_64':'amd64','aarch64':'arm64','arm64':'arm64'}.get(platform.machine(),platform.machine());require(details.get('Architecture')==architecture)
    identifier=details['Id'];require(re.fullmatch(r'sha256:[0-9a-f]{64}',identifier));return identifier

def installation_identity(data):
    mode=data.get('mode','CAMPUS');require(mode in ['CAMPUS','EXTERNAL']);identity={'mode':mode}
    if 'clusterId' in data:
        cluster=data['clusterId'];require(str(uuid.UUID(cluster))==cluster and uuid.UUID(cluster).int!=0)
        slot=data.get('allocationSlot');require(type(slot) is int and 0<=slot<=1023 and (mode=='CAMPUS' and slot==0 or mode=='EXTERNAL' and slot>0));identity.update(clusterId=cluster,allocationSlot=slot)
    else:require('allocationSlot' not in data and mode=='CAMPUS')
    if mode=='EXTERNAL':require(data.get('administrator') is None)
    return identity

def _install(data):
    root=safe_root(data['serverId']);job=str(uuid.UUID(data['jobId']));require(job==data['jobId']);site=data['siteId'];instance=str(uuid.UUID(data['instanceId']));sha=data['artifactSha256'];require(re.fullmatch(r'server-[0-9a-f]{12}',site) and re.fullmatch('[0-9a-f]{64}',sha));docker=shutil.which('docker')
    identity=installation_identity(data)
    completed=result_path(data,True)
    if completed.exists():
        receipt=json.loads(private(completed).read_text());require(receipt['serverId']==data['serverId'] and receipt['artifactSha256']==sha and receipt['siteId']==site and receipt['instanceId']==instance)
        require(all(receipt.get(k,'CAMPUS' if k=='mode' else None)==v for k,v in identity.items()))
        while sys.stdin.buffer.read(65536): pass
        return receipt
    receipt={'protocol':'eatwhat-install-v1','serverId':data['serverId'],'jobId':job,'state':'INSTALLING','siteId':site,'instanceId':instance,'artifactSha256':sha,'httpPort':data['httpPort'],'root':str(root),'clusterMembership':'NOT_JOINED','stage':'PREFLIGHT'}
    receipt.update(identity)
    atomic(completed,receipt)
    check=inspect(data)
    if not (check['rootAvailable'] and check['portAvailable'] and check['rootUser'] and (check.get('projectAvailable') is True or not check['dockerReady'] and check.get('projectAvailable') is None) and (check['dockerReady'] or data.get('installDocker') is True and check['canInstallDocker']) and check['diskFreeMiB']>=3072):
        receipt.update(state='FAILED',code='FIRST_INSTALL_PRECONDITION_CHANGED',retryable=not root.exists());atomic(completed,receipt)
        while sys.stdin.buffer.read(65536): pass
        return receipt
    root.mkdir(mode=0o700);atomic(root/'installation.json',receipt)
    lock=open(root/'installation.lock','x');os.chmod(root/'installation.lock',0o600);fcntl.flock(lock,fcntl.LOCK_EX)
    data_started=False
    try:
        receipt['stage']='ARTIFACT_VERIFY'
        for folder in ['releases','config','deploy','app-state','restore-state','private']: (root/folder).mkdir(mode=0o700)
        jar=root/'releases/current.jar';digest=hashlib.sha256();size=0
        with open(jar,'xb') as out:
            os.fchmod(out.fileno(),0o600)
            expected=data.get('artifactBytes');require(expected is None or isinstance(expected,int) and 0<expected<=536870912)
            while expected is None or size<expected:
                block=sys.stdin.buffer.read(65536 if expected is None else min(65536,expected-size))
                if not block: break
                size+=len(block);require(size<=536870912);digest.update(block);out.write(block)
            require(expected is None or size==expected)
            out.flush();os.fsync(out.fileno())
        require(size>0 and digest.hexdigest()==sha)
        archive=None;archive_size=data.get('imageArchiveBytes',0);require(isinstance(archive_size,int) and 0<=archive_size<=2147483648)
        if archive_size:
            receipt['stage']='IMAGE_ARCHIVE_VERIFY';atomic(completed,receipt);archive=root/'private/bootstrap-images.tar.gz';received=0;image_hash=hashlib.sha256()
            with open(archive,'xb') as out:
                os.fchmod(out.fileno(),0o600)
                while received<archive_size:
                    block=sys.stdin.buffer.read(min(65536,archive_size-received));require(bool(block));received+=len(block);image_hash.update(block);out.write(block)
                out.flush();os.fsync(out.fileno())
            require(image_hash.hexdigest()==data.get('imageArchiveSha256'))
        require(not sys.stdin.buffer.read(1))
        os.chown(jar,10001,10001)
        if not check['dockerReady']:
            receipt['stage']='SYSTEM_DEPENDENCIES';install_docker(root/'private');require(inspect(dict(data,serverId=data['serverId']))['dockerReady']);docker=shutil.which('docker')
        require(inspect(data).get('projectAvailable') is True)
        for filename in ['application.properties','control.token']:
            content=data['properties'] if filename=='application.properties' else data['nodeToken']
            require(isinstance(content,str) and len(content)<=65536);atomic(root/'config'/filename,content.encode());os.chown(root/'config'/filename,10001,10001)
        os.chown(root/'config',10001,10001);os.chown(root/'app-state',10001,10001);os.chown(root/'restore-state',10001,10001)
        atomic(root/'deploy/remote-release.py',data['releaseAgent'].encode(),0o700)
        # The image drops to its postgres user before sourcing init scripts. This script
        # contains no credentials; the private host parent remains mode 700.
        atomic(root/'deploy/initdb.sh',data['initdb'].encode(),0o644)
        receipt['stage']='IMAGES';atomic(completed,receipt)
        if archive:
            run([docker,'load','--input',str(archive)],timeout=600);archive.unlink()
        images={key:pin_image(docker,value) for key,value in data['images'].items()};require(set(images)=={'app','postgres','redis'})
        project='eatwhat-site-'+data['serverId'][:8];env=data['database'];require(set(env)=={'POSTGRES_USER','POSTGRES_PASSWORD','PG_DB','PG_APP_USER','PG_APP_PASSWORD','REDIS_PASSWORD'})
        for value in env.values(): require(isinstance(value,str) and re.fullmatch('[A-Za-z0-9_-]{1,128}',value))
        atomic(root/'deploy/.env',('APP_JAR='+str(jar)+'\n').encode())
        labels={'you.v50to.eatwhat.server-id':data['serverId'],'you.v50to.eatwhat.install-job-id':data['jobId']}
        common={'restart':'unless-stopped','pull_policy':'never','labels':labels}
        app={**common,'image':images['app'],'user':'10001:10001','read_only':True,'tmpfs':['/tmp'],'volumes':['${APP_JAR}:/app/app.jar:ro',str(root/'config')+':/config:ro',str(root/'app-state')+':/state'],'entrypoint':['java'],'command':['-Xms64m','-Xmx384m','-jar','/app/app.jar','--spring.config.location=file:/config/application.properties','--spring.config.additional-location=','--spring.config.import='],'ports':['127.0.0.1:'+str(data['httpPort'])+':8080'],'depends_on':{'postgres':{'condition':'service_healthy'},'redis':{'condition':'service_healthy'}}}
        compose={'services':{'app':app,'postgres':{**common,'image':images['postgres'],'environment':{k:v for k,v in env.items() if k!='REDIS_PASSWORD'}|{'POSTGRES_DB':'postgres'},'volumes':['pg_data:/var/lib/postgresql/data',str(root/'deploy/initdb.sh')+':/docker-entrypoint-initdb.d/20-create-application-db.sh:ro'],'healthcheck':{'test':['CMD-SHELL','psql -U "$$POSTGRES_USER" -d "$$PG_DB" -tAc "SELECT 1" >/dev/null'],'interval':'3s','timeout':'3s','retries':30,'start_period':'30s'},'shm_size':'256mb'},'redis':{**common,'image':images['redis'],'command':['redis-server','--appendonly','yes','--requirepass',env['REDIS_PASSWORD']],'volumes':['redis_data:/data'],'healthcheck':{'test':['CMD','redis-cli','-a',env['REDIS_PASSWORD'],'ping'],'interval':'3s','timeout':'3s','retries':20}}},'volumes':{'pg_data':{},'redis_data':{}}}
        compose['volumes']={name:{'labels':labels} for name in ['pg_data','redis_data']};compose['networks']={'default':{'labels':labels}}
        atomic(root/'deploy/compose.json',compose)
        data_started=True
        return complete_application(data,root,receipt,docker,images,compose,completed)
    except Exception:
        receipt['state']='FAILED';receipt['code']='FIRST_INSTALL_FAILED_PRESERVED'
        if not data_started:
            shutil.rmtree(root);receipt['retryable']=True;receipt['code']='FIRST_INSTALL_FAILED_BEFORE_DATABASE'
        else: atomic(root/'installation.json',receipt)
        atomic(completed,receipt);return receipt
    finally: lock.close()

def created_network(data,root,docker):
    folder=root/'private';require(folder.is_dir() and all(not p.is_symlink() for p in [folder,*folder.parents])
        and folder.stat().st_uid==os.geteuid() and stat.S_IMODE(folder.stat().st_mode)==0o700)
    project='eatwhat-site-'+data['serverId'][:8];rows=json.loads(run([docker,'network','inspect',project+'_default'],timeout=8));require(len(rows)==1)
    row=rows[0];labels=row.get('Labels') or {};network_id=row.get('Id');require(isinstance(network_id,str) and re.fullmatch('[a-f0-9]{64}',network_id)
        and row.get('Name')==project+'_default' and row.get('Driver')=='bridge' and labels.get('you.v50to.eatwhat.server-id')==data['serverId']
        and labels.get('you.v50to.eatwhat.install-job-id')==data['jobId'] and labels.get('com.docker.compose.project')==project
        and labels.get('com.docker.compose.network')=='default')
    proof={'protocol':'eatwhat-created-business-network-v1','serverId':data['serverId'],'jobId':data['jobId'],'networkId':network_id,'project':project}
    path=root/'private/created-network.json'
    if path.exists() or path.is_symlink():
        require(not path.is_symlink() and path.is_file() and path.stat().st_uid==os.geteuid() and stat.S_IMODE(path.stat().st_mode)==0o600
            and path.stat().st_size<=4096 and json.loads(path.read_bytes())==proof)
    else:atomic(path,proof)
    # Proof is fixed at first creation, before application start. A replacement
    # network cannot acquire provenance by copying the original task labels.
    return {'createdNetworkId':network_id,'createdNetworkProofSha256':hashlib.sha256(path.read_bytes()).hexdigest()}

def complete_application(data, root, receipt, docker, images, compose, completed, resume=False):
    job=receipt['jobId'];site=receipt['siteId'];instance=receipt['instanceId'];sha=receipt['artifactSha256'];jar=root/'releases/current.jar';env=data['database']
    project='eatwhat-site-'+data['serverId'][:8];app=compose['services']['app']
    base=[docker,'compose','--project-name',project,'--env-file',str(root/'deploy/.env'),'-f',str(root/'deploy/compose.json')]
    def compose_run(args,**kwargs): return run(base+args,**kwargs)
    receipt['stage']='DATABASE_REDIS';atomic(completed,receipt)
    compose_run(['up','-d','--wait','--wait-timeout','120','postgres','redis'])
    if not resume:receipt.update(created_network(data,root,docker));atomic(completed,receipt)
    compose_run(['up','-d','--no-deps','app'])
    def request(origin,path):
        req=urllib.request.Request(origin+path,headers={'X-Control-Node-Token':data['nodeToken']} if path.startswith('/internal/') else {})
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs): return None
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        with opener.open(req,timeout=3) as r:
            raw=r.read(65537);require(r.status==200 and len(raw)<=65536);return json.loads(raw)
    def healthy(origin):
        require(request(origin,'/actuator/health/readiness')['status']=='UP');s=request(origin,'/internal/control/v1/snapshot');require(s.get('siteId')==site and s.get('instanceId')==instance and s.get('mode')==data.get('mode','CAMPUS') and s.get('observationStatus')=='OBSERVED')
    def await_health(origin,seconds=180):
        deadline=time.monotonic()+seconds
        while True:
            try: healthy(origin);return
            except Exception:
                if time.monotonic()>deadline: raise Rejected()
                time.sleep(1)
    receipt['stage']='APPLICATION_START';atomic(completed,receipt)
    origin='http://127.0.0.1:'+str(data['httpPort']);await_health(origin)
    # Restore rehearsal runs before opening the loopback-only business endpoint through any proxy.
    # The fingerprint rebind below is confined to our newly created disposable restored database.
    if not (resume and receipt.get('restoreEvidenceId')):
        receipt['stage']='RESTORE_REHEARSAL';atomic(completed,receipt)
        compose_run(['stop','app'])
        dump=root/'private'/('recovery-restore-'+job+'.dump' if resume else 'first-backup.dump')
        with open(dump,'xb') as out: os.fchmod(out.fileno(),0o600);compose_run(['exec','-T','postgres','pg_dump','-U',env['POSTGRES_USER'],'-d',env['PG_DB'],'-Fc'],output=out)
        temporary='eatwhat_restore_'+job.replace('-','');receipt['restoreDatabase']=temporary;receipt['restoreCleanupVerified']=False;atomic(completed,receipt);compose_run(['exec','-T','postgres','createdb','-U',env['POSTGRES_USER'],'--template=template0',temporary])
        with open(dump,'rb') as source: compose_run(['exec','-T','postgres','pg_restore','--exit-on-error','-U',env['POSTGRES_USER'],'-d',temporary],stdin=source)
        # Only a disposable DB with the expected identity and no business users can be rebound for this rehearsal.
        sql="DO $$ BEGIN IF (SELECT count(*) FROM public.users)<>0 OR NOT EXISTS(SELECT 1 FROM eatwhat_identity.node_state WHERE singleton AND site_id='"+site+"' AND instance_id='"+instance+"') THEN RAISE EXCEPTION 'not a fresh rehearsal'; END IF; END $$; UPDATE eatwhat_identity.node_state SET database_name=current_database(),database_oid=(SELECT oid FROM pg_database WHERE datname=current_database()),cluster_identifier=(SELECT system_identifier::text FROM pg_control_system()) WHERE singleton;"
        compose_run(['exec','-T','postgres','psql','-U',env['POSTGRES_USER'],'-d',temporary,'-v','ON_ERROR_STOP=1','-c',sql])
        restore=root/'private'/('recovery-restore-'+job+'.properties' if resume else 'restore.properties');properties='\n'.join(line.replace('/eatwhat','/'+temporary) if line.startswith(('spring.datasource.url=','spring.flyway.url=')) else line for line in data['properties'].splitlines()).replace('spring.data.redis.database=0','spring.data.redis.database=15')+'\n';properties+='\n'.join(name+'=false' for name in ['app.account.enabled','app.sync.capture-enabled','app.sync.transport-enabled','app.storage.files.replication.worker-enabled','app.storage.files.gc.worker-enabled','app.storage.files.lifecycle.worker-enabled','app.control.servers.installation-enabled','app.control.servers.cluster-enrollment-enabled'])+'\n';atomic(restore,properties.encode());os.chown(restore,10001,10001)
        restore_state=root/('restore-state-'+job if resume else 'restore-state')
        if resume:restore_state.mkdir(mode=0o700);os.chown(restore_state,10001,10001)
        rehearsal=copy.deepcopy(app);rehearsal['restart']='no';rehearsal.pop('ports');rehearsal.pop('depends_on');rehearsal['volumes']=['${APP_JAR}:/app/app.jar:ro',str(restore)+':/config/application.properties:ro',str(root/'config/control.token')+':/config/control.token:ro',str(restore_state)+':/state'];compose['services']['restore-check']=rehearsal;atomic(root/'deploy/compose.json',compose)
        try:
            compose_run(['up','-d','--no-deps','restore-check']);container=compose_run(['ps','-q','restore-check']).decode().strip();details=json.loads(run([docker,'inspect',container]))[0];networks=details['NetworkSettings']['Networks'];ip=next(v['IPAddress'] for v in networks.values() if v['IPAddress']);await_health('http://'+ip+':8080');receipt['restoreEvidenceId']='fresh-install-restore-'+job
        finally:
            compose_run(['rm','-sf','restore-check']);compose['services'].pop('restore-check',None);atomic(root/'deploy/compose.json',compose);compose_run(['exec','-T','postgres','dropdb','-U',env['POSTGRES_USER'],temporary]);restore.unlink(missing_ok=True)
            if resume:shutil.rmtree(restore_state)
            receipt['restoreCleanupVerified']=True;atomic(completed,receipt)
    config={'site_id':site,'instance_id':instance,'project':project,'compose_file':str(root/'deploy/compose.json'),'env_file':str(root/'deploy/.env'),'active_jar':str(jar),'state_directory':str(root/'private/deployments'),'node_token_file':str(root/'config/control.token'),'origin':origin,'docker':docker,'control_host':False,'restore_evidence_id':receipt['restoreEvidenceId'],'command_timeout_seconds':300,'health_timeout_seconds':180,'minimum_free_bytes':1073741824,'maximum_artifact_bytes':536870912}
    atomic(root/'deploy/release.json',config)
    receipt['stage']='ADMIN_INITIALIZATION';atomic(completed,receipt)
    admin=data.get('administrator')
    if admin:
        require(isinstance(admin['username'],str) and 0<len(admin['username'])<=32 and '\x00' not in admin['username'] and re.fullmatch(r'\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}',admin['passwordHash']))
        sql="SET standard_conforming_strings=on; INSERT INTO public.users(nick_name,password_hash,role) VALUES ('"+admin['username'].replace("'","''")+"','"+admin['passwordHash']+"','admin');"
        if resume:sql="SET log_statement='none'; SET log_min_error_statement='panic'; BEGIN; LOCK TABLE public.users IN EXCLUSIVE MODE; DO $$ BEGIN IF EXISTS(SELECT 1 FROM public.users) THEN RAISE EXCEPTION 'recovery administrator requires an empty database'; END IF; END $$; "+sql+" COMMIT;"
        admin_file=root/'private/admin-bootstrap.sql';atomic(admin_file,sql.encode())
        try:
            with open(admin_file,'rb') as stream: compose_run(['exec','-T','postgres','psql','-U',env['POSTGRES_USER'],'-d',env['PG_DB'],'-v','ON_ERROR_STOP=1'],stdin=stream)
        finally: admin_file.unlink(missing_ok=True)
    compose_run(['up','-d','--no-deps','app']);await_health(origin)
    # Verify the running container's bytes, not merely the on-host JAR.
    with tempfile.TemporaryFile() as out:
        compose_run(['exec','-T','app','cat','/app/app.jar'],output=out);out.seek(0);digest=hashlib.sha256()
        for block in iter(lambda:out.read(65536),b''): digest.update(block)
        require(digest.hexdigest()==sha)
    receipt['stage']='COMPLETE';receipt['state']='SUCCEEDED';receipt['services']=['app','postgres','redis'];receipt['images']=images;atomic(root/'installation.json',receipt);atomic(completed,receipt);return receipt

def acquire_lock(path):
    fd=os.open(path,os.O_CREAT|os.O_WRONLY|os.O_NOFOLLOW,0o600)
    try:
        require(stat.S_IMODE(os.fstat(fd).st_mode)==0o600 and os.fstat(fd).st_uid==os.geteuid())
        try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise Rejected()
        return fd
    except Exception:os.close(fd);raise

def install(data):
    # Lock both the job and the Compose project before preflight. Separate controllers
    # cannot race two UUIDs with the same project prefix into one database volume.
    job_lock=acquire_lock(result_path(data,True).with_suffix('.lock'))
    try:
        directory=BASE/'.project-locks';directory.mkdir(mode=0o700,exist_ok=True)
        require(not directory.is_symlink() and directory.stat().st_uid==os.geteuid() and stat.S_IMODE(directory.stat().st_mode)==0o700)
        project_lock=acquire_lock(directory/('eatwhat-site-'+str(uuid.UUID(data['serverId']))[:8]+'.lock'))
        try:return _install(data)
        finally:os.close(project_lock)
    finally:os.close(job_lock)

def main():
    os.umask(0o077)
    try:
        require(len(sys.argv)==2);data=input_header();action=sys.argv[1]
        if action=='inspect': result=inspect(data)
        elif action=='status': result=status(data)
        elif action=='install': result=install(data)
        else: raise Rejected()
        print(json.dumps(result,separators=(',',':')))
    except Exception:
        print('{"code":"FIRST_INSTALL_REJECTED_OR_UNKNOWN"}');sys.exit(1)
if __name__=='__main__': main()

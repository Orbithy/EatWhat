"""Registered cluster release protocol: durable, detached, at-most-once dispatch.

Java embeds only reviewed sources. No user commands, arbitrary paths, database
restore or service-wide restart are accepted. Status never resumes execution.
"""
import base64, contextlib, datetime, fcntl, hashlib, json, os, re, select, shutil, signal, stat, subprocess, sys, tempfile, time, types
from pathlib import Path

CORE_SOURCE='__EATWHAT_CLUSTER_RELEASE_CORE_BASE64__'
core=types.ModuleType('eatwhat_cluster_release_core')
if not CORE_SOURCE.startswith('__EATWHAT_'):
    exec(compile(base64.b64decode(CORE_SOURCE),'<reviewed-release-core>','exec'),core.__dict__)
PROTOCOL='eatwhat-cluster-release-v1'
BINDING=('id','serverId','siteId','instanceId','clusterId','epoch','databaseFingerprint','artifactSha256','fromSha256','manifestHash')
TERMINAL={'SUCCEEDED','FAILED','ROLLED_BACK','ROLLBACK_FAILED'}
MAX_ARTIFACT=536870912

def bind_source_matches(observed,expected):
    # Production Linux requires the exact admitted host path. The disposable
    # Docker Desktop acceptance fixture overrides only this comparison to
    # recognize its independently verified VM path alias; no runtime input can.
    return observed==expected

def sync_directory(path):
    fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY)
    try:os.fsync(fd)
    finally:os.close(fd)

def atomic(path,value):
    if isinstance(value,dict) and value.get('protocol')==PROTOCOL and value.get('state') in TERMINAL|{'RUNNING'}:value['updatedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    core.safe(path);data=value if isinstance(value,bytes) else json.dumps(value,separators=(',',':')).encode()
    fd,name=tempfile.mkstemp(prefix='.cluster-release-',dir=path.parent)
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,'wb') as output:output.write(data);output.flush();os.fsync(output.fileno())
        os.replace(name,path);sync_directory(path.parent)
    finally:Path(name).unlink(missing_ok=True)

def copy_private(source,destination):
    core.digest(source);fd=os.open(destination,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'wb') as output,open(source,'rb') as data:
        shutil.copyfileobj(data,output);output.flush();os.fsync(output.fileno())
    sync_directory(destination.parent)

class Runner:
    def __init__(self,header,context_factory=None):
        self.h=core.validated_header(header);core.require(sys.platform=='linux' and os.geteuid()==core.ROOT_UID,'RELEASE_ROOT_LINUX_REQUIRED')
        self.root=core.folder(core.BASE/self.h['serverId'],{core.ROOT_UID});core.trusted_ancestors(self.root);core.folder(self.root/'private',{core.ROOT_UID})
        self.parent=self.root/'private/cluster-deployments';self.directory=self.parent/self.h['id'];self.receipt=self.directory/'receipt.json';self.context_factory=context_factory or core.ReleaseContext
        self.lock_fd=None
        if self.parent.exists() or self.parent.is_symlink():core.folder(self.parent,{core.ROOT_UID})
        if self.directory.exists() or self.directory.is_symlink():core.folder(self.directory,{core.ROOT_UID})

    def context(self):return self.context_factory(self.h)

    def reply(self,state,**details):return {key:self.h[key] for key in BINDING}|{'protocol':PROTOCOL,'state':state}|details

    def journal(self):
        if not self.receipt.exists() and not self.receipt.is_symlink():return None
        value=core.document(self.receipt);core.require(value.get('protocol')==PROTOCOL,'RELEASE_RECEIPT_PROTOCOL_MISMATCH')
        for key in BINDING:core.require(value.get(key)==self.h[key],'RELEASE_RECEIPT_BINDING_CHANGED')
        proof=self.h.get('configurationProof')
        core.require(proof is None or proof==value.get('configurationProof'),'RELEASE_CONFIGURATION_CHANGED')
        core.require(value.get('state') in TERMINAL|{'RUNNING'},'RELEASE_INVALID_RECEIPT')
        return value

    @contextlib.contextmanager
    def lock(self):
        path=self.root/'installation.lock';core.private(path,4096);fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
        try:
            try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError:raise core.ReleaseRejected('RELEASE_PROJECT_BUSY') from None
            yield fd
        finally:os.close(fd)

    def inspect_containers(self,context,recovery=False):
        # Compose can find a project with the same short prefix. Prove every
        # existing service belongs to this full installation before changing it.
        configured=core.document(self.root/'deploy/compose.json')['services']
        for service in ['app','postgres','redis']:
            ids=context.agent.command(['ps',*(['--all'] if recovery and service=='app' else []),'-q',service]).decode().split()
            if recovery and service=='app' and not ids:continue
            core.require(len(ids)==1 and re.fullmatch('[0-9a-f]{12,64}',ids[0]),'RELEASE_CONTAINER_IDENTITY_UNAVAILABLE')
            result=subprocess.run([context.c['docker'],'inspect',ids[0]],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env=context.agent.environment,check=True,timeout=15)
            core.require(len(result.stdout)<=1048576,'RELEASE_CONTAINER_INSPECTION_TOO_LARGE');items=json.loads(result.stdout);core.require(isinstance(items,list) and len(items)==1,'RELEASE_CONTAINER_IDENTITY_UNAVAILABLE');item=items[0];labels=item.get('Config',{}).get('Labels',{})
            core.require(labels.get('com.docker.compose.project')==context.c['project'] and labels.get('com.docker.compose.service')==service and labels.get('com.docker.compose.project.config_files')==str(self.root/'deploy/compose.json'),'RELEASE_CONTAINER_BINDING_CHANGED')
            core.require(labels.get('you.v50to.eatwhat.server-id',self.h['serverId'])==self.h['serverId'],'RELEASE_CONTAINER_BINDING_CHANGED')
            expected_service=configured[service];actual_config=item.get('Config',{});host=item.get('HostConfig',{})
            core.require(item.get('Image')==expected_service['image'] and host.get('Privileged') is False,'RELEASE_CONTAINER_CONFIG_CHANGED')
            actual_environment=dict(entry.split('=',1) for entry in actual_config.get('Env',[]) if isinstance(entry,str) and '=' in entry)
            for key,value in expected_service.get('environment',{}).items():core.require(actual_environment.get(key)==value,'RELEASE_CONTAINER_ENVIRONMENT_CHANGED')
            ports={}
            for port in expected_service.get('ports',[]):
                address,published,target=port.split(':');ports.setdefault(target+'/tcp',[]).append({'HostIp':address,'HostPort':published})
            core.require({key:value for key,value in host.get('PortBindings',{}).items() if value}==ports,'RELEASE_CONTAINER_PORT_CHANGED')
            if service=='app':
                core.require(actual_config.get('User')==expected_service['user'] and actual_config.get('Entrypoint')==['java'] and actual_config.get('Cmd')==expected_service['command'] and host.get('ReadonlyRootfs') is True,'RELEASE_CONTAINER_CONFIG_CHANGED')
                core.require(not any(key.startswith(('SPRING_','APP_')) or key in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','LD_PRELOAD','LD_LIBRARY_PATH'] for key in actual_environment),'RELEASE_CONTAINER_ENVIRONMENT_CHANGED')
            if service=='redis':core.require(actual_config.get('Cmd')==expected_service.get('command'),'RELEASE_CONTAINER_CONFIG_CHANGED')
            expected={'app':{'/app/app.jar':str(self.root/'releases/current.jar'),'/config':str(self.root/'config'),'/state':str(self.root/'app-state')},'postgres':{'/docker-entrypoint-initdb.d/20-create-application-db.sh':str(self.root/'deploy/initdb.sh')},'redis':{}}[service]
            mounts=item.get('Mounts',[])
            for destination,source in expected.items():
                match=[mount for mount in mounts if mount.get('Destination')==destination]
                core.require(len(match)==1 and match[0].get('Type')=='bind' and bind_source_matches(match[0].get('Source'),source) and (destination=='/state' or match[0].get('RW') is False),'RELEASE_CONTAINER_MOUNT_CHANGED')
            for mount in mounts:
                if mount.get('Type')=='bind':core.require(mount.get('Destination') in expected,'RELEASE_CONTAINER_MOUNT_CHANGED')
                elif mount.get('Type')=='volume':core.require(service in ['postgres','redis'] and mount.get('Name')==context.c['project']+('_pg_data' if service=='postgres' else '_redis_data') and mount.get('Destination')==('/var/lib/postgresql/data' if service=='postgres' else '/data'),'RELEASE_CONTAINER_VOLUME_CHANGED')
                else:core.require(service=='app' and mount.get('Type')=='tmpfs' and mount.get('Destination')=='/tmp','RELEASE_CONTAINER_MOUNT_CHANGED')
            if not(recovery and service=='app'):core.require(item.get('State',{}).get('Running') is True,'RELEASE_CONTAINER_NOT_RUNNING')

    def frozen(self,context):
        core.require(self.h.get('configurationProof')==context.configuration_proof,'RELEASE_CONFIGURATION_CHANGED')

    def prepare(self):
        with self.lock():
            context=self.context();context.identity(self.h['fromSha256']);self.inspect_containers(context);core.verify_authority(self.h)
            return self.reply('PREPARED',configurationProof=context.configuration_proof,runtimeSha256=self.h['fromSha256'],readinessVerified=True)

    def status_locked(self):
        journal=self.journal()
        recovery=journal.get('recovery') if journal else None
        pending=isinstance(recovery,dict) and recovery.get('state') in {'RUNNING','OUTCOME_UNKNOWN'}
        if journal and journal['state'] in TERMINAL and not pending and not(journal['state']=='ROLLBACK_FAILED' and journal.get('rollbackAttempted')):return journal
        context=self.context()
        if journal:self.frozen(context)
        if pending and recovery.get('mutationStarted') is True:
            try:
                context.identity(self.h['fromSha256']);return journal|{'state':'ROLLED_BACK','stage':'COMPLETE','code':'RELEASE_MANUAL_ROLLBACK_RUNTIME_VERIFIED','derived':True,'recovery':recovery|{'state':'SUCCEEDED','stage':'COMPLETE','code':'RELEASE_MANUAL_ROLLBACK_RUNTIME_VERIFIED'}}
            except Exception:return journal|{'state':'OUTCOME_UNKNOWN','code':'RELEASE_RECOVERY_OUTCOME_UNKNOWN'}
        try:
            context.identity(self.h['fromSha256'])
            if not journal:
                if self.directory.exists():return self.reply('OUTCOME_UNKNOWN',code='RELEASE_TASK_RECORD_MISSING')
                return self.reply('NOT_STARTED',code='RELEASE_NO_JOURNAL_AND_OLD_RUNTIME_VERIFIED')
            if journal.get('mutationStarted') is False:return journal|{'state':'FAILED','code':'RELEASE_INTERRUPTED_BEFORE_APPLICATION_CHANGE','derived':True}
            if journal.get('rollbackAttempted') is True and not pending:return journal|{'state':'ROLLED_BACK','stage':'COMPLETE','code':'RELEASE_ROLLBACK_RUNTIME_VERIFIED','derived':True}
        except Exception:pass
        if pending and recovery.get('mutationStarted') is False:
            journal=journal|{'recovery':recovery|{'state':'FAILED','stage':'COMPLETE','code':'RELEASE_RECOVERY_INTERRUPTED_BEFORE_APPLICATION_CHANGE'}}
        if journal and journal.get('mutationStarted') is True:
            try:
                context.identity(self.h['artifactSha256']);return journal|{'state':'SUCCEEDED','code':'RELEASE_INTERRUPTED_NEW_RUNTIME_VERIFIED','derived':True}
            except Exception:pass
        return self.reply('OUTCOME_UNKNOWN',code='RELEASE_INTERRUPTED_REQUIRES_REVIEW',rollbackEligible=bool(journal and journal.get('mutationStarted')))

    def status(self):
        # A terminal receipt is historical evidence and remains readable after
        # later deployments or membership changes. It is not current health.
        journal=self.journal()
        if journal and journal['state'] in TERMINAL and not(journal['state']=='ROLLBACK_FAILED' and (journal.get('rollbackAttempted') or journal.get('recovery'))):return journal
        try:
            with self.lock():return self.status_locked()
        except core.ReleaseRejected as failure:
            if failure.code=='RELEASE_PROJECT_BUSY':return (journal if journal else self.reply('OUTCOME_UNKNOWN'))|{'code':'RELEASE_PROJECT_BUSY'}
            raise

    def recovery_id(self):return core.canonical(self.h.get('recoveryRequestId'))

    def recovery_snapshot(self,context,journal):
        self.frozen(context);self.inspect_containers(context,recovery=True);core.verify_authority(self.h)
        current=core.digest(context.agent.jar);core.require(current in {self.h['fromSha256'],self.h['artifactSha256']},'RELEASE_RUNTIME_ARTIFACT_MISMATCH')
        core.require(core.digest(self.directory/'previous.jar')==self.h['fromSha256'],'RELEASE_PREVIOUS_ARTIFACT_CHANGED')
        backup=core.safe(self.directory/'database.dump');fd=os.open(backup,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(fd,'rb') as source:
            info=os.fstat(source.fileno());core.require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode)==0o600 and info.st_uid==core.ROOT_UID and 0<info.st_size<=68719476736,'RELEASE_BACKUP_UNAVAILABLE')
            context.agent.command(['exec','-T','postgres','pg_restore','--list'],stdin=source,output=subprocess.DEVNULL)
        database=context.recovery_database()
        facts={'configurationProof':context.configuration_proof,'currentSha256':current,'previousSha256':self.h['fromSha256'],'database':database,'backupBytes':info.st_size,'backupModifiedNs':info.st_mtime_ns,'backupInode':info.st_ino,'receiptSha256':hashlib.sha256(core.private(self.receipt)).hexdigest(),'recoveryRequestId':self.recovery_id()}
        return facts,hashlib.sha256(json.dumps(facts,sort_keys=True,separators=(',',':')).encode()).hexdigest()

    def recovery_prepare(self):
        self.recovery_id()
        with self.lock():
            preview=self.recovery_prepare_locked()
            return self.reply('RECOVERY_PREPARED',recoveryRequestId=self.recovery_id(),recoveryProof=preview['proof'],restoreSha256=self.h['fromSha256'],currentSha256=preview['facts']['currentSha256'],backupId=self.h['id'],databaseIdentityVerified=True,applicationMayBeStopped=True)

    def recovery_status(self):
        request=self.recovery_id();value=self.status();current=value.get('recovery',{})
        selected=current if current.get('id')==request else next((entry for entry in value.get('recoveryHistory',[]) if entry.get('id')==request),None)
        if selected is None:selected={'id':request,'state':'NOT_STARTED','code':'RELEASE_RECOVERY_NOT_ACCEPTED'} if self.journal() is not None else {'id':request,'state':'OUTCOME_UNKNOWN','code':'RELEASE_RECOVERY_RECORD_UNAVAILABLE'}
        return value|{'recovery':selected}

    def recover(self,detach=True):
        request=self.recovery_id();journal=self.journal()
        if journal and any(value.get('id')==request for value in [journal.get('recovery',{}),*journal.get('recoveryHistory',[])]):return self.recovery_status()
        with self.lock() as fd:
            preview=self.recovery_prepare_locked()
            core.require(self.h.get('recoveryProof')==preview['proof'],'RELEASE_RECOVERY_PROOF_CHANGED')
            timeout=self.h.get('timeoutSeconds');core.require(type(timeout) is int and 30<=timeout<=3600,'RELEASE_TIMEOUT_INVALID')
            journal=preview['journal'];prior=journal.get('recovery')
            history=journal.setdefault('recoveryHistory',[])
            if prior:history.append(prior)
            journal.setdefault('originalOutcome',{key:journal[key] for key in ['state','stage','code','failureStage'] if key in journal})
            journal['recovery']={'id':request,'state':'RUNNING','stage':'VALIDATED','mutationStarted':False,'proof':preview['proof'],'sourceSha256':preview['facts']['currentSha256'],'databaseProof':preview['facts']['database'],'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
            journal.update(state='RUNNING',stage='RECOVERING');atomic(self.receipt,journal)
            if not detach:self.recovery_worker(preview['context'],fd);return self.recovery_status()
            launched=False
            try:
                ready_read,ready_write=os.pipe()
                try:pid=os.fork()
                except Exception:os.close(ready_read);os.close(ready_write);raise
                launched=True
                if pid==0:
                    try:
                        os.close(ready_read);os.setsid();signal.signal(signal.SIGHUP,signal.SIG_IGN);null=os.open('/dev/null',os.O_RDWR)
                        for number in [0,1,2]:os.dup2(null,number)
                        if null>2:os.close(null)
                        os.write(ready_write,b'1');os.close(ready_write);self.recovery_worker(preview['context'],fd)
                    finally:os._exit(0)
                os.close(ready_write)
                try:core.require(bool(select.select([ready_read],[],[],5)[0]) and os.read(ready_read,1)==b'1','RELEASE_DETACH_ACK_UNKNOWN')
                finally:os.close(ready_read)
                return journal
            except Exception:
                if not launched:journal['recovery'].update(state='FAILED',stage='COMPLETE',code='RELEASE_RECOVERY_NOT_LAUNCHED');atomic(self.receipt,journal)
                return journal|{'state':'OUTCOME_UNKNOWN','code':'RELEASE_RECOVERY_LAUNCH_NOT_CONFIRMED'}

    def recovery_prepare_locked(self):
        journal=self.journal();core.require(journal and journal.get('mutationStarted') is True and journal['state'] in {'RUNNING','ROLLBACK_FAILED'},'RELEASE_RECOVERY_NOT_ELIGIBLE')
        core.require(self.status_locked().get('state') not in {'SUCCEEDED','ROLLED_BACK','FAILED'},'RELEASE_RECOVERY_USE_RECONCILIATION')
        core.require(len(journal.get('recoveryHistory',[]))<15,'RELEASE_RECOVERY_HISTORY_CAPACITY')
        context=self.context();facts,proof=self.recovery_snapshot(context,journal)
        return {'journal':journal,'context':context,'facts':facts,'proof':proof}

    def recovery_worker(self,context,fd):
        journal=self.journal();recovery=journal['recovery'];original=context.agent.command;self.lock_fd=fd
        def command(args,*,output=None,input=None,stdin=None):
            source=stdin if stdin is not None else subprocess.DEVNULL if input is None else None
            result=subprocess.run(context.agent.base+args,input=input,stdin=source,stdout=output if output is not None else subprocess.PIPE,stderr=subprocess.DEVNULL,env=context.agent.environment,timeout=context.c.get('command_timeout_seconds',180),pass_fds=(fd,),check=True);return result.stdout
        context.agent.command=command
        def deadline(*unused):raise core.ReleaseRejected('RELEASE_RECOVERY_WORKER_TIMEOUT')
        previous=signal.signal(signal.SIGALRM,deadline);signal.alarm(self.h['timeoutSeconds'])
        try:
            current=self.context();self.frozen(current);current.agent.command=command;self.inspect_containers(current,recovery=True);core.verify_authority(self.h)
            core.require(core.digest(current.agent.jar)==recovery['sourceSha256'] and current.recovery_database()==recovery['databaseProof'],'RELEASE_RECOVERY_PRECONDITION_CHANGED')
            core.require(core.digest(self.directory/'previous.jar')==self.h['fromSha256'],'RELEASE_PREVIOUS_ARTIFACT_CHANGED')
            recovery.update(stage='RESTORING',mutationStarted=True);journal['stage']='RECOVERING';atomic(self.receipt,journal)
            context.agent.install(self.directory/'previous.jar',0o600);context.agent.command(['up','-d','--no-deps','--force-recreate','app']);recovery['stage']='VERIFYING';atomic(self.receipt,journal)
            context.agent.wait_ready(self.h['fromSha256'],self.h['siteId'],self.h['instanceId']);verified=self.context();verified.agent.command=command;verified.identity(self.h['fromSha256'])
            recovery.update(state='SUCCEEDED',stage='COMPLETE',code='RELEASE_MANUAL_ROLLBACK_RUNTIME_VERIFIED');journal.update(state='ROLLED_BACK',stage='COMPLETE',code='RELEASE_MANUAL_ROLLBACK_RUNTIME_VERIFIED',backupId=self.h['id'])
        except Exception as failure:
            recovery.update(failureStage=recovery.get('stage'),stage='COMPLETE',state='OUTCOME_UNKNOWN' if recovery.get('mutationStarted') else 'FAILED',code=failure.code if isinstance(failure,core.ReleaseRejected) else 'RELEASE_RECOVERY_NOT_VERIFIED')
            if recovery.get('mutationStarted'):journal.update(state='ROLLBACK_FAILED',stage='COMPLETE',code='RELEASE_MANUAL_ROLLBACK_NOT_VERIFIED')
        finally:signal.alarm(0);signal.signal(signal.SIGALRM,previous);context.agent.command=original;self.lock_fd=None
        atomic(self.receipt,journal)

    def reject_other_unfinished(self):
        if not self.parent.exists():return
        entries=list(self.parent.iterdir());core.require(len(entries)<=10000,'RELEASE_HISTORY_CAPACITY_EXCEEDED')
        for directory in entries:
            if directory==self.directory:continue
            if re.fullmatch(r'\.pending-[0-9a-f-]{36}-[A-Za-z0-9_-]{6,16}',directory.name):core.folder(directory,{core.ROOT_UID});continue
            core.canonical(directory.name);core.folder(directory,{core.ROOT_UID});value=core.document(directory/'receipt.json')
            core.require(value.get('protocol')==PROTOCOL and value.get('state') in {'SUCCEEDED','FAILED','ROLLED_BACK'},'RELEASE_PREVIOUS_TASK_UNRESOLVED')

    def dispatch(self,stream,detach=True):
        previous=self.journal()
        if previous is not None:return previous
        with self.lock() as fd:
            previous=self.journal()
            if previous is not None:return previous # Never resume or reapply an accepted UUID.
            core.require(not self.directory.exists(),'RELEASE_TASK_RECORD_MISSING')
            self.reject_other_unfinished();context=self.context();self.frozen(context);context.identity(self.h['fromSha256']);self.inspect_containers(context);core.verify_authority(self.h)
            size=self.h.get('artifactBytes');core.require(type(size) is int and 0<size<=MAX_ARTIFACT,'RELEASE_ARTIFACT_SIZE_INVALID')
            watchdog=self.h.get('timeoutSeconds');core.require(type(watchdog) is int and 30<=watchdog<=3600,'RELEASE_TIMEOUT_INVALID')
            core.require(shutil.disk_usage(self.root/'private').free>size*3+max(1073741824,context.c.get('minimum_free_bytes',1073741824)),'RELEASE_INSUFFICIENT_DISK')
            if not self.parent.exists():self.parent.mkdir(mode=0o700);sync_directory(self.parent.parent)
            journal=self.reply('RUNNING',stage='UPLOAD',configurationProof=context.configuration_proof,mutationStarted=False,createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
            # Publish receipt + private request together. A crash before rename
            # leaves a staging directory from which no worker can be started.
            pending=Path(tempfile.mkdtemp(prefix='.pending-'+self.h['id']+'-',dir=self.parent))
            atomic(pending/'receipt.json',journal);atomic(pending/'request.json',self.h);sync_directory(pending)
            os.rename(pending,self.directory);sync_directory(self.parent)
            candidate=self.directory/'candidate.jar'
            launched=False
            try:
                output_fd=os.open(candidate,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
                digest=hashlib.sha256();received=0
                with os.fdopen(output_fd,'wb') as output:
                    while received<size:
                        block=stream.read(min(65536,size-received));core.require(bool(block),'RELEASE_UPLOAD_INCOMPLETE');received+=len(block);digest.update(block);output.write(block)
                    output.flush();os.fsync(output.fileno())
                core.require(not stream.read(1) and digest.hexdigest()==self.h['artifactSha256'],'RELEASE_ARTIFACT_SHA_MISMATCH');sync_directory(self.directory)
                journal['stage']='UPLOADED';atomic(self.receipt,journal)
                if not detach:self.worker(context,fd);return self.journal()
                ready_read,ready_write=os.pipe()
                try:pid=os.fork()
                except Exception:os.close(ready_read);os.close(ready_write);raise
                launched=True
                if pid==0:
                    try:
                        os.close(ready_read)
                        os.setsid();signal.signal(signal.SIGHUP,signal.SIG_IGN)
                        null=os.open('/dev/null',os.O_RDWR)
                        for number in [0,1,2]:os.dup2(null,number)
                        if null>2:os.close(null)
                        os.write(ready_write,b'1');os.close(ready_write)
                        self.worker(context,fd)
                    finally:os._exit(0)
                os.close(ready_write)
                try:core.require(bool(select.select([ready_read],[],[],5)[0]) and os.read(ready_read,1)==b'1','RELEASE_DETACH_ACK_UNKNOWN')
                finally:os.close(ready_read)
                return journal
            except Exception as failure:
                recorded=self.journal()
                if launched or recorded and recorded.get('mutationStarted') is True:
                    return recorded|{'state':'OUTCOME_UNKNOWN','code':'RELEASE_RECEIPT_PUBLICATION_NOT_CONFIRMED'}
                journal.update(state='FAILED',stage='COMPLETE',code=failure.code if isinstance(failure,core.ReleaseRejected) else 'RELEASE_DISPATCH_FAILED_BEFORE_APPLICATION_CHANGE');atomic(self.receipt,journal);return journal

    def worker(self,context,fd):
        self.lock_fd=fd;journal=self.journal();old=self.directory/'previous.jar';candidate=self.directory/'candidate.jar'
        original_command=context.agent.command
        # Keep the project lock in an in-flight Docker CLI if the worker dies.
        def command(args,*,output=None,input=None,stdin=None):
            source=stdin if stdin is not None else subprocess.DEVNULL if input is None else None
            result=subprocess.run(context.agent.base+args,input=input,stdin=source,stdout=output if output is not None else subprocess.PIPE,stderr=subprocess.DEVNULL,env=context.agent.environment,timeout=context.c.get('command_timeout_seconds',180),pass_fds=(fd,),check=True);return result.stdout
        context.agent.command=command
        def deadline(*unused):raise core.ReleaseRejected('RELEASE_WORKER_TIMEOUT')
        prior_handler=signal.signal(signal.SIGALRM,deadline);signal.alarm(self.h['timeoutSeconds'])
        try:
            journal['stage']='BACKUP';atomic(self.receipt,journal);context.agent.backup(self.directory);copy_private(context.agent.jar,old);core.require(core.digest(old)==self.h['fromSha256'],'RELEASE_PREVIOUS_ARTIFACT_CHANGED')
            current=self.context();self.frozen(current);current.identity(self.h['fromSha256']);self.inspect_containers(current);core.verify_authority(self.h);core.require(core.digest(candidate)==self.h['artifactSha256'],'RELEASE_ARTIFACT_SHA_MISMATCH')
            journal.update(stage='INSTALLING',mutationStarted=True);atomic(self.receipt,journal)
            context.agent.install(candidate,0o600);journal['stage']='STARTING';atomic(self.receipt,journal);context.agent.command(['up','-d','--no-deps','--force-recreate','app']);context.agent.wait_ready(self.h['artifactSha256'],self.h['siteId'],self.h['instanceId']);current.identity(self.h['artifactSha256'])
            journal.update(state='SUCCEEDED',stage='COMPLETE',code='RELEASE_RUNTIME_VERIFIED',backupId=self.h['id'])
        except Exception as failure:
            journal['failureStage']=journal.get('stage')
            if journal.get('mutationStarted') is True:
                signal.alarm(660);journal.update(stage='ROLLING_BACK',rollbackAttempted=True);atomic(self.receipt,journal)
                try:
                    core.require(core.digest(old)==self.h['fromSha256'],'RELEASE_PREVIOUS_ARTIFACT_CHANGED');self.frozen(self.context());context.agent.install(old,0o600);context.agent.command(['up','-d','--no-deps','--force-recreate','app']);context.agent.wait_ready(self.h['fromSha256'],self.h['siteId'],self.h['instanceId']);self.context().identity(self.h['fromSha256']);journal.update(state='ROLLED_BACK',stage='COMPLETE',code='RELEASE_ROLLED_BACK',backupId=self.h['id'])
                except Exception:journal.update(state='ROLLBACK_FAILED',stage='COMPLETE',code='RELEASE_ROLLBACK_NOT_VERIFIED')
            else:journal.update(state='FAILED',stage='COMPLETE',code=failure.code if isinstance(failure,core.ReleaseRejected) else 'RELEASE_FAILED_BEFORE_APPLICATION_CHANGE')
        finally:signal.alarm(0);signal.signal(signal.SIGALRM,prior_handler);context.agent.command=original_command;self.lock_fd=None
        # Publishing a terminal result must never trigger a subsequent rollback:
        # rename may have succeeded even if a later fsync/ack fails.
        atomic(self.receipt,journal)

    def seal(self):
        # Metadata only, after the authority recorded its terminal decision.
        # This makes a derived interrupted result durable without re-executing it.
        proof=core.read_authority(self.h);expected=self.h.get('expectedRemoteState');core.require(expected in {'SUCCEEDED','FAILED','ROLLED_BACK','NOT_STARTED'},'RELEASE_SEAL_OUTCOME_REQUIRED')
        allowed={'SUCCEEDED','PARTIAL_FAILURE'} if expected=='SUCCEEDED' else {'FAILED','PARTIAL_FAILURE'}
        core.require(proof.get('state') in allowed and proof.get('authorized') is False,'RELEASE_AUTHORITY_NOT_TERMINAL')
        journal=self.journal()
        if journal and journal['state'] in {'SUCCEEDED','FAILED','ROLLED_BACK'}:
            core.require(journal['state']==expected,'RELEASE_SEAL_OUTCOME_CONFLICT')
            if not journal.get('sealedAfterAuthority'):journal['sealedAfterAuthority']=True;atomic(self.receipt,journal)
            return journal
        with self.lock():
            value=self.status_locked();core.require(value.get('state')==expected,'RELEASE_SEAL_OUTCOME_CONFLICT')
            if expected!='NOT_STARTED':value.pop('derived',None);value['sealedAfterAuthority']=True;atomic(self.receipt,value)
            return value

def public_reply(value):
    result=dict(value)
    for key in ['recovery','recoveryHistory']:
        if key not in result:continue
        allowed={'id','state','stage','code','failureStage','mutationStarted','createdAt','updatedAt'}
        result[key]=[{k:v for k,v in item.items() if k in allowed} for item in result[key]] if key=='recoveryHistory' else {k:v for k,v in result[key].items() if k in allowed}
    return result

def main():
    try:
        core.require(len(sys.argv)==2 and sys.argv[1] in ['prepare','dispatch','status','seal','recovery-prepare','recover','recovery-status'],'RELEASE_INVALID_ACTION')
        raw=sys.stdin.buffer.readline(65537);core.require(raw.endswith(b'\n') and len(raw)<=65536,'RELEASE_INVALID_REQUEST');runner=Runner(json.loads(raw));action=sys.argv[1]
        reply=runner.dispatch(sys.stdin.buffer) if action=='dispatch' else getattr(runner,action.replace('-','_'))();print(json.dumps(public_reply(reply),separators=(',',':')))
    except Exception as failure:
        code=failure.code if hasattr(failure,'code') else 'RELEASE_REJECTED_OR_UNKNOWN';print(json.dumps({'protocol':PROTOCOL,'code':code},separators=(',',':')));sys.exit(1)

if __name__=='__main__':main()

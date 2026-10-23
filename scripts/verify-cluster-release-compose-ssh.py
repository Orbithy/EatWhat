#!/usr/bin/env python3
"""Real admin HTTP -> registered ControlSsh -> detached agent -> Compose acceptance.

Uses disposable, explicitly selected local images and synthetic credentials only.
The initial retained installation is made by the first-install Compose verifier;
its manifest is imported into the fixture registry, not presented as a server-
installation API test. Membership JOIN, release admission/fence, SSH transport,
backups, application health, rollback and sealing all use the real implementations.
Docker Desktop path/UID and loopback routing adaptations are declared in evidence.
"""
import argparse,base64,datetime,hashlib,json,os,re,secrets,shutil,signal,socket,subprocess,sys,tempfile,time,urllib.error,urllib.request,uuid,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def sha(path):
    with open(path,'rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()
def uid():return str(uuid.uuid4())
def port():
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));return sock.getsockname()[1]
def secret(path,value):
    path.write_bytes(value if isinstance(value,bytes) else value.encode());path.chmod(0o600)
def write_json(path,value):secret(path,json.dumps(value,separators=(',',':')))
def read_properties(path):return dict(line.split('=',1) for line in Path(path).read_text().splitlines() if '=' in line and not line.startswith(('#','!')))
def properties(values):
    # Spring loads .properties as ISO-8859-1. Preserve existing Java escapes and
    # encode new non-ASCII paths, rather than silently mangling workspace names.
    def ascii_value(value):return ''.join(c if ord(c)<128 else ''.join('\\u'+pair.hex() for pair in [c.encode('utf-16-be')[i:i+2] for i in range(0,len(c.encode('utf-16-be')),2)]) for c in str(value))
    return ''.join(key+'='+ascii_value(value)+'\n' for key,value in sorted(values.items()))
def command(args,**kwargs):return subprocess.check_output(args,stderr=subprocess.DEVNULL,timeout=kwargs.pop('timeout',60),**kwargs)
def wait(check,seconds=90):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        result=check()
        if result:return result
        time.sleep(.5)
    raise AssertionError('Isolated fixture condition timed out')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template',type=Path,required=True,help='Explicit synthetic first-install payload template, never production data')
    parser.add_argument('--old-artifact',type=Path,required=True);parser.add_argument('--old-sha',required=True)
    parser.add_argument('--candidate',type=Path,default=ROOT/'target/eatwhat-0.0.1-SNAPSHOT.jar')
    parser.add_argument('--ssh-image',required=True,help='Immutable ID of the locally built fixture image')
    parser.add_argument('--jre-image',default='eclipse-temurin:21-jre')
    args=parser.parse_args();assert re.fullmatch('sha256:[0-9a-f]{64}',args.ssh_image)
    old=args.old_artifact.resolve();candidate=args.candidate.resolve();assert sha(old)==args.old_sha and sha(candidate)!=args.old_sha
    work=Path(tempfile.mkdtemp(prefix='eatwhat-cluster-ssh-compose-'));work.chmod(0o700)
    # Own immutable input; a concurrent Maven package must not replace the JAR
    # of a running fixture controller or the release whose SHA was reviewed.
    copied=work/'controller-and-candidate.jar';shutil.copyfile(candidate,copied);copied.chmod(0o600);candidate=copied
    with zipfile.ZipFile(candidate) as packaged:
        for name,path in [('cluster-release.py',ROOT/'src/main/resources/control/cluster-release.py'),('cluster-release-core.py',ROOT/'src/main/resources/control/cluster-release-core.py'),('remote-release.py',ROOT/'deploy/remote-release.py')]:
            assert packaged.read('BOOT-INF/classes/control/'+name)==path.read_bytes(),'Package the current reviewed protocol before this acceptance run'
    docker=shutil.which('docker');assert docker
    env=dict(os.environ);bootstrap=None;compose=None;manager=None;ssh_container=None;redis_container=None;owned_directory=None;events=None;event_log=None;report={'status':'RUNNING','scope':'REAL_ADMIN_HTTP_REGISTERED_SSH_COMPOSE_SINGLE_LOGICAL_SITE','productionDeployment':False,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':[],'oldArtifactSha256':args.old_sha,'candidateArtifactSha256':sha(candidate),'sshImageId':args.ssh_image,'adaptations':['Docker Desktop fixture paths and original-file UID/GID only; separate Linux production permission acceptance','Loopback-only container HTTP proxy to own host business and management processes; no synthetic HTTP results','Genuine first-install manifest imported into private registry before deployment; not an installation API acceptance','Single logical CAMPUS site; not cross-host production or multiple-site acceptance']}
    source_paths=[Path(__file__),ROOT/'scripts/verify-first-install-compose.py',ROOT/'scripts/fixtures/cluster-ssh/desktop_adapter.py',ROOT/'scripts/fixtures/cluster-ssh/fixture_proxy.py',ROOT/'scripts/fixtures/cluster-ssh/ssh-exec.py',ROOT/'src/main/resources/control/cluster-release-core.py',ROOT/'src/main/resources/control/cluster-release.py',ROOT/'deploy/remote-release.py',*[ROOT/('src/main/java/you/v50to/eatwhat/'+path) for path in ['control/ControlDeploymentRecovery.java','control/ControlDeploymentRecoveryController.java','control/ControlConfiguration.java','control/ControlController.java','control/ControlService.java','control/ControlSsh.java','control/DeploymentExecutor.java','control/ControlRegisteredDeployments.java','control/ControlExceptionHandler.java','control/ControlResponseHeaders.java','cluster/ClusterDeploymentFence.java','cluster/ClusterControl.java','cluster/ClusterAdminController.java']]]
    report['sourceHashes']={str(path.relative_to(ROOT)):sha(path) for path in source_paths}
    log=open(work/'fixture.log','wb');os.chmod(log.name,0o600)
    try:
        command([docker,'image','inspect',args.ssh_image])
        payload=json.loads(args.template.read_text());server_id=uid();instance=uid();cluster=uid();site='server-'+server_id.replace('-','')[:12]
        payload.update(serverId=server_id,jobId=uid(),siteId=site,instanceId=instance,artifactSha256=args.old_sha,artifactBytes=old.stat().st_size,nodeToken=secrets.token_urlsafe(32))
        for key in ['REDIS_PASSWORD','PG_APP_PASSWORD','POSTGRES_PASSWORD']:payload['database'][key]=secrets.token_urlsafe(32)
        values=dict(line.split('=',1) for line in payload['properties'].splitlines() if '=' in line and not line.startswith(('#','!')))
        values.update({'app.site.id':site,'app.site.instance-id':instance,'app.cluster.enabled':'false','spring.datasource.password':payload['database']['PG_APP_PASSWORD'],'spring.flyway.password':payload['database']['POSTGRES_PASSWORD'],'spring.data.redis.password':payload['database']['REDIS_PASSWORD'],'app.jwt.key':secrets.token_urlsafe(48),'app.storage.s3.endpoint':'http://127.0.0.1:1','app.storage.s3.access-key':'synthetic','app.storage.s3.secret-key':'synthetic','app.storage.s3.cdn-endpoint':'','spring.mail.host':'127.0.0.1','sa-token.token-name':'eatwhat-test-token','logging.level.root':'ERROR'})
        payload['properties']=properties(values);payload['releaseAgent']=(ROOT/'deploy/remote-release.py').read_text();payload_path=work/'payload.json';write_json(payload_path,payload)
        owned_directory=Path(tempfile.mkdtemp(prefix='eatwhat-first-install-compose-'));owned_directory.chmod(0o700);root=owned_directory/server_id;project='eatwhat-site-'+server_id[:8]
        compose=[docker,'compose','--project-name',project,'--env-file',str(root/'deploy/.env'),'-f',str(root/'deploy/compose.json')]
        fixture_env=dict(env,EATWHAT_INSTALL_TEST_PAYLOAD=str(payload_path),EATWHAT_INSTALL_TEST_ARTIFACT=str(old),EATWHAT_INSTALL_TEST_JRE_IMAGE=args.jre_image,EATWHAT_INSTALL_TEST_KEEP_SUCCESS='1',EATWHAT_INSTALL_TEST_OUTPUT=str(work/'bootstrap'),EATWHAT_INSTALL_TEST_DIRECTORY=str(owned_directory))
        fixture_env.pop('EATWHAT_INSTALL_TEST_RECOVERY',None);fixture_env.pop('EATWHAT_INSTALL_TEST_ARCHIVE',None)
        print('Bootstrapping disposable old-version Compose application and restore rehearsal',flush=True)
        child=subprocess.Popen([sys.executable,str(ROOT/'scripts/verify-first-install-compose.py')],env=fixture_env,stdout=log,stderr=log,start_new_session=True)
        try:assert child.wait(720)==0,'First-install fixture failed; inspect private fixture log'
        finally:
            if child.poll() is None:
                os.killpg(child.pid,signal.SIGTERM)
                try:child.wait(5)
                except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait(5)
        bootstrap=json.loads((work/'bootstrap/compose-verification.json').read_text());assert bootstrap['status']=='PASS';fixture=bootstrap['retainedFixture'];assert Path(fixture['root'])==root and fixture['project']==project;business_port=fixture['httpPort'];pg_port=port();management_port=port();redis_port=port()
        compose=[docker,'compose','--project-name',project,'--env-file',str(root/'deploy/.env'),'-f',str(root/'deploy/compose.json')]
        def dc(*parts,**kw):return command(compose+list(parts),**kw)
        def sql(statement):return dc('exec','-T','postgres','psql','--no-psqlrc','-v','ON_ERROR_STOP=1','-U',payload['database']['POSTGRES_USER'],'-d',payload['database']['PG_DB'],'-tAc',statement).decode().strip()
        identity_before=sql('SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n');admin_before=sql("SELECT md5(id::text||':'||nick_name||':'||password_hash) FROM users WHERE role='admin'");system_before=sql('SELECT system_identifier::text FROM pg_control_system()')
        config=read_properties(root/'config/application.properties')
        shared={'app.cluster.enabled':'true','app.cluster.coordinator':'true','app.cluster.single-site-only':'true','app.cluster.automatic-gc-enabled':'false','app.cluster.control-lease.enabled':'true','app.cluster.control-lease.candidate':'false','app.site.cluster-id':cluster,'app.site.allocation-slot':'0'}
        config.update(shared);secret(root/'config/application.properties',properties(config))
        compose_data=json.loads((root/'deploy/compose.json').read_text());compose_data['services']['postgres']['ports']=[f'127.0.0.1:{pg_port}:5432'];write_json(root/'deploy/compose.json',compose_data)
        dc('up','-d','--wait','--wait-timeout','90','postgres','redis',timeout=120)
        dc('up','-d','--no-deps','--force-recreate','app',timeout=60)
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));auth=None
        def http(path,body=None,*,admin=False,business=False):
            origin=f'http://127.0.0.1:{business_port if business else management_port}'
            headers={'Content-Type':'application/json'}
            if admin:headers['eatwhat-test-token']=auth
            request=urllib.request.Request(origin+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
            try:
                with opener.open(request,timeout=15) as response:return json.loads(response.read(1048577))
            except urllib.error.HTTPError as failure:
                # Never print a credential-bearing response/request.
                raw=failure.read(65536)
                try:code=json.loads(raw).get('code','HTTP_ERROR')
                except Exception:code='NON_JSON_HTTP_ERROR'
                raise AssertionError(f'Fixture HTTP {failure.code} at {path.split("?")[0]} code={code}') from None
        def ready(business=False):
            try:return http('/actuator/health/readiness',business=business).get('status')=='UP'
            except Exception:return False
        wait(lambda:ready(True),180)
        redis_container=command([docker,'run','-d','--pull','never','-p',f'127.0.0.1:{redis_port}:6379','redis:7.4-alpine']).decode().strip()
        # A real packaged management JVM owns admission/fences, separate from the
        # old business container. It uses the exact installed authority database.
        management=dict(config);state=work/'management-state';state.mkdir(mode=0o700)
        management.update({'server.address':'127.0.0.1','server.port':str(management_port),'spring.datasource.url':f"jdbc:postgresql://127.0.0.1:{pg_port}/{payload['database']['PG_DB']}",'spring.flyway.url':f"jdbc:postgresql://127.0.0.1:{pg_port}/{payload['database']['PG_DB']}",'spring.data.redis.host':'127.0.0.1','spring.data.redis.port':str(redis_port),'spring.data.redis.password':'','app.control.state-directory':str(state),'app.control.node-token-file':str(root/'config/control.token'),'app.cluster.control-lease.candidate':'true','app.cluster.control-lease.controller-name':'ssh-compose-fixture','app.cluster.profiles.authority.site-id':site,'app.cluster.profiles.authority.instance-id':instance,'app.cluster.profiles.authority.mode':'CAMPUS','app.cluster.profiles.authority.allocation-slot':'0','app.cluster.profiles.authority.origin':f'http://127.0.0.1:{business_port}','app.cluster.profiles.authority.token-file':str(root/'config/control.token'),'app.control.deployment.enabled':'true','app.control.deployment.target-source':'REGISTERED_SERVERS','app.control.deployment.controller-artifact':str(candidate),'app.control.deployment.authority-origin':f'http://127.0.0.1:{management_port}','app.control.deployment.timeout-seconds':'300'})
        broken=work/'intentional-startup-failure.jar'
        with zipfile.ZipFile(candidate) as source,zipfile.ZipFile(broken,'w') as destination:
            for item in source.infolist():
                data=source.read(item.filename)
                if item.filename=='META-INF/MANIFEST.MF':
                    data=re.sub(br'(?m)^Start-Class: [^\r\n]+',b'Start-Class: fixture.IntentionallyMissingApplication',data)
                destination.writestr(item,data)
        broken.chmod(0o600)
        for name,artifact,from_sha in [('candidate',candidate,args.old_sha),('intentional-failure',broken,sha(candidate))]:
            prefix='app.control.releases.'+name+'.'
            management.update({prefix+'artifact':str(artifact),prefix+'sha256':sha(artifact),prefix+'acceptance-evidence-id':'isolated-ssh-compose-fixture-'+name,prefix+'rollback-compatible':'true',prefix+'compatible-from-sha256[0]':from_sha,prefix+'compatible-from-sha256[1]':sha(candidate)})
        manager_config=work/'management.properties';secret(manager_config,properties(management));java=shutil.which('java');assert java
        manager=subprocess.Popen([java,'-Xms64m','-Xmx384m','-jar',str(candidate),'--spring.config.location=file:'+str(manager_config),'--spring.config.additional-location=','--spring.config.import='],stdout=log,stderr=log)
        wait(lambda:manager.poll() is None and ready(),90)
        login=http('/auth/login',{'username':payload['administrator']['username'],'password':'synthetic-password','device':'WEB'});assert login['code']==0;auth=login['data']['tokenValue'];auth=auth if auth.startswith('Bearer ') else 'Bearer '+auth
        def api(path,body=None):return http('/admin/control/v1/'+path,body,admin=True)
        wait(lambda:api('cluster/leadership').get('localLeader') is True,30)
        api('cluster/sites',{'requestId':uid(),'expectedEpoch':0,'profileId':'authority'})
        change_id=uid();api('cluster/sites/'+site+'/join',{'requestId':change_id,'expectedEpoch':0})
        def joined():
            change=api('cluster/changes/'+change_id)
            if change.get('state')=='APPLIED':return True
            api('cluster/changes/'+change_id+'/resume',{});return False
        wait(joined,60);status=api('cluster');assert status['epoch']==1 and len(status['members'])==1 and status['members'][0]['state']=='ACTIVE'
        assert sql('SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n')==identity_before
        report['checks']+=['real_old_jar_first_install_and_backup_restore_rehearsal','real_management_boot_jar_migrates_54_while_old_application_runs','real_authenticated_register_join_votes_applied_epoch_1_without_identity_reset']
        print('Real JOIN completed; starting pinned-key SSH executor',flush=True)
        ssh_input=work/'ssh-input';ssh_input.mkdir(mode=0o700);key=ssh_input/'client';subprocess.run(['/usr/bin/ssh-keygen','-q','-t','ed25519','-N','','-f',str(key)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
        secret(ssh_input/'password',secrets.token_urlsafe(32))
        with zipfile.ZipFile(candidate) as packaged:
            core=packaged.read('BOOT-INF/classes/control/cluster-release-core.py').decode().replace('__EATWHAT_RELEASE_AGENT_BASE64__',base64.b64encode(packaged.read('BOOT-INF/classes/control/remote-release.py')).decode())
            source=packaged.read('BOOT-INF/classes/control/cluster-release.py').decode().replace('__EATWHAT_CLUSTER_RELEASE_CORE_BASE64__',base64.b64encode(core.encode()).decode())
        write_json(ssh_input/'allowed-sources.json',{hashlib.sha256(source.encode()).hexdigest():[[action] for action in ['prepare','dispatch','status','seal','recovery-prepare','recover','recovery-status']]})
        assert Path(fixture['directory']).samefile(Path(fixture['directory']).resolve())
        write_json(ssh_input/'desktop-adapter.json',{'base':fixture['directory'],'canonicalHostBase':str(Path(fixture['directory']).resolve()),'uid':os.getuid(),'gid':os.getgid(),'businessPort':business_port,'managementPort':management_port})
        ssh_container=command([docker,'run','-d','--pull','never','--init','-p','127.0.0.1::22','-v',str(ssh_input)+':/fixture-input:ro','-v',fixture['directory']+':'+fixture['directory'],'-v','/var/run/docker.sock:/var/run/docker.sock',args.ssh_image]).decode().strip()
        details=json.loads(command([docker,'inspect',ssh_container]))[0];ssh_port=int(details['NetworkSettings']['Ports']['22/tcp'][0]['HostPort'])
        def ssh_ready():
            try:
                with socket.create_connection(('127.0.0.1',ssh_port),timeout=1) as connection:return connection.recv(100).startswith(b'SSH-')
            except OSError:return False
        wait(ssh_ready,20)
        key_parts=command([docker,'exec',ssh_container,'cat','/run/eatwhat-ssh/host_key.pub']).decode().split();host_key=' '.join(key_parts[:2]);fingerprint='SHA256:'+base64.b64encode(hashlib.sha256(base64.b64decode(key_parts[1])).digest()).decode().rstrip('=')
        saved=api('servers',{'requestId':server_id,'name':'isolated-ssh-compose-campus','host':'127.0.0.1','port':ssh_port,'username':'root','location':'CONTROL_HOST','authentication':'PRIVATE_KEY','credential':key.read_text(),'hostKey':host_key,'confirmedFingerprint':fingerprint});assert saved['id']==server_id
        # Fixture-only import: encrypted connection is generated by the real API;
        # installation metadata comes from the actual bootstrap/restore receipt.
        registry_path=state/'servers.json';registry=json.loads(registry_path.read_text());installation=json.loads((root/'installation.json').read_text())
        installation.update(root='/opt/eatwhat-sites/'+server_id,clusterMembership='ACTIVE',currentSha256=args.old_sha)
        registry['servers'][server_id]['installation']=installation;write_json(registry_path,registry)
        release_config=json.loads((root/'deploy/release.json').read_text());release_config.update(docker='/usr/bin/docker',health_timeout_seconds=45);write_json(root/'deploy/release.json',release_config)
        dependencies={name:dc('ps','-q',name).decode().strip() for name in ['postgres','redis']}
        event_log=open(work/'docker-events.jsonl','wb');os.chmod(event_log.name,0o600)
        events=subprocess.Popen([docker,'events','--since',datetime.datetime.now(datetime.timezone.utc).isoformat(),'--filter','type=container','--filter','label=com.docker.compose.project='+project,'--filter','label=com.docker.compose.service=app','--format','{{json .}}'],stdout=event_log,stderr=subprocess.DEVNULL)
        def kill_owned_worker():
            # Only the Python worker holding this fixture's exact lock. Never
            # inspect/kill host processes, another project, or its Docker CLI.
            script="""import json,os,signal,sys
from pathlib import Path
expected=sys.argv[1];matches=[]
for directory in Path('/proc').iterdir():
 if not directory.name.isdecimal():continue
 try:
  name=Path((directory/'cmdline').read_bytes().split(b'\\0')[0].decode()).name
  if name not in ['python','python3']:continue
  if any(os.readlink(fd)==expected for fd in (directory/'fd').iterdir()):matches.append(int(directory.name))
 except (OSError,UnicodeError):pass
assert len(matches)==1
os.kill(matches[0],signal.SIGKILL);print(json.dumps({'workerKilled':True,'exactProjectLockMatches':len(matches)}))
"""
            result=json.loads(command([docker,'exec',ssh_container,'python3','-c',script,str(root/'installation.lock')]))
            assert result=={'workerKilled':True,'exactProjectLockMatches':1}
        def execute(release,interrupt=False):
            plan_id=uid();plan=api('cluster/deployment-plans',{'requestId':plan_id,'expectedEpoch':1,'releaseId':release,'sites':[site]})
            assert plan.get('executionEnabled') is True,plan.get('blockers')
            job=api('cluster/deployment-plans/'+plan_id+'/execute',{})
            job_id=job['id'];seen=set();killed=False;remote_path=root/'private/cluster-deployments'/job_id/'receipt.json'
            def finished():
                nonlocal killed
                if interrupt and not killed and remote_path.exists():
                    value=json.loads(remote_path.read_text())
                    if value.get('stage')=='STARTING' and value.get('mutationStarted') is True:kill_owned_worker();killed=True
                current=api('deployments/'+job_id);seen.add(current.get('stage','UNKNOWN'))
                if current['state'] in ['SUCCEEDED','FAILED','PARTIAL_FAILURE']:return current
                if current['state']=='OUTCOME_UNKNOWN':
                    if interrupt:
                        assert killed
                        return current
                    try:
                        diagnostics=json.loads('['+','.join(command([docker,'exec',ssh_container,'cat','/run/eatwhat-ssh/agent-diagnostics.jsonl']).decode().splitlines())+']')
                        report['safeAgentDiagnostics']=diagnostics[-10:]
                        print('Safe fixture agent diagnostics:',json.dumps(diagnostics[-10:]),flush=True)
                    except Exception:pass
                    try:
                        safe=[json.loads(line) for line in command([docker,'exec',ssh_container,'cat','/run/eatwhat-ssh/prepare-diagnostics.jsonl']).decode().splitlines()]
                        report['safePrepareDiagnostics']=safe[-3:];print('Safe prepare exception locations:',json.dumps(safe[-3:]),flush=True)
                    except Exception:pass
                    try:
                        write_json(work/'private-mount-diagnostics.json',{'checks':[json.loads(line) for line in command([docker,'exec',ssh_container,'cat','/run/eatwhat-ssh/mount-diagnostics.jsonl']).decode().splitlines()]})
                    except Exception:pass
                    raise AssertionError('Remote outcome unknown: '+str([(r.get('state'),r.get('code')) for r in current.get('targets',[])]))
                return None
            result=wait(finished,420)
            remote=json.loads(command([docker,'exec',ssh_container,'cat',str(root/'private/cluster-deployments'/job_id/'receipt.json')]))
            if remote.get('state')=='FAILED':
                try:
                    safe=[json.loads(line) for line in command([docker,'exec',ssh_container,'cat','/run/eatwhat-ssh/worker-diagnostics.jsonl']).decode().splitlines()]
                    report['safeWorkerDiagnostics']=safe[-5:];print('Safe worker exception locations:',json.dumps(safe[-5:]),flush=True)
                except Exception:pass
            if interrupt:
                assert killed and result['state']=='OUTCOME_UNKNOWN' and len(api('cluster/deployment-fences')['active'])==1
                return result,remote
            assert api('cluster/deployment-fences')['active']==[]
            assert remote.get('sealedAfterAuthority') is True
            assert (root/'private/cluster-deployments'/job_id/'database.dump').stat().st_size>0
            assert all(dc('ps','-q',name).decode().strip()==value for name,value in dependencies.items())
            assert sql('SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n')==identity_before and sql('SELECT system_identifier::text FROM pg_control_system()')==system_before
            assert sql("SELECT md5(id::text||':'||nick_name||':'||password_hash) FROM users WHERE role='admin'")==admin_before
            report.setdefault('flows',[]).append({'release':release,'jobState':result['state'],'remoteState':remote['state'],'remoteCode':remote.get('code'),'failureStage':remote.get('failureStage'),'observedStages':sorted(seen),'sealed':True,'databaseBackupBytes':(root/'private/cluster-deployments'/job_id/'database.dump').stat().st_size})
            return result,remote
        print('Executing registered deployment through real admin HTTP and SSH',flush=True)
        good,remote=execute('candidate');assert good['state']=='SUCCEEDED' and remote['state']=='SUCCEEDED'
        assert sha(root/'releases/current.jar')==sha(candidate) and ready(True)
        report['checks']+=['real_admin_preview_execute_registered_encrypted_ssh_binding','real_pinned_key_control_ssh_streams_reviewed_source_and_actual_candidate','real_postgres_custom_backup_and_pg_restore_list','detached_worker_survives_closed_ssh_dispatch_session','actual_container_jar_and_readiness_verified','authority_terminal_and_original_remote_receipt_sealed','postgres_redis_containers_identity_and_administrator_unchanged']
        print('Testing actual failed application startup and old-JAR-only rollback',flush=True)
        bad,remote=execute('intentional-failure');assert bad['state']=='FAILED' and remote['state']=='ROLLED_BACK'
        assert sha(root/'releases/current.jar')==sha(candidate) and ready(True)
        assert json.loads((state/'servers.json').read_text())['servers'][server_id]['installation']['currentSha256']==sha(candidate)
        report['checks']+=['intentional_real_jvm_startup_failure_restores_previous_jar_only','rollback_recreates_only_app_and_proves_readiness_runtime_identity','failed_target_does_not_advance_registered_version','original_database_not_restored_or_reinitialized']
        for kill_recovery in [False,True]:
            print('Interrupting real release; testing explicit admin recovery'+(' with lost client acknowledgement and recovery-worker interruption' if kill_recovery else ''),flush=True)
            interrupted,original=execute('intentional-failure',interrupt=True);parent_id=interrupted['id'];recovery_id=uid()
            wait(lambda:not ready(True),30)
            plan=api('deployments/'+parent_id+'/recovery-plans',{'requestId':recovery_id,'siteId':site})
            assert plan['state']=='PREVIEW_ONLY' and plan['restoreSha256']==sha(candidate) and plan['executionEnabled'] is True
            assert not any(word in json.dumps(plan) for word in ['encryptedConnection','authorityToken','databaseProof','_remoteProof'])
            recovery_body={'restoreSha256':plan['restoreSha256']}
            if kill_recovery:
                # Send a complete real request and deliberately do not consume
                # the response. Query the retained request UUID; never resend.
                body=json.dumps(recovery_body).encode();path='/admin/control/v1/deployment-recovery-plans/'+recovery_id+'/execute'
                headers=f'POST {path} HTTP/1.1\r\nHost: 127.0.0.1:{management_port}\r\nContent-Type: application/json\r\neatwhat-test-token: {auth}\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n'
                with socket.create_connection(('127.0.0.1',management_port),timeout=5) as connection:connection.sendall(headers.encode()+body);connection.shutdown(socket.SHUT_WR)
            else:api('deployment-recovery-plans/'+recovery_id+'/execute',recovery_body)
            killed_restore=False
            def restored():
                nonlocal killed_restore
                receipt=json.loads((root/'private/cluster-deployments'/parent_id/'receipt.json').read_text());attempt=receipt.get('recovery',{})
                # Convergence by read-only runtime proof requires the recreate
                # command to have completed. Killing during RESTORING can
                # interrupt the atomic JAR copy itself: that must stay unknown,
                # not magically finish the restore. Early interruption refusal
                # has separate runner coverage.
                if kill_recovery and not killed_restore and attempt.get('id')==recovery_id and attempt.get('stage')=='VERIFYING' and attempt.get('mutationStarted') is True and attempt.get('state')=='RUNNING':kill_owned_worker();killed_restore=True
                status=api('deployment-recovery-plans/'+recovery_id)
                if status['state'] in ['OUTCOME_UNKNOWN','FAILED']:raise AssertionError('Actual recovery was not verified: '+str(status.get('code')))
                return status if status['state']=='SUCCEEDED' and status.get('parentState')=='FAILED' and not status.get('parentReconciliationRequired') else None
            status=wait(restored,300)
            assert not kill_recovery or killed_restore
            assert sha(root/'releases/current.jar')==sha(candidate) and ready(True) and api('cluster/deployment-fences')['active']==[]
            receipt=json.loads((root/'private/cluster-deployments'/parent_id/'receipt.json').read_text());assert receipt['state']=='ROLLED_BACK' and receipt['sealedAfterAuthority'] is True and receipt['recovery']['state']=='SUCCEEDED'
            application=dc('ps','-q','app').decode().strip();before=json.dumps(receipt,sort_keys=True)
            assert api('deployment-recovery-plans/'+recovery_id+'/execute',recovery_body)['state']=='SUCCEEDED'
            assert dc('ps','-q','app').decode().strip()==application and json.dumps(json.loads((root/'private/cluster-deployments'/parent_id/'receipt.json').read_text()),sort_keys=True)==before
            assert all(dc('ps','-q',name).decode().strip()==value for name,value in dependencies.items())
            assert sql('SELECT row_to_json(n)::text FROM eatwhat_identity.node_state n')==identity_before and sql('SELECT system_identifier::text FROM pg_control_system()')==system_before
            assert sql("SELECT md5(id::text||':'||nick_name||':'||password_hash) FROM users WHERE role='admin'")==admin_before
            report['flows'].append({'release':'interrupted-manual-recovery','jobState':'FAILED','remoteState':'ROLLED_BACK','recoveryState':'SUCCEEDED','sourceWorkerKilled':True,'recoveryWorkerKilled':killed_restore,'recoveryInterruptionStage':'VERIFYING' if killed_restore else None,'clientDidNotReadExecuteAcknowledgement':kill_recovery,'oldApplicationRuntimeVerified':True,'replayDidNotRecreateApp':True,'sealed':True})
        report['checks']+=['real_killed_release_worker_preserves_active_authority_fence','real_admin_recovery_preview_queries_actual_postgres_while_business_http_is_down','explicit_confirmed_old_jar_restore_and_health_identity_verification','real_recovery_worker_interruption_converges_by_readonly_proof_and_sealing','unread_client_execute_acknowledgement_never_causes_automatic_redispatch','same_recovery_uuid_replay_preserves_actual_app_container_and_receipt','recovery_attempt_is_linked_to_original_failed_deployment_history']
        events.terminate();events.wait(5);events=None;event_log.close();event_log=None
        creates=[]
        for line in (work/'docker-events.jsonl').read_text().splitlines():
            if not line:continue
            event=json.loads(line);attributes=event.get('Actor',{}).get('Attributes',{})
            if event.get('Action')=='create' and attributes.get('com.docker.compose.project')==project and attributes.get('com.docker.compose.service')=='app':creates.append(event)
        assert len(creates)==7,'Unexpected extra application recreation or missed real create event'
        report['actualAppContainerCreatesDuringReleaseFlows']=len(creates);report['checks'].append('real_docker_events_prove_exactly_one_new_app_plus_two_each_for_failure_and_two_manual_restore_flows')
        report['status']='PASS';print('Real registered SSH/Compose acceptance passed',flush=True)
    except Exception as failure:
        report.update(status='FAIL',errorType=type(failure).__name__,privateFixture=str(work));raise
    finally:
        if events:events.terminate();events.wait(5)
        if event_log:event_log.close()
        if manager:
            manager.terminate()
            try:manager.wait(10)
            except subprocess.TimeoutExpired:manager.kill();manager.wait(5)
        if compose and Path(compose[-1]).is_file():subprocess.run(compose+['down','--volumes','--remove-orphans'],stdout=log,stderr=log,timeout=90)
        for container in [ssh_container,redis_container]:
            if container:subprocess.run([docker,'rm','-f',container],stdout=log,stderr=log,timeout=30)
        report['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
        drift=[path for path,digest in report['sourceHashes'].items() if sha(ROOT/path)!=digest]
        if drift:report['sourceDrift']=drift;report['status']='SOURCE_DRIFT'
        output=ROOT/'output/control-cluster-release-ssh-compose';output.mkdir(parents=True,exist_ok=True);(output/'verification.json').write_text(json.dumps(report,indent=2)+'\n');log.close()
        if report['status']=='PASS':
            if owned_directory and owned_directory.exists():shutil.rmtree(owned_directory)
            shutil.rmtree(work)

if __name__=='__main__':
    main()

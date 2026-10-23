#!/usr/bin/env python3
"""Actual EatWhat JAR + PostGIS + Redis first-install/recovery acceptance, isolated local Docker only.
The Linux root filesystem checks are tested separately: this macOS fixture adapts ownership and
container IP routing to Docker Desktop; it never connects to a production SSH host.
"""
import datetime, hashlib, importlib.util, io, json, os, shutil, socket, subprocess, sys, tempfile, urllib.request, urllib.error, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('first_install',ROOT/'src/main/resources/control/first-install.py');agent=importlib.util.module_from_spec(spec);spec.loader.exec_module(agent)
payload=json.loads(Path(os.environ['EATWHAT_INSTALL_TEST_PAYLOAD']).read_text());jar=Path(os.environ.get('EATWHAT_INSTALL_TEST_ARTIFACT',ROOT/'target/eatwhat-0.0.1-SNAPSHOT.jar')).resolve();assert hashlib.sha256(jar.read_bytes()).hexdigest()==payload['artifactSha256']
keep_success=os.environ.get('EATWHAT_INSTALL_TEST_KEEP_SUCCESS')=='1'
output_directory=Path(os.environ.get('EATWHAT_INSTALL_TEST_OUTPUT',ROOT/'output/control-servers-first-install'))
image=os.environ['EATWHAT_INSTALL_TEST_JRE_IMAGE'];docker=shutil.which('docker');payload['images']={'app':image,'postgres':'postgis/postgis:16-3.5','redis':'redis:7.4-alpine'}
agent.ENV['DOCKER_HOST']=subprocess.check_output([docker,'context','inspect','--format','{{.Endpoints.docker.Host}}'],text=True).strip()
# Never pull or build: resolve only images already installed for this explicit fixture.
for key,name in payload['images'].items():payload['images'][key]=subprocess.check_output([docker,'image','inspect','--platform','linux/amd64','--format','{{.Id}}',name],text=True).strip()
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
payload['httpPort']=port();restore_port=port();
archive=Path(os.environ['EATWHAT_INSTALL_TEST_ARCHIVE']) if os.environ.get('EATWHAT_INSTALL_TEST_ARCHIVE') else None
if archive:payload.update(artifactBytes=jar.stat().st_size,imageArchiveBytes=archive.stat().st_size,imageArchiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest())
assert agent.project_available(docker,payload['serverId']),'The isolated test project is already occupied; refusing any mutation'
owned_directory=os.environ.get('EATWHAT_INSTALL_TEST_DIRECTORY')
temporary=Path(owned_directory) if owned_directory else Path(tempfile.mkdtemp(prefix='eatwhat-first-install-compose-'))
assert temporary.is_absolute() and not temporary.is_symlink() and temporary.name.startswith('eatwhat-first-install-compose-') and temporary.stat().st_uid==os.getuid() and temporary.stat().st_mode&0o777==0o700 and not list(temporary.iterdir()),'A fixture directory must be a new empty private owned directory'
# macOS's system /var alias is resolved only after checking the requested fresh
# fixture directory; the Linux installer keeps rejecting symlinked ancestors.
temporary=temporary.resolve()
root=temporary/payload['serverId'];project='eatwhat-site-'+payload['serverId'][:8];agent.BASE=temporary
agent.platform.machine=lambda:'x86_64'  # Reviewed amd64 fixture images run under Docker Desktop emulation.
original_root=agent.safe_root;agent.safe_root=lambda server:temporary/str(uuid.UUID(server))
agent.inspect=lambda data:{'protocol':'eatwhat-install-v1','serverId':data['serverId'],'root':str(agent.safe_root(data['serverId'])),'rootAvailable':not agent.safe_root(data['serverId']).exists(),'portAvailable':True,'rootUser':True,'dockerReady':True,'projectAvailable':True,'diskFreeMiB':100000}
original_result=agent.result_path
def fixture_result(data,create=False):
 p=temporary/'.install-results'
 if create:p.mkdir(mode=0o700,exist_ok=True)
 return p/(str(uuid.UUID(data['jobId']))+'.json')
agent.result_path=fixture_result
original_chown=os.chown
# These are synthetic fixture secrets under a mode-700 host parent. The Linux target uses real UID 10001.
def ownership(path,uid,gid):
 pass  # The fixture runs app with the existing host UID; private 600/700 modes are preserved.
os.chown=ownership
original_run=agent.run
recovery_kind=os.environ.get('EATWHAT_INSTALL_TEST_RECOVERY');recovery_mode=recovery_kind in ['1','admin'];injected=False
base=[docker,'compose','--project-name',project,'--env-file',str(root/'deploy/.env'),'-f',str(root/'deploy/compose.json')]
def run(args,**kwargs):
 global injected
 if 'up' in args:
  # Docker Desktop's emulated access(X_OK) reports a bind-mounted 0644 script as
  # executable, while execve rejects it. Use an executable fixture copy only;
  # the reviewed Linux bootstrap keeps its credential-free init script at 0644.
  (root/'deploy/initdb.sh').chmod(0o755)
  p=root/'deploy/compose.json';data=json.loads(p.read_text());data['services']['app']['user']=f'{os.getuid()}:{os.getgid()}'
  if 'restore-check' in data['services']:
   data['services']['restore-check']['user']=f'{os.getuid()}:{os.getgid()}';data['services']['restore-check']['ports']=[f'127.0.0.1:{restore_port}:8080']
  agent.atomic(p,data)
 if recovery_kind=='admin' and not injected and 'up' in args and args[-1]=='app' and json.loads(agent.result_path(payload).read_text()).get('stage')=='ADMIN_INITIALIZATION':
  injected=True;(root/'deploy/initdb.sh').chmod(0o644);raise OSError('Synthetic loss of app startup after administrator initialization')
 if recovery_kind=='1' and not injected and 'up' in args and args[-2:]==['postgres','redis']:
  # Own disposable volume only: emulate an initdb failure after PG_VERSION is
  # written, before the application role/database exist. Restore the reviewed
  # script after the failed task; recovery must repair the retained PG volume.
  injected=True;init=root/'deploy/initdb.sh';reviewed=init.read_bytes();init.write_text('#!/bin/bash\nexit 1\n');init.chmod(0o755)
  try:
   short=list(args);short[short.index('--wait-timeout')+1]='15';return original_run(short,**kwargs)
  finally:init.write_bytes(reviewed);init.chmod(0o644)
 result=original_run(args,**kwargs)
 if 'up' in args and '--wait' in args and args[-2:]==['postgres','redis']:
  command=base+['exec','-T','postgres','psql','-U',payload['database']['POSTGRES_USER'],'-d','postgres','-tAc','SELECT datname FROM pg_database ORDER BY datname']
  diagnostic=subprocess.run(command,env=agent.ENV,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
  if diagnostic.returncode:
   detail=diagnostic.stderr
   for secret in list(payload['database'].values())+[payload['nodeToken']]:detail=detail.replace(secret.encode(),b'[REDACTED]')
   print('Isolated DB diagnostic failed',diagnostic.returncode,detail.decode(errors='replace')[-2000:],flush=True)
  assert diagnostic.returncode==0,'Read-only database diagnostic failed'
  names=diagnostic.stdout.decode().splitlines()
  print('Isolated fixture database names:',names,flush=True)
  assert payload['database']['PG_DB'] in names,'Expected application database missing immediately after dependency startup'
 return result
agent.run=run
original_opener=urllib.request.build_opener
class DesktopOpener:
 def __init__(self,delegate):self.delegate=delegate
 def open(self,request,**kwargs):
  from urllib.parse import urlsplit,urlunsplit
  url=urlsplit(request.full_url)
  if url.hostname!='127.0.0.1' and url.port==8080:
   request=urllib.request.Request(urlunsplit((url.scheme,f'127.0.0.1:{restore_port}',url.path,url.query,url.fragment)),headers=dict(request.header_items()))
  return self.delegate.open(request,**kwargs)
urllib.request.build_opener=lambda *args:DesktopOpener(original_opener(*args))
original_stdin=sys.stdin
report={'scope':'LOCAL_REAL_EATWHAT_COMPOSE_POSTGIS_REDIS','productionDeployment':False,'sshTransport':'NOT_USED','platformAdaptations':['macOS fixture ownership','Docker Desktop bind-mounted init-script executable mode','Docker Desktop local socket and restore-container loopback port','amd64 Docker Desktop emulation'],'checks':[]}
report.update(startedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),artifactSha256=payload['artifactSha256'],bootstrapSha256=hashlib.sha256((ROOT/'src/main/resources/control/first-install.py').read_bytes()).hexdigest())
if recovery_mode:report['recoveryAgentSha256']=hashlib.sha256((ROOT/'src/main/resources/control/first-install-recovery.py').read_bytes()).hexdigest()
try:
 transport=tempfile.TemporaryFile();transport.write(json.dumps(payload).encode()+b'\n')
 with open(jar,'rb') as source:shutil.copyfileobj(source,transport)
 if archive:
  with open(archive,'rb') as source:shutil.copyfileobj(source,transport)
 transport.seek(0);sys.stdin=io.TextIOWrapper(transport);agent.input_header();result=agent.install(payload)
 if recovery_mode:
  assert result['state']=='FAILED' and result['stage']==('ADMIN_INITIALIZATION' if recovery_kind=='admin' else 'DATABASE_REDIS'),result
  rspec=importlib.util.spec_from_file_location('retained_recovery',ROOT/'src/main/resources/control/first-install-recovery.py');recovery=importlib.util.module_from_spec(rspec);rspec.loader.exec_module(recovery);recovery._BOOTSTRAP=agent;recovery.ROOT_UID=os.getuid();recovery.APP_UID=os.getuid();recovery.APP_GID=os.getgid()
  request={key:payload[key] for key in ['serverId','siteId','instanceId','artifactSha256']};request.update(sourceJobId=payload['jobId'],jobId=str(uuid.uuid4()),mode=payload.get('mode','CAMPUS'),initdbSha256=hashlib.sha256(payload['initdb'].encode()).hexdigest())
  for key in ['clusterId','allocationSlot','administrator']:
   if key in payload:request[key]=payload[key]
  preview=recovery.inspect(request);assert preview['eligible'],preview;request['freezeHash']=preview['freezeHash'];before={name:hashlib.sha256((root/name).read_bytes()).hexdigest() for name in ['config/control.token','config/application.properties','releases/current.jar']}
  administrator_before=recovery.sql(base,payload['database'],'eatwhat',"SELECT md5(nick_name||':'||password_hash) FROM public.users WHERE role='admin'") if recovery_kind=='admin' else None
  result=recovery.recover(request);assert result['state']=='SUCCEEDED',result
  if recovery_kind=='admin':
   assert administrator_before==recovery.sql(base,payload['database'],'eatwhat',"SELECT md5(nick_name||':'||password_hash) FROM public.users WHERE role='admin'")
   assert recovery.sql(base,payload['database'],'eatwhat','SELECT count(*) FROM public.users')==('0' if payload.get('mode')=='EXTERNAL' else '1')
  assert before=={name:hashlib.sha256((root/name).read_bytes()).hexdigest() for name in before}
  assert json.loads(agent.result_path(payload).read_text())['state']=='FAILED'
  assert recovery.recover(request)==result
  report['checks']+=[('external_empty_account_table_and_restore_evidence_preserved' if payload.get('mode')=='EXTERNAL' else 'existing_administrator_and_restore_evidence_preserved_without_duplicate_seed') if recovery_kind=='admin' else 'retained_pg_volume_missing_application_database_and_role_recovered','source_failed_receipt_preserved','stable_identity_jar_configuration_token_and_passwords_preserved','recovery_uuid_replay_returns_receipt_without_reexecution','original_database_system_identifier_preserved']
 assert result['state']=='SUCCEEDED',result
 report['checks']+=['actual_eatwhat_jar_first_boot_and_migrations','separate_postgres_application_role_and_database','authenticated_redis','real_backup_restore_and_restored_app_readiness_and_identity','immutable_local_image_ids','running_container_jar_sha256','original_database_preserved']
 assert json.loads((root/'installation.json').read_text())['restoreEvidenceId']=='fresh-install-restore-'+(request['jobId'] if recovery_kind=='1' else payload['jobId'])
 if payload.get('mode')=='EXTERNAL':
  def fixed_sql(statement):return agent.run(base+['exec','-T','postgres','psql','-U',payload['database']['POSTGRES_USER'],'-d','eatwhat','-tAc',statement],timeout=30).decode().strip()
  assert fixed_sql('SELECT count(*) FROM public.users')=='0'
  allocation=json.loads(fixed_sql('SELECT row_to_json(a) FROM eatwhat_identity.allocation a WHERE singleton'))
  assert allocation['cluster_id']==payload['clusterId'] and allocation['slot']==payload['allocationSlot'] and allocation['site_id']==payload['siteId'] and allocation['instance_id']==payload['instanceId']
  req=urllib.request.Request(f"http://127.0.0.1:{payload['httpPort']}/auth/login",data=b'{"username":"unjoined","password":"synthetic-password","device":"WEB"}',headers={'Content-Type':'application/json'})
  try:original_opener(urllib.request.ProxyHandler({})).open(req,timeout=10);raise AssertionError('Unjoined external login must be closed')
  except urllib.error.HTTPError as denied:assert denied.code==503 and json.loads(denied.read())['code']=='SITE_ENROLLMENT_REQUIRED'
  report['checks']+=['external_role_allocation_persisted_by_real_flyway_callback','external_users_remain_empty_no_copied_administrator','business_login_closed_before_membership_join']
 elif 'clusterId' in payload:
  # A bound CAMPUS business installation is also quarantined until genuine JOIN.
  # The independent management process remains responsible for bootstrapping it.
  req=urllib.request.Request(f"http://127.0.0.1:{payload['httpPort']}/auth/login",data=json.dumps({'username':payload['administrator']['username'],'password':'synthetic-password','device':'WEB'}).encode(),headers={'Content-Type':'application/json'})
  try:original_opener(urllib.request.ProxyHandler({})).open(req,timeout=10);raise AssertionError('Unjoined allocated campus business login must be closed')
  except urllib.error.HTTPError as denied:assert denied.code==503 and json.loads(denied.read())['code']=='SITE_ENROLLMENT_REQUIRED'
  assert agent.run(base+['exec','-T','postgres','psql','-U',payload['database']['POSTGRES_USER'],'-d','eatwhat','-tAc',"SELECT count(*) FROM users WHERE role='admin'"],timeout=30).decode().strip()=='1'
  report['checks']+=['bound_campus_administrator_preserved','bound_campus_business_login_closed_until_genuine_join']
 else:
  req=urllib.request.Request(f"http://127.0.0.1:{payload['httpPort']}/auth/login",data=json.dumps({'username':payload['administrator']['username'],'password':'synthetic-password','device':'WEB'}).encode(),headers={'Content-Type':'application/json'})
  with original_opener(urllib.request.ProxyHandler({})).open(req,timeout=10) as r:assert json.loads(r.read())['code']==0
  report['checks']+=['independent_initial_administrator','copied_administrator_can_login_with_existing_password']
 if os.environ.get('EATWHAT_INSTALL_TEST_MEMBER_RECOVERY')=='1':
  assert payload.get('mode')=='EXTERNAL','Member recovery fixture requires an unjoined EXTERNAL installation'
  hook_spec=importlib.util.spec_from_file_location('member_recovery_compose_hook',ROOT/'scripts/verify-member-configuration-recovery-compose.py');hook=importlib.util.module_from_spec(hook_spec);hook_spec.loader.exec_module(hook)
  report['checks']+=hook.verify(root,payload,agent)
  report['memberConfigurationRecovery']={'execution':'SYNCHRONOUS_TASK_METHODS','systemd':'NOT_USED','ssh':'NOT_USED','linuxRootValidation':'ADAPTED_FOR_MACOS','managementHttp':'NOT_USED','membershipJoin':'NOT_EXECUTED'}
 report['siteMode']=payload.get('mode','CAMPUS');report['managementAdmission']='NOT_USED_SYNTHETIC_PAYLOAD_CONFIGURATION_ONLY'
 if archive:report['checks'].append('real_offline_image_archive_transfer_sha256_and_docker_load')
 sys.stdin=io.TextIOWrapper(io.BytesIO(b''));duplicate=agent.install(payload);assert duplicate['state']==('FAILED' if recovery_mode else 'SUCCEEDED');report['checks'].append('duplicate_installation_reads_original_receipt_without_overwrite')
 other=dict(payload,jobId=str(uuid.uuid4()));sys.stdin=io.TextIOWrapper(io.BytesIO(b''));rejected=agent.install(other);assert rejected['state']=='FAILED' and root.exists();report['checks'].append('existing_installation_directory_is_never_overwritten')
 report['status']='PASS'
 if keep_success:
  # Explicit fixture handoff only. The caller owns cleanup of this exact project;
  # no credential, token, database URL or rendered Compose is exported here.
  report['retainedFixture']={'directory':str(temporary),'root':str(root),'project':project,'httpPort':payload['httpPort'],'serverId':payload['serverId'],'siteId':payload['siteId'],'instanceId':payload['instanceId']}
 print('First-install Compose acceptance passed:',len(report['checks']))
except Exception as error:
 report['status']='FAIL';report['errorType']=type(error).__name__;report['privateFixture']=str(temporary)
 try:
  logs=subprocess.check_output(base+['logs','--no-color','--tail','100'],env=agent.ENV,stdin=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  for secret in list(payload['database'].values())+[payload['nodeToken']]:logs=logs.replace(secret.encode(),b'[REDACTED]')
  p=temporary/'failure.log';p.write_bytes(logs);p.chmod(0o600)
 except Exception:pass
 raise
finally:
 sys.stdin=original_stdin;os.chown=original_chown;urllib.request.build_opener=original_opener
 retained=keep_success and report.get('status')=='PASS'
 if not retained:subprocess.run(base+['down','--volumes','--remove-orphans'],env=agent.ENV,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=90)
 output_directory.mkdir(parents=True,exist_ok=True);(output_directory/('recovery-admin-compose-verification.json' if recovery_kind=='admin' else 'recovery-compose-verification.json' if recovery_mode else 'compose-verification.json')).write_text(json.dumps(report,indent=2)+'\n')
 if report['status']=='PASS' and not retained:shutil.rmtree(temporary)

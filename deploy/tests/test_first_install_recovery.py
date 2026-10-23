import copy,hashlib,importlib.util,io,json,os,stat,sys,tempfile,unittest,uuid
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);loaded=importlib.util.module_from_spec(spec);spec.loader.exec_module(loaded);return loaded
class RecoveryInspectionSafety(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.base=Path(self.temp.name);self.b=module('bootstrap_fixture',ROOT/'src/main/resources/control/first-install.py');self.r=module('recovery_fixture',ROOT/'src/main/resources/control/first-install-recovery.py');self.r._BOOTSTRAP=self.b;self.r.ROOT_UID=os.getuid();self.r.APP_UID=os.getuid();self.r.APP_GID=os.getgid();self.b.BASE=self.base;self.server=str(uuid.uuid4());self.job=str(uuid.uuid4());self.root=self.base/self.server;site='server-'+self.server.replace('-','')[:12];instance=str(uuid.uuid4());artifact=b'synthetic inert artifact';self.token='t'*50
  database={'POSTGRES_USER':'fixture_migration','POSTGRES_PASSWORD':'synthetic','PG_DB':'eatwhat','PG_APP_USER':'fixture_app','PG_APP_PASSWORD':'synthetic','REDIS_PASSWORD':'synthetic'}
  props='\n'.join(['app.site.id='+site,'app.site.instance-id='+instance,'app.site.mode=CAMPUS','app.cluster.enabled=false','spring.datasource.url=jdbc:postgresql://postgres:5432/eatwhat','spring.flyway.url=jdbc:postgresql://postgres:5432/eatwhat','spring.datasource.username=fixture_app','spring.datasource.password=synthetic','spring.flyway.user=fixture_migration','spring.flyway.password=synthetic','spring.data.redis.password=synthetic','app.base-url=https://business.example.invalid/api','app.storage.s3.secret-key=private-synthetic-only'])+'\n'
  self.header={'serverId':self.server,'jobId':self.job,'siteId':site,'instanceId':instance,'artifactSha256':hashlib.sha256(artifact).hexdigest(),'httpPort':18090,'properties':props,'nodeToken':self.token,'releaseAgent':'synthetic agent','initdb':'synthetic reviewed initdb','images':{'app':'synthetic-app','postgres':'synthetic-pg','redis':'synthetic-redis'},'database':database}
  self.b.safe_root=lambda server:self.base/str(uuid.UUID(server))
  def result(data,create=False):
   folder=self.base/'.install-results'
   if create:folder.mkdir(mode=0o700,exist_ok=True)
   return folder/(str(uuid.UUID(data['jobId']))+'.json')
  self.b.result_path=result
  with patch.object(self.b,'inspect',return_value={'rootAvailable':True,'portAvailable':True,'rootUser':True,'dockerReady':True,'projectAvailable':True,'diskFreeMiB':100000}),patch.object(self.b.os,'chown',lambda *a:None),patch.object(self.b,'pin_image',return_value='sha256:'+'a'*64),patch.object(self.b,'run',side_effect=OSError('synthetic database startup failure')),patch.object(sys,'stdin',io.TextIOWrapper(io.BytesIO(artifact))):
   receipt=self.b.install(self.header);self.assertEqual('DATABASE_REDIS',receipt['stage']);self.assertEqual('FAILED',receipt['state'])
  path=self.root/'deploy/compose.json';document=json.loads(path.read_text());document['services']['app']['user']=f'{os.getuid()}:{os.getgid()}';path.write_text(json.dumps(document))
  self.request={k:self.header[k] for k in ['serverId','siteId','instanceId','artifactSha256']};self.request.update(sourceJobId=self.job,mode='CAMPUS',initdbSha256=hashlib.sha256(self.header['initdb'].encode()).hexdigest())
  # File-policy tests do not contact Docker. Resource provenance has separate
  # rejection tests below and actual Docker acceptance in the Compose fixture.
  p=patch.object(self.r,'preflight',return_value='/usr/bin/docker');p.start();self.addCleanup(p.stop)
 def tearDown(self):self.temp.cleanup()
 def snapshot(self):
  return {str(p.relative_to(self.base)):(hashlib.sha256(p.read_bytes()).hexdigest(),stat.S_IMODE(p.stat().st_mode),p.stat().st_mtime_ns) for p in self.base.rglob('*') if p.is_file() and not p.is_symlink()}
 def test_read_only_inspection_never_returns_configuration_or_credentials(self):
  before=self.snapshot();reply=self.r.inspect(self.request);self.assertTrue(reply['eligible'],reply);self.assertEqual(before,self.snapshot());text=json.dumps(reply);self.assertNotIn('private-synthetic-only',text);self.assertNotIn(self.token,text);self.assertNotIn('passwordHash',text);self.assertNotIn('database',reply);self.assertEqual('https://business.example.invalid/api',reply['appBaseUrl']);self.assertEqual(64,len(reply['freezeHash']))
 def test_changed_existing_configuration_changes_the_frozen_proof(self):
  before=self.r.inspect(self.request)['freezeHash'];path=self.root/'config/application.properties';path.write_text(path.read_text().replace('private-synthetic-only','changed-synthetic-only'));after=self.r.inspect(self.request)['freezeHash'];self.assertNotEqual(before,after)
 def test_joined_site_cannot_use_first_install_recovery(self):
  path=self.root/'config/application.properties';path.write_text(path.read_text().replace('app.cluster.enabled=false','app.cluster.enabled=true'));reply=self.r.inspect(self.request);self.assertFalse(reply['eligible']);self.assertIn('RECOVERY_JOINED_SITE_FORBIDDEN',reply['blockers'])
 def test_terminal_success_and_unfinished_restore_cleanup_cannot_be_recovered(self):
  path=self.root/'installation.json';original=json.loads(path.read_text());self.b.atomic(path,dict(original,state='SUCCEEDED'));self.assertIn('RECOVERY_INSTALLATION_ALREADY_SUCCEEDED',self.r.inspect(self.request)['blockers']);self.b.atomic(path,original)
  source=self.b.result_path({'serverId':self.server,'jobId':self.job});self.b.atomic(source,dict(original,restoreCleanupVerified=False));self.assertIn('RECOVERY_RESTORE_CLEANUP_REQUIRED',self.r.inspect(self.request)['blockers'])
 def test_repeated_recovery_keeps_verified_ancestor_restore_proof_and_checks_its_identity(self):
  original=json.loads((self.root/'installation.json').read_text());evidence='fresh-install-restore-'+self.job;proof=dict(original,stage='ADMIN_INITIALIZATION',restoreEvidenceId=evidence,restoreCleanupVerified=True);self.b.atomic(self.b.result_path({'serverId':self.server,'jobId':self.job}),proof);self.b.atomic(self.root/'private/first-backup.dump',b'synthetic backup bytes for proof policy only')
  later=str(uuid.uuid4());receipt=dict(proof,protocol=self.r.PROTOCOL,jobId=later,sourceJobId=self.job,originalInstallJobId=self.job,stage='APPLICATION_START');self.b.atomic(self.b.result_path({'serverId':self.server,'jobId':later}),receipt);self.b.atomic(self.root/'installation.json',receipt);request=dict(self.request,sourceJobId=later);reply=self.r.inspect(request);self.assertTrue(reply['eligible'],reply);self.assertEqual(evidence,reply['restoreEvidenceId'])
  self.b.atomic(self.b.result_path({'serverId':self.server,'jobId':self.job}),dict(proof,instanceId=str(uuid.uuid4())));self.assertIn('RECOVERY_RESTORE_EVIDENCE_INVALID',self.r.inspect(request)['blockers'])
 def test_foreign_mounts_and_symlinked_configuration_are_rejected(self):
  path=self.root/'deploy/compose.json';original=path.read_text();document=json.loads(original);document['services']['app']['volumes'].append('/:/host');path.write_text(json.dumps(document));self.assertIn('RECOVERY_UNSAFE_MOUNTS',self.r.inspect(self.request)['blockers']);path.write_text(original)
  folder=self.root/'config';folder.rename(self.root/'config-original');folder.symlink_to(self.root/'config-original');self.assertIn('RECOVERY_UNSAFE_PATH',self.r.inspect(self.request)['blockers'])
 def test_old_unresolved_receipt_is_not_guessed_to_be_recoverable(self):
  for path in [self.root/'installation.json',self.b.result_path({'serverId':self.server,'jobId':self.job})]:
   receipt=json.loads(path.read_text());receipt['state']='INSTALLING';self.b.atomic(path,receipt)
  self.b.result_path({'serverId':self.server,'jobId':self.job}).with_suffix('.lock').unlink();reply=self.r.inspect(self.request);self.assertFalse(reply['eligible']);self.assertIn('RECOVERY_SOURCE_OUTCOME_UNRESOLVED',reply['blockers'])
 def test_unreviewed_initdb_or_changed_jar_cannot_be_resumed(self):
  path=self.root/'deploy/initdb.sh';path.write_text('unexpected code');self.assertIn('RECOVERY_BOOTSTRAP_SCRIPT_UNREVIEWED',self.r.inspect(self.request)['blockers']);path.write_text(self.header['initdb']);path=self.root/'releases/current.jar';path.write_bytes(b'different artifact');self.assertIn('RECOVERY_ARTIFACT_CHANGED',self.r.inspect(self.request)['blockers'])
 def test_malformed_services_and_volumes_are_safely_rejected(self):
  path=self.root/'deploy/compose.json';original=path.read_text()
  for field,value in [('services',{'app':None,'postgres':{},'redis':{}}),('volumes',{'pg_data':None,'redis_data':{}})]:
   document=json.loads(original);document[field]=value;self.b.atomic(path,document);self.assertFalse(self.r.inspect(self.request)['eligible'])
 def test_source_task_lock_and_symlinked_lock_directory_block_recovery(self):
  import fcntl
  lock=self.b.result_path({'serverId':self.server,'jobId':self.job}).with_suffix('.lock')
  with open(lock,'r') as stream:
   fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB);self.assertIn('RECOVERY_REMOTE_TASK_RUNNING',self.r.inspect(self.request)['blockers'])
  folder=self.base/'.project-locks';folder.rename(self.base/'.project-locks-original');folder.symlink_to(self.base/'.project-locks-original');self.assertIn('RECOVERY_UNSAFE_PATH',self.r.inspect(self.request)['blockers'])
 def test_changed_plan_is_rejected_before_docker_or_installation_receipt_changes(self):
  request=dict(self.request,jobId=str(uuid.uuid4()),freezeHash='0'*64);before=self.snapshot()
  with patch.object(self.b,'run') as run:
   with self.assertRaises(self.r.RecoveryRejected) as raised:self.r.recover(request)
   self.assertEqual('RECOVERY_PLAN_CHANGED',raised.exception.code);run.assert_not_called()
  after=self.snapshot();after={key:value for key,value in after.items() if key in before};self.assertEqual(before,after)
 def test_existing_container_cannot_redirect_recovery_into_a_foreign_database_volume(self):
  loaded=self.r.load_installation(self.request);project='eatwhat-site-'+self.server[:8];container='a'*64
  row={'Config':{'Labels':{'com.docker.compose.service':'postgres','com.docker.compose.project.config_files':str(self.root/'deploy/compose.json'),'you.v50to.eatwhat.server-id':self.server}},'Mounts':[{'Type':'volume','Name':'foreign_user_database','Destination':'/var/lib/postgresql/data'}]}
  with patch.object(self.b,'run',side_effect=[(container+'\n').encode(),json.dumps([row]).encode()]):
   with self.assertRaises(self.r.RecoveryRejected) as raised:self.r.verify_resources('/usr/bin/docker',loaded,self.request)
   self.assertEqual('RECOVERY_FOREIGN_DOCKER_RESOURCES',raised.exception.code)
 def test_unowned_existing_network_is_never_adopted(self):
  loaded=self.r.load_installation(self.request);project='eatwhat-site-'+self.server[:8]
  with patch.object(self.b,'run',side_effect=[b'',b'',b'',(project+'_default\n').encode(),b'[{"Labels":{}}]']):
   with self.assertRaises(self.r.RecoveryRejected) as raised:self.r.verify_resources('/usr/bin/docker',loaded,self.request)
   self.assertEqual('RECOVERY_FOREIGN_DOCKER_RESOURCES',raised.exception.code)
 def test_failure_after_acceptance_preserves_original_receipt_and_configuration(self):
  request=dict(self.request,jobId=str(uuid.uuid4()),freezeHash=self.r.inspect(self.request)['freezeHash']);source=self.b.result_path({'serverId':self.server,'jobId':self.job});before=source.read_bytes();config=(self.root/'config/application.properties').read_bytes()
  with patch.object(self.b,'run',side_effect=OSError('synthetic stop failure')):
   result=self.r.recover(request)
  self.assertEqual('FAILED',result['state']);self.assertEqual('RECOVERY_FAILED_PRESERVED',result['code']);self.assertEqual(before,source.read_bytes());self.assertEqual(config,(self.root/'config/application.properties').read_bytes());self.assertEqual(result,self.r.recovery_status(request));self.assertEqual(result,self.r.recover(request))
 def test_unjoined_external_allocation_is_preserved_and_cannot_be_changed(self):
  binding={'mode':'EXTERNAL','clusterId':str(uuid.uuid4()),'allocationSlot':2};self.request.update(binding)
  for path in [self.root/'installation.json',self.b.result_path({'serverId':self.server,'jobId':self.job})]:
   receipt=json.loads(path.read_text());receipt.update(binding);self.b.atomic(path,receipt)
  path=self.root/'config/application.properties';path.write_text(path.read_text().replace('app.site.mode=CAMPUS','app.site.mode=EXTERNAL')+'\napp.site.cluster-id='+binding['clusterId']+'\napp.site.allocation-slot=2\napp.control.enrollment-required=true\n')
  reply=self.r.inspect(self.request);self.assertTrue(reply['eligible'],reply);self.assertEqual('EXTERNAL',reply['mode']);self.assertEqual(2,reply['allocationSlot']);self.assertEqual('AUTHORITY_ACCOUNTS_AFTER_JOIN',reply['administratorAction'])
  self.assertFalse(self.r.inspect(dict(self.request,allocationSlot=3))['eligible']);self.assertFalse(self.r.inspect(dict(self.request,administrator={'username':'unexpected'}))['eligible'])
if __name__=='__main__':unittest.main()

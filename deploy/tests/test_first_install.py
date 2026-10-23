import importlib.util, hashlib, io, json, stat, sys, tempfile, unittest, uuid
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('first',Path(__file__).resolve().parents[2]/'src/main/resources/control/first-install.py');agent=importlib.util.module_from_spec(spec);spec.loader.exec_module(agent)
class FirstInstallSafety(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.base=Path(self.temp.name);self.server=str(uuid.uuid4());self.job=str(uuid.uuid4());self.root=self.base/self.server;self.artifact=b'synthetic inert artifact';self.header={'serverId':self.server,'jobId':self.job,'siteId':'server-'+self.server.replace('-','')[:12],'instanceId':str(uuid.uuid4()),'artifactSha256':hashlib.sha256(self.artifact).hexdigest(),'httpPort':18090,'installDocker':False,'properties':'private-fixture-config','nodeToken':'synthetic-node-token','releaseAgent':'synthetic agent','initdb':'synthetic initdb','images':{'app':'synthetic-image'},'database':{'POSTGRES_USER':'fixture_migration','POSTGRES_PASSWORD':'synthetic','PG_DB':'eatwhat','PG_APP_USER':'fixture_app','PG_APP_PASSWORD':'synthetic','REDIS_PASSWORD':'synthetic'}}
  self.patches=[patch.object(agent,'BASE',self.base),patch.object(agent,'safe_root',lambda server:self.base/str(uuid.UUID(server))),patch.object(agent,'result_path',self.result_path),patch.object(agent.os,'chown',lambda *args:None),patch.object(agent,'inspect',lambda data:{'rootAvailable':not self.root.exists(),'portAvailable':True,'rootUser':True,'dockerReady':True,'projectAvailable':True,'diskFreeMiB':100000})]
  for p in self.patches:p.start()
 def result_path(self,data,create=False):
  folder=self.base/'.results'
  if create:folder.mkdir(mode=0o700,exist_ok=True)
  return folder/(str(uuid.UUID(data['jobId']))+'.json')
 def tearDown(self):
  for p in reversed(self.patches):p.stop()
  self.temp.cleanup()
 def install(self):
  with patch.object(sys,'stdin',io.TextIOWrapper(io.BytesIO(self.artifact))):return agent.install(self.header)
 def test_image_failure_cleans_only_its_new_directory_and_leaves_a_terminal_receipt(self):
  with patch.object(agent,'pin_image',side_effect=OSError('synthetic unavailable image')) as pull,patch.object(agent,'run') as run:
   reply=self.install();self.assertEqual('FAILED',reply['state']);self.assertTrue(reply['retryable']);self.assertFalse(self.root.exists());self.assertEqual('FAILED',agent.status(self.header)['state']);self.assertNotIn('private-fixture-config',json.dumps(reply));self.assertEqual('FAILED',self.install()['state']);pull.assert_called_once();run.assert_not_called()
 def test_preexisting_directory_and_user_data_are_never_deleted(self):
  self.root.mkdir();(self.root/'keep.txt').write_text('preserve this')
  with patch.object(agent,'run') as run:
   reply=self.install();self.assertEqual('FIRST_INSTALL_PRECONDITION_CHANGED',reply['code']);self.assertFalse(reply['retryable']);self.assertEqual('preserve this',(self.root/'keep.txt').read_text());run.assert_not_called()
 def test_failure_after_database_start_preserves_files_and_never_reports_retryable(self):
  self.header['images']={'app':'synthetic-app','postgres':'synthetic-postgres','redis':'synthetic-redis'}
  with patch.object(agent,'pin_image',return_value='sha256:'+'a'*64),patch.object(agent,'run',side_effect=OSError('synthetic failure while starting database')):
   reply=self.install();self.assertEqual('FAILED',reply['state']);self.assertNotEqual(True,reply.get('retryable'));self.assertTrue((self.root/'releases/current.jar').exists());self.assertEqual('DATABASE_REDIS',reply['stage']);self.assertEqual('FAILED',agent.status(self.header)['state'])
   self.assertEqual(0o644,stat.S_IMODE((self.root/'deploy/initdb.sh').stat().st_mode))
   for name in ['config/application.properties','config/control.token','deploy/compose.json','releases/current.jar']:
    self.assertEqual(0o600,stat.S_IMODE((self.root/name).stat().st_mode),name)
   self.assertEqual(0o700,stat.S_IMODE((self.root/'deploy').stat().st_mode))
 def test_wrong_artifact_is_rejected_before_docker_or_system_package_mutations(self):
  self.header['artifactSha256']='0'*64
  with patch.object(agent,'pin_image') as image,patch.object(agent,'install_docker') as packages:
   reply=self.install();self.assertEqual('FAILED',reply['state']);self.assertEqual('ARTIFACT_VERIFY',reply['stage']);self.assertFalse(self.root.exists());image.assert_not_called();packages.assert_not_called()
 def test_project_collision_is_rejected_before_artifact_or_data_mutations(self):
  with patch.object(agent,'inspect',return_value={'rootAvailable':True,'portAvailable':True,'rootUser':True,'dockerReady':True,'projectAvailable':False,'diskFreeMiB':100000}),patch.object(agent,'run') as run:
   reply=self.install();self.assertEqual('FIRST_INSTALL_PRECONDITION_CHANGED',reply['code']);self.assertFalse(self.root.exists());run.assert_not_called()
 def test_project_checks_find_labelled_and_unlabelled_foreign_resources(self):
  project='eatwhat-site-'+self.server[:8]
  with patch.object(agent,'run',return_value=b'') as run:self.assertTrue(agent.project_available('/usr/bin/docker',self.server));self.assertEqual(5,run.call_count)
  with patch.object(agent,'run',side_effect=[b'',b'foreign-volume\n']):self.assertFalse(agent.project_available('/usr/bin/docker',self.server))
  with patch.object(agent,'run',side_effect=[b'',b'',b'',(project+'_pg_data\n').encode(),b'']):self.assertFalse(agent.project_available('/usr/bin/docker',self.server))
  with patch.object(agent,'run',side_effect=[b'',b'',b'',b'',(project+'_default\n').encode()]):self.assertFalse(agent.project_available('/usr/bin/docker',self.server))
 def test_live_task_lock_prevents_concurrent_install_and_unknown_is_not_guessed(self):
  path=self.result_path(self.header,True);agent.atomic(path,dict(self.header,state='INSTALLING',stage='DATABASE_REDIS'));lock=path.with_suffix('.lock');lock.touch(mode=0o600)
  import fcntl
  with open(lock,'w') as stream:
   fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB);self.assertEqual('HELD',agent.lock_state(self.header));self.assertEqual('INSTALLING',agent.status(self.header)['state']);self.assertRaises(agent.Rejected,self.install)
  self.root.mkdir();self.assertEqual('FAILED',agent.status(self.header)['state']);self.assertEqual('FIRST_INSTALL_INTERRUPTED_PRESERVED',agent.status(self.header)['code']);self.assertFalse(agent.status(self.header)['retryable']);self.assertEqual('INSTALLING',json.loads(path.read_text())['state'])
  lock.unlink();self.assertEqual('INSTALLING',agent.status(self.header)['state'])
 def test_positive_unused_lock_proof_is_distinct_from_unsupported_old_protocol(self):
  self.assertEqual('UNSUPPORTED',agent.status(self.header)['lockProof']);path=self.result_path(self.header,True).with_suffix('.lock');path.touch(mode=0o600);reply=agent.status(self.header);self.assertEqual('NOT_STARTED',reply['state']);self.assertEqual('RELEASED',reply['lockProof']);self.assertTrue(reply['noInstallationDirectory'])
 def test_completed_root_receipt_wins_after_crash_between_terminal_writes(self):
  path=self.result_path(self.header,True);receipt=dict(self.header,protocol='eatwhat-install-v1',root=str(self.root),state='INSTALLING',stage='ADMIN_INITIALIZATION');agent.atomic(path,receipt);self.root.mkdir();path.with_suffix('.lock').touch(mode=0o600)
  terminal=dict(receipt,state='SUCCEEDED',stage='COMPLETE');agent.atomic(self.root/'installation.json',terminal)
  self.assertEqual('SUCCEEDED',agent.status(self.header)['state']);self.assertEqual('INSTALLING',json.loads(path.read_text())['state'])
  agent.atomic(self.root/'installation.json',dict(terminal,artifactSha256='f'*64));self.assertEqual('FIRST_INSTALL_INTERRUPTED_PRESERVED',agent.status(self.header)['code'])
 def test_project_lock_serializes_different_servers_with_the_same_prefix(self):
  directory=self.base/'.project-locks';directory.mkdir(mode=0o700);lock=directory/('eatwhat-site-'+self.server[:8]+'.lock');fd=agent.acquire_lock(lock)
  try:
   second=dict(self.header,serverId=self.server[:8]+str(uuid.uuid4())[8:],jobId=str(uuid.uuid4()))
   with patch.object(agent,'run') as run,patch.object(sys,'stdin',io.TextIOWrapper(io.BytesIO(self.artifact))):self.assertRaises(agent.Rejected,lambda:agent.install(second));run.assert_not_called()
   self.assertFalse((self.base/second['serverId']).exists());self.assertEqual('RELEASED',agent.lock_state(second))
  finally:agent.os.close(fd)
 def test_external_identity_requires_a_matching_slot_and_no_copied_administrator(self):
  cluster=str(uuid.uuid4());self.assertEqual({'mode':'EXTERNAL','clusterId':cluster,'allocationSlot':1},agent.installation_identity({'mode':'EXTERNAL','clusterId':cluster,'allocationSlot':1}))
  for data in [{'mode':'EXTERNAL'},{'mode':'EXTERNAL','clusterId':cluster,'allocationSlot':0},{'mode':'CAMPUS','clusterId':cluster,'allocationSlot':2},{'mode':'EXTERNAL','clusterId':cluster,'allocationSlot':True},{'mode':'EXTERNAL','clusterId':cluster,'allocationSlot':1,'administrator':{'username':'admin'}}]:
   self.assertRaises(agent.Rejected,lambda:agent.installation_identity(data))
 def test_failed_external_install_receipt_retains_its_identity(self):
  self.header.update(mode='EXTERNAL',clusterId=str(uuid.uuid4()),allocationSlot=2,images={'app':'synthetic-app','postgres':'synthetic-postgres','redis':'synthetic-redis'})
  with patch.object(agent,'pin_image',return_value='sha256:'+'a'*64),patch.object(agent,'run',side_effect=OSError('synthetic database startup failure')):
   reply=self.install();self.assertEqual('FAILED',reply['state']);self.assertEqual('EXTERNAL',reply['mode']);self.assertEqual(self.header['clusterId'],reply['clusterId']);self.assertEqual(2,reply['allocationSlot']);self.assertEqual('EXTERNAL',agent.status(self.header)['mode'])
if __name__=='__main__':unittest.main()

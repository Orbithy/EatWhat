#!/usr/bin/env python3
"""Pure local fixtures for the fixed authority agent; no Docker, SSH or systemd effects."""
import copy, importlib.util, json, os, stat, tempfile, unittest, uuid
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('authority_agent',Path(__file__).resolve().parents[1]/'src/main/resources/control/cluster-enroll.py');a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
class AgentTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name).resolve();self.control=self.root/'control';self.business=self.root/'sites'/str(uuid.uuid4())
  for p in [self.control/'config',self.control/'state',self.control/'releases',self.control/'infra',self.business/'config',self.business/'releases',self.business/'deploy']:p.mkdir(parents=True,exist_ok=True)
  self.h={'id':str(uuid.uuid4()),'serverId':self.business.name,'siteId':'installed-campus','instanceId':str(uuid.uuid4()),'clusterId':str(uuid.uuid4()),'httpPort':18090,'databasePort':25432,'_actor':'123'}
  cp='app.control.servers.cluster-enrollment-enabled=true\nspring.datasource.url=jdbc\\:postgresql\\://127.0.0.1\\:15432/eatwhat\napp.site.cluster-id='+self.h['clusterId']+'\n'
  bp='app.site.id=installed-campus\napp.site.mode=CAMPUS\napp.site.instance-id='+self.h['instanceId']+'\napp.cluster.enabled=false\n'
  self.write(self.control/'config/application.properties',cp);self.write(self.business/'config/application.properties',bp)
  for p in [self.control/'releases/eatwhat.jar',self.business/'releases/current.jar']:self.write(p,'synthetic-inert-jar')
  (self.control/'releases/eatwhat.jar').chmod(0o640)
  self.dc={'services':{'app':{'ports':['127.0.0.1:18090:8080']},'postgres':{'environment':{'PG_DB':'eatwhat'}}}};self.write(self.business/'deploy/compose.json',json.dumps(self.dc))
  user={'id':123,'nick_name':'fixture','password_hash':'private-synthetic','role':'admin','banned':False}
  self.old={'node':{'site_id':'old-campus'},'allocation':{'cluster_id':self.h['clusterId']},'state':{'epoch':0,'cluster_id':self.h['clusterId']},'members':0,'changes':0,'outbox':0,'inbox':0,'consents':0,'handoffs':0,'controllers':1,'users':[user],'credentials':[],'credentialChanges':[],'security':[]}
  self.new=copy.deepcopy(self.old);self.new.update(node={'site_id':'installed-campus','instance_id':self.h['instanceId'],'mode':'CAMPUS'},allocation=None,state=None,controllers=0)
  def fixture_directory(h,create=False):
   p=self.control/'authority-jobs'/h['id']
   if create:p.mkdir(parents=True,mode=0o700)
   return p
  self.patches=[patch.object(a,'directory',side_effect=fixture_directory),patch.object(a,'CONTROL',self.control),patch.object(a,'BASE',self.root/'sites'),patch.object(a,'validated',return_value=self.business),patch.object(a,'oldcompose',return_value=['old']),patch.object(a,'compose',return_value=['business']),patch.object(a,'snapshot',side_effect=lambda cmd:copy.deepcopy(self.old if cmd==['old'] else self.new)),patch.object(a,'sql',side_effect=lambda cmd,q:'[]' if 'json_agg(tablename)' in q else '0')]
  for p in self.patches:p.start()
 def tearDown(self):
  for p in reversed(self.patches):p.stop()
  self.temp.cleanup()
 def write(self,p,v):p.write_text(v);p.chmod(0o600)
 def test_preflight_is_stable_and_does_not_emit_secrets(self):
  r=a.inspect(self.h);self.assertTrue(r['ready']);self.assertEqual(r,a.inspect(self.h));self.assertNotIn('private-synthetic',json.dumps(r))
 def test_changed_config_invalidates_frozen_hash(self):
  h=a.inspect(self.h)['preflightHash'];p=self.control/'config/application.properties';self.write(p,p.read_text()+'# changed\n');self.assertNotEqual(h,a.inspect(self.h)['preflightHash'])
 def test_mismatched_admin_is_rejected(self):
  self.new['users'][0]['password_hash']='different';self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_admin_role_and_actor_are_enforced(self):
  self.h['_actor']='124';self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_existing_cluster_is_rejected(self):
  self.old['state']['epoch']=1;self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_existing_member_or_pending_replay_is_rejected(self):
  for field in ['members','changes','outbox','inbox','consents','handoffs']:
   self.old[field]=1;self.assertRaises(a.Rejected,a.inspect,self.h);self.old[field]=0
 def test_second_controller_is_rejected(self):
  self.old['controllers']=2;self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_preexisting_business_allocation_is_not_overwritten(self):
  self.new['allocation']={'cluster_id':str(uuid.uuid4())};self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_exact_prebound_quarantined_campus_allocation_is_preserved(self):
  self.new['allocation']={'cluster_id':self.h['clusterId'],'site_id':self.h['siteId'],'instance_id':self.h['instanceId'],'slot':0}
  p=self.business/'config/application.properties';self.write(p,p.read_text()+'app.site.cluster-id='+self.h['clusterId']+'\napp.site.allocation-slot=0\napp.control.enrollment-required=true\n')
  before=copy.deepcopy(self.new['allocation']);self.assertTrue(a.inspect(self.h)['ready']);self.assertEqual(before,self.new['allocation'])
  for key,value in [('cluster_id',str(uuid.uuid4())),('instance_id',str(uuid.uuid4())),('site_id','different'),('slot',1)]:
   self.new['allocation']=dict(before,**{key:value});self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_prebound_allocation_requires_business_configuration_proof(self):
  self.new['allocation']={'cluster_id':self.h['clusterId'],'site_id':self.h['siteId'],'instance_id':self.h['instanceId'],'slot':0}
  self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_changed_site_is_rejected(self):
  self.new['node']['instance_id']=str(uuid.uuid4());self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_public_postgres_binding_is_rejected(self):
  self.dc['services']['postgres']['ports']=['0.0.0.0:5432:5432'];self.write(self.business/'deploy/compose.json',json.dumps(self.dc));self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_distinct_artifact_is_rejected(self):
  self.write(self.business/'releases/current.jar','other');self.assertRaises(a.Rejected,a.inspect,self.h)
 def test_dispatch_detaches_once_and_preserves_receipt(self):
  h=dict(self.h,_preflightHash=a.inspect(self.h)['preflightHash'],agent='synthetic inert code')
  with patch.object(a,'run') as run:
   first=a.dispatch(h);second=a.dispatch(h);self.assertEqual(first,second);self.assertEqual(run.call_count,1);args=run.call_args.args[0];self.assertEqual(args[0],'systemd-run');self.assertIn('--no-block',args);self.assertNotIn('private-synthetic',' '.join(args))
  self.assertNotIn('agent',json.loads((a.directory(h)/'input.json').read_text()));self.assertEqual(stat.S_IMODE((a.directory(h)/'receipt.json').stat().st_mode),0o600)
 def test_stale_plan_has_no_dispatch_effect(self):
  h=dict(self.h,_preflightHash='0'*64,agent='inert')
  with patch.object(a,'run') as run:self.assertEqual('REJECTED',a.dispatch(h)['state']);run.assert_not_called()
  self.assertFalse(a.directory(h).exists())
 def test_java_properties_edit_preserves_secret_escaping(self):
  result=a.edited('password=old\nkeep=value\n',{'password':'a:b=c\\d\n'}).decode();self.assertIn('password=a\\:b\\=c\\\\d\\n',result);self.assertIn('keep=value',result);self.assertNotIn('password=old',result)
 def test_symlink_configuration_is_rejected(self):
  p=self.business/'config/application.properties';p.unlink();p.symlink_to(self.control/'config/application.properties');self.assertRaises(a.Rejected,a.inspect,self.h)
if __name__=='__main__':unittest.main()

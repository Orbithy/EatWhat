#!/usr/bin/env python3
"""Unit checks only. Runtime/authority observations are simulated, never SSH acceptance."""
import base64, copy, hashlib, importlib.util, json, os, tempfile, unittest, uuid
from pathlib import Path
from unittest.mock import Mock, patch

REPO=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('cluster_release_core',REPO/'src/main/resources/control/cluster-release-core.py')
core=importlib.util.module_from_spec(spec);spec.loader.exec_module(core)

class CoreChecks(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='eatwhat-cluster-release-unit-');self.base=Path(self.temp.name).resolve();self.base.chmod(0o700)
        self.header={'id':str(uuid.uuid4()),'serverId':str(uuid.uuid4()),'siteId':'fixture','instanceId':str(uuid.uuid4()),'clusterId':str(uuid.uuid4()),'epoch':3,'databaseFingerprint':'f'*32,'artifactSha256':'b'*64,'manifestHash':'c'*64,'restoreEvidenceId':'synthetic-reviewed-restore','authorityOrigin':'https://authority.example.invalid','authorityToken':'x'*43}
        self.root=self.base/self.header['serverId'];self.root.mkdir(mode=0o700)
        for name in ['deploy','releases','private','config','app-state']:(self.root/name).mkdir(mode=0o700)
        self.write('releases/current.jar',b'synthetic-running-artifact');self.header['fromSha256']=hashlib.sha256(b'synthetic-running-artifact').hexdigest()
        receipt={k:self.header[k] for k in ['serverId','siteId','instanceId']};receipt.update(protocol='eatwhat-install-v1',state='SUCCEEDED',root=str(self.root),httpPort=18090);self.write('installation.json',receipt)
        self.write('installation.lock',b'');self.write('config/control.token',b'n'*43)
        self.write('config/application.properties',('\n'.join(k+'='+str(v) for k,v in {'app.site.id':'fixture','app.site.instance-id':self.header['instanceId'],'app.site.cluster-id':self.header['clusterId'],'app.site.mode':'CAMPUS','app.site.allocation-slot':0,'app.cluster.enabled':'true','app.control.node-enabled':'true'}.items())+'\n').encode())
        self.write('deploy/.env',('APP_JAR='+str(self.root/'releases/current.jar')+'\n').encode())
        self.config={'site_id':'fixture','instance_id':self.header['instanceId'],'project':'eatwhat-site-'+self.header['serverId'][:8],'active_jar':str(self.root/'releases/current.jar'),'compose_file':str(self.root/'deploy/compose.json'),'env_file':str(self.root/'deploy/.env'),'state_directory':str(self.root/'private/deployments'),'node_token_file':str(self.root/'config/control.token'),'origin':'http://127.0.0.1:18090','docker':'/usr/bin/docker','restore_evidence_id':self.header['restoreEvidenceId']};self.write('deploy/release.json',self.config)
        common={'image':'sha256:'+'d'*64,'pull_policy':'never'}
        self.compose={'services':{'app':common|{'user':f'{os.getuid()}:{os.getgid()}','read_only':True,'entrypoint':['java'],'command':['-Xms64m','-Xmx384m','-jar','/app/app.jar','--spring.config.location=file:/config/application.properties','--spring.config.additional-location=','--spring.config.import='],'tmpfs':['/tmp'],'volumes':['${APP_JAR}:/app/app.jar:ro',str(self.root/'config')+':/config:ro',str(self.root/'app-state')+':/state'],'ports':['127.0.0.1:18090:8080']},'postgres':common|{'volumes':['pg_data:/var/lib/postgresql/data',str(self.root/'deploy/initdb.sh')+':/docker-entrypoint-initdb.d/20-create-application-db.sh:ro'],'ports':['127.0.0.1:25432:5432']},'redis':common|{'volumes':['redis_data:/data']}},'volumes':{'pg_data':{},'redis_data':{}}};self.write('deploy/compose.json',self.compose)
        # Non-root macOS fixture adaptation only. Production checks retain Linux,
        # root/app ownership and trusted /opt parents; no Compose/SSH is executed.
        self.patches=[patch.object(core,'BASE',self.base),patch.object(core,'ROOT_UID',os.getuid()),patch.object(core,'APP_UID',os.getuid()),patch.object(core,'APP_GID',os.getgid()),patch.object(core.sys,'platform','linux'),patch.object(core,'trusted_ancestors',lambda root:None),patch.object(core,'RELEASE_SOURCE',base64.b64encode((REPO/'deploy/remote-release.py').read_bytes()).decode())]
        for p in self.patches:p.start()

    def tearDown(self):
        for p in reversed(self.patches):p.stop()
        self.temp.cleanup()

    def write(self,name,value):
        path=self.root/name;path.write_bytes(value if isinstance(value,bytes) else json.dumps(value).encode());path.chmod(0o600)

    def context(self):
        c=core.ReleaseContext(copy.deepcopy(self.header));c.agent.runtime_sha=Mock(return_value=self.header['fromSha256']);c.agent.health=Mock();c.agent.request=Mock(return_value={k:self.header[k] for k in ['siteId','instanceId','clusterId','databaseFingerprint']}|{'protocolVersion':1,'dataPlaneReady':True,'mode':'CAMPUS','allocationSlot':0,'membership':{'epoch':3,'state':'ACTIVE','pending_change':None}});return c

    def proof(self,*args):
        return {k:self.header[k] for k in ['id','clusterId','epoch','manifestHash']}|{'protocol':'eatwhat-deployment-fence-v1','state':'ACTIVE','authorized':True,'dispatchAuthorized':True,'targets':[{k:self.header[k] for k in ['siteId','instanceId','databaseFingerprint']}]}

    def test_prepare_is_read_only_and_verifies_exact_scoped_authority(self):
        before={p.relative_to(self.root):(p.read_bytes(),p.stat().st_mtime_ns) for p in self.root.rglob('*') if p.is_file()};c=self.context();fetch=Mock(side_effect=self.proof);result=c.prepare(fetch);self.assertTrue(result['readinessVerified']);self.assertRegex(result['configurationProof'],r'^[a-f0-9]{64}$');self.assertFalse((self.root/'private/deployments').exists());self.assertEqual(before,{p.relative_to(self.root):(p.read_bytes(),p.stat().st_mtime_ns) for p in self.root.rglob('*') if p.is_file()});fetch.assert_called_once_with(self.header['authorityOrigin']+'/internal/deployment/v1/fences/'+self.header['id']+'?manifest='+self.header['manifestHash'],{'X-Deployment-Token':self.header['authorityToken']})

    def test_authority_wrong_identity_terminal_and_unauthorized_fail_closed(self):
        for key,value in [('id',str(uuid.uuid4())),('epoch',4),('clusterId',str(uuid.uuid4())),('manifestHash','e'*64),('targets',[]),('state','SUCCEEDED'),('authorized',False),('dispatchAuthorized',False)]:
            with self.subTest(key=key):
                proof=self.proof();proof[key]=value
                with self.assertRaises(core.ReleaseRejected):self.context().prepare(lambda *args:proof)

    def test_duplicate_target_is_not_an_authority_binding(self):
        proof=self.proof();proof['targets']*=2
        with self.assertRaisesRegex(core.ReleaseRejected,''):core.verify_authority(self.header,lambda *args:proof)

    def test_missing_capability_and_unsafe_callback_origin_are_rejected(self):
        for value in ['http://remote.example.invalid','https://user:secret@host.invalid','https://host.invalid/path','https://host.invalid?query=1','https://host.invalid:0','https://host.invalid:65536']:
            with self.subTest(value=value),self.assertRaises(core.ReleaseRejected):core.verify_authority(self.header|{'authorityOrigin':value},self.proof)
        with self.assertRaises(core.ReleaseRejected):core.verify_authority(self.header|{'authorityToken':None},self.proof)

    def test_actual_bytes_running_bytes_membership_and_fingerprint_must_match(self):
        c=self.context();c.agent.runtime_sha.return_value='e'*64
        with self.assertRaises(core.ReleaseRejected):c.prepare(self.proof)
        for key,value in [('databaseFingerprint','e'*32),('allocationSlot',7),('mode','EXTERNAL'),('dataPlaneReady',False),('protocolVersion',None),('membership',{'state':'ACTIVE','epoch':3}),('membership',{'state':'ACTIVE','epoch':2}),('membership',{'state':'ACTIVE','epoch':3,'pending_change':str(uuid.uuid4())})]:
            c=self.context();c.agent.request.return_value[key]=value
            with self.subTest(key=key),self.assertRaises(core.ReleaseRejected):c.prepare(self.proof)
        self.write('releases/current.jar',b'changed-on-host')
        with self.assertRaises(core.ReleaseRejected):self.context().prepare(self.proof)

    def test_private_config_changes_invalidate_the_frozen_configuration_proof(self):
        original=self.context().configuration_proof;self.write('config/application.properties',(self.root/'config/application.properties').read_bytes()+b'app.extra=changed\n');self.header['configurationProof']=original
        with self.assertRaises(core.ReleaseRejected):self.context().prepare(self.proof)
        self.assertNotEqual(original,self.context().configuration_proof)

    def test_unsafe_compose_never_constructs_an_execution_context(self):
        mutations=[lambda v:v['services']['app'].update(privileged=True),lambda v:v['services']['app'].update(volumes=['/var/run/docker.sock:/var/run/docker.sock']),lambda v:v['volumes'].update(pg_data={'external':True}),lambda v:v['services']['postgres'].update(ports=['0.0.0.0:5432:5432']),lambda v:v['services']['app'].update(image='latest'),lambda v:v['services'].update(other={}),lambda v:v['services']['app'].update(environment={'JAVA_TOOL_OPTIONS':'-agentlib:anything'})]
        for mutation in mutations:
            value=copy.deepcopy(self.compose);mutation(value);self.write('deploy/compose.json',value)
            with self.assertRaises(core.ReleaseRejected):core.ReleaseContext(self.header)

    def test_no_follow_private_modes_and_duplicate_properties(self):
        token=self.root/'config/control.token';token.chmod(0o644)
        with self.assertRaises(core.ReleaseRejected):core.ReleaseContext(self.header)
        token.chmod(0o600);token.unlink();token.symlink_to(self.root/'releases/current.jar')
        with self.assertRaises(core.ReleaseRejected):core.ReleaseContext(self.header)
        with self.assertRaises(core.ReleaseRejected):core.properties(b'app.cluster.enabled=true\napp.cluster.enabled=false\n')

    def test_project_lock_requires_existing_file_and_refuses_concurrent_mutations(self):
        c=self.context()
        with c.project_lock():
            with self.assertRaises(core.ReleaseRejected):c.prepare(self.proof)
        self.assertTrue(c.prepare(self.proof)['readinessVerified']);(self.root/'installation.lock').unlink()
        with self.assertRaises(core.ReleaseRejected):core.ReleaseContext(self.header)

    def test_recovery_database_proves_actual_system_oid_allocation_and_current_local_membership(self):
        self.compose['services']['postgres']['environment']={'POSTGRES_USER':'fixture','PG_DB':'eatwhat'};self.write('deploy/compose.json',self.compose)
        self.header['databaseFingerprint']=hashlib.md5(b'123:456').hexdigest()
        proof={'systemIdentifier':'123','databaseOid':'456','databaseName':'eatwhat','node':{'site_id':'fixture','instance_id':self.header['instanceId'],'mode':'CAMPUS','database_name':'eatwhat','database_oid':456,'cluster_identifier':'123'},'allocation':{'site_id':'fixture','instance_id':self.header['instanceId'],'cluster_id':self.header['clusterId'],'slot':0},'local':{'site_id':'fixture','instance_id':self.header['instanceId'],'cluster_id':self.header['clusterId'],'epoch':3,'state':'ACTIVE','pending_change':None}}
        context=self.context();context.agent.command=Mock(return_value=json.dumps(proof).encode());self.assertEqual(self.header['databaseFingerprint'],context.recovery_database()['databaseFingerprint']);arguments=context.agent.command.call_args.args[0];self.assertIn('--no-psqlrc',arguments);self.assertIn('--username=fixture',arguments);self.assertNotIn(self.header['instanceId'],arguments[-1])
        for group,key,value in [('node','cluster_identifier','different'),('node','database_oid',789),('node','mode','EXTERNAL'),('allocation','slot',2),('local','epoch',2),('local','pending_change',str(uuid.uuid4())),('local','instance_id',str(uuid.uuid4()))]:
            changed=copy.deepcopy(proof);changed[group][key]=value;context.agent.command.return_value=json.dumps(changed).encode()
            with self.subTest(group=group,key=key),self.assertRaises(core.ReleaseRejected):context.recovery_database()
        self.compose['services']['postgres']['environment']['PG_DB']='eatwhat;DROP DATABASE';self.write('deploy/compose.json',self.compose)
        with self.assertRaises(core.ReleaseRejected):self.context().recovery_database()

if __name__=='__main__':unittest.main()

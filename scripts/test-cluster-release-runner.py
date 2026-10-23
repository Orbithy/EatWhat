#!/usr/bin/env python3
"""Runner unit checks: real private files/flock, simulated app/DB/authority only.

The Docker command stub records fixed recreate arguments and never runs Docker.
This is not SSH, Compose, database-backup, app-health or deployment acceptance.
"""
import copy, hashlib, importlib.util, io, json, os, signal, sys, types, unittest, uuid
from pathlib import Path
from unittest.mock import Mock, patch

REPO=Path(__file__).resolve().parents[1]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
fixture=module('runner_unit_fixture',REPO/'scripts/test-cluster-release-core.py')
runner=module('cluster_release_runner',REPO/'src/main/resources/control/cluster-release.py')

class RunnerChecks(unittest.TestCase):
    def test_production_bind_comparison_never_accepts_docker_desktop_aliases(self):
        expected='/opt/eatwhat-sites/'+str(uuid.uuid4())+'/deploy/initdb.sh'
        self.assertTrue(runner.bind_source_matches(expected,expected))
        self.assertFalse(runner.bind_source_matches('/host_mnt'+expected,expected))
        self.assertFalse(runner.bind_source_matches(expected+'/other',expected))
    def setUp(self):
        self.case=fixture.CoreChecks();self.case.setUp();self.core=fixture.core;runner.core=self.core
        self.h=self.case.header;self.candidate=b'approved-candidate-artifact';self.h.update(artifactSha256=hashlib.sha256(self.candidate).hexdigest(),artifactBytes=len(self.candidate),timeoutSeconds=30);self.h['configurationProof']=self.case.context().configuration_proof
        self.events=self.case.base/'docker-events.jsonl';self.cli=self.case.base/'fixed-docker-stub.py';self.cli.write_text('import json,sys\nwith open(sys.argv[1],"a") as out:out.write(json.dumps(sys.argv[2:])+"\\n")\n')
        self.backups=0;self.fail_backup=False;self.fail_rollback=False;self.did_install=False
        self.authority=patch.object(self.core,'verify_authority',return_value={'authorized':True});self.authority.start()
        self.origin=patch.object(self.core,'read_authority',return_value={'state':'SUCCEEDED','authorized':False});self.origin.start()
        self.inspection_source=runner.Runner.inspect_containers;self.inspection=patch.object(runner.Runner,'inspect_containers');self.inspection.start()
        self.r=runner.Runner(self.h,self.context)
    def tearDown(self):self.inspection.stop();self.origin.stop();self.authority.stop();self.case.tearDown()
    def context(self,*unused):
        context=self.case.context();context.agent.base=[sys.executable,str(self.cli),str(self.events)];context.c['command_timeout_seconds']=5
        context.agent.runtime_sha=lambda:self.core.digest(context.agent.jar)
        def health(*args):
            current=context.agent.jar.read_bytes()
            if current.startswith(b'bad-') or self.fail_rollback and self.did_install and current==b'synthetic-running-artifact':raise RuntimeError('simulated unhealthy application')
        context.agent.health=health
        def wait_ready(sha,*args):health();self.assertEqual(sha,self.core.digest(context.agent.jar))
        context.agent.wait_ready=wait_ready
        def backup(directory):
            self.backups+=1
            if self.fail_backup:raise RuntimeError('simulated dump failure')
            runner.atomic(directory/'database.dump',b'inert-backup-format-not-a-real-database')
        context.agent.backup=backup
        install=context.agent.install
        def installed(source,mode):install(source,mode);self.did_install=True
        context.agent.install=installed
        return context
    def calls(self):return [json.loads(line) for line in self.events.read_text().splitlines()] if self.events.exists() else []
    def test_early_restore_interruption_stays_unknown_without_writes_or_reexecution(self):
        journal=self.accepted(True,'RECOVERING');journal['recovery']={'id':str(uuid.uuid4()),'state':'RUNNING','stage':'RESTORING','mutationStarted':True};runner.atomic(self.r.receipt,journal)
        (self.case.root/'releases/current.jar').write_bytes(b'bad-not-yet-restored');before=self.r.receipt.read_bytes()
        value=self.r.status();self.assertEqual('OUTCOME_UNKNOWN',value['state']);self.assertEqual('RELEASE_RECOVERY_OUTCOME_UNKNOWN',value['code']);self.assertEqual(before,self.r.receipt.read_bytes());self.assertEqual([],self.calls())
    def test_late_restore_interruption_uses_actual_old_runtime_proof_without_writes(self):
        journal=self.accepted(True,'RECOVERING');journal['recovery']={'id':str(uuid.uuid4()),'state':'RUNNING','stage':'VERIFYING','mutationStarted':True};runner.atomic(self.r.receipt,journal);before=self.r.receipt.read_bytes()
        value=self.r.status();self.assertEqual('ROLLED_BACK',value['state']);self.assertEqual('SUCCEEDED',value['recovery']['state']);self.assertEqual(before,self.r.receipt.read_bytes());self.assertEqual([],self.calls())
    def accepted(self,mutation=False,stage='UPLOAD'):
        self.r.parent.mkdir(mode=0o700,exist_ok=True);self.r.directory.mkdir(mode=0o700)
        value=self.r.reply('RUNNING',mutationStarted=mutation,stage=stage,configurationProof=self.h['configurationProof']);runner.atomic(self.r.receipt,value);runner.atomic(self.r.directory/'request.json',self.h);return value
    def test_success_dispatch_is_once_and_receipt_never_contains_capabilities(self):
        reply=self.r.dispatch(io.BytesIO(self.candidate),detach=False);self.assertEqual('SUCCEEDED',reply['state']);self.assertEqual(1,self.backups);self.assertEqual([['up','-d','--no-deps','--force-recreate','app']],self.calls());self.assertEqual(self.candidate,(self.case.root/'releases/current.jar').read_bytes())
        with self.r.lock():again=self.r.dispatch(io.BytesIO(b'do-not-read-or-apply'),detach=False)
        self.assertEqual(reply,again);self.assertEqual(1,self.backups);self.assertEqual(1,len(self.calls()));self.assertNotIn(self.h['authorityToken'],json.dumps(reply));self.assertEqual(0o600,self.r.receipt.stat().st_mode&0o777);self.assertEqual(self.h['authorityToken'],self.core.document(self.r.directory/'request.json')['authorityToken'])
    def test_start_failure_rolls_back_only_artifact_and_recreates_app(self):
        self.candidate=b'bad-reviewed-candidate';self.h.update(artifactSha256=hashlib.sha256(self.candidate).hexdigest(),artifactBytes=len(self.candidate));self.r=runner.Runner(self.h,self.context)
        reply=self.r.dispatch(io.BytesIO(self.candidate),detach=False);self.assertEqual('ROLLED_BACK',reply['state']);self.assertEqual(b'synthetic-running-artifact',(self.case.root/'releases/current.jar').read_bytes());self.assertEqual([['up','-d','--no-deps','--force-recreate','app']]*2,self.calls());self.assertEqual(1,self.backups)
    def test_failed_backup_or_bad_upload_never_installs_or_recreates_app(self):
        self.fail_backup=True;reply=self.r.dispatch(io.BytesIO(self.candidate),detach=False);self.assertEqual('FAILED',reply['state']);self.assertFalse(reply['mutationStarted']);self.assertEqual([],self.calls());self.assertEqual(b'synthetic-running-artifact',(self.case.root/'releases/current.jar').read_bytes())
    def test_terminal_publication_ack_failure_never_rolls_back_an_already_verified_success(self):
        write=runner.atomic
        def lost_ack(path,value):
            write(path,value)
            if isinstance(value,dict) and value.get('state')=='SUCCEEDED':raise OSError('simulated fsync acknowledgement loss')
        with patch.object(runner,'atomic',side_effect=lost_ack):reply=self.r.dispatch(io.BytesIO(self.candidate),detach=False)
        self.assertEqual('OUTCOME_UNKNOWN',reply['state']);self.assertEqual(self.candidate,(self.case.root/'releases/current.jar').read_bytes());self.assertEqual(1,len(self.calls()));self.assertEqual('SUCCEEDED',self.r.status()['state'])
    def test_short_or_wrong_upload_is_retained_failed_without_worker(self):
        reply=self.r.dispatch(io.BytesIO(b'short'),detach=False);self.assertEqual('FAILED',reply['state']);self.assertEqual('RELEASE_UPLOAD_INCOMPLETE',reply['code']);self.assertEqual(0,self.backups);self.assertEqual([],self.calls());self.assertEqual(reply,self.r.dispatch(io.BytesIO(self.candidate),detach=False))
    def test_readonly_status_derives_pre_mutation_failure_and_seal_only_writes_metadata(self):
        self.accepted();before=self.r.receipt.read_bytes();mtime=self.r.receipt.stat().st_mtime_ns
        status=self.r.status();self.assertEqual('FAILED',status['state']);self.assertTrue(status['derived']);self.assertEqual(before,self.r.receipt.read_bytes());self.assertEqual(mtime,self.r.receipt.stat().st_mtime_ns)
        with patch.object(self.core,'read_authority',return_value={'state':'FAILED','authorized':False}):
            self.h['expectedRemoteState']='FAILED';sealed=self.r.seal();self.assertEqual('FAILED',sealed['state']);self.assertTrue(sealed['sealedAfterAuthority'])
        self.assertEqual([],self.calls());self.assertEqual(0,self.backups);self.assertEqual(b'synthetic-running-artifact',(self.case.root/'releases/current.jar').read_bytes())
    def test_interrupted_mutation_with_old_runtime_is_unknown_not_a_fake_rollback(self):
        self.accepted(True,'ROLLING_BACK');before=self.r.receipt.read_bytes();status=self.r.status();self.assertEqual('OUTCOME_UNKNOWN',status['state']);self.assertTrue(status['rollbackEligible']);self.assertEqual(before,self.r.receipt.read_bytes());self.assertEqual([],self.calls())
    def test_interrupted_new_runtime_is_verified_without_reexecution(self):
        self.accepted(True,'STARTING');self.case.write('releases/current.jar',self.candidate);before=self.r.receipt.read_bytes();status=self.r.status();self.assertEqual('SUCCEEDED',status['state']);self.assertTrue(status['derived']);self.assertEqual(before,self.r.receipt.read_bytes());self.assertEqual([],self.calls());self.assertEqual(0,self.backups)
    def test_held_kernel_lock_is_running_and_fake_pid_does_not_prove_running(self):
        self.accepted();value=self.core.document(self.r.receipt);value['pid']=99999999;runner.atomic(self.r.receipt,value)
        with self.r.lock():self.assertEqual('RUNNING',self.r.status()['state']);self.assertEqual('UPLOAD',self.r.status()['stage'])
        self.assertEqual('FAILED',self.r.status()['state'])
    def test_missing_record_in_existing_task_directory_stays_unknown(self):
        self.r.parent.mkdir(mode=0o700);self.r.directory.mkdir(mode=0o700);self.assertEqual('OUTCOME_UNKNOWN',self.r.status()['state'])
        with self.assertRaises(self.core.ReleaseRejected):self.r.dispatch(io.BytesIO(self.candidate),detach=False)
    def test_changed_manifest_and_unfinished_previous_job_cannot_dispatch(self):
        self.accepted();other=copy.deepcopy(self.h);other['manifestHash']='e'*64
        with self.assertRaises(self.core.ReleaseRejected):runner.Runner(other,self.context).status()
        other['manifestHash']=self.h['manifestHash'];other['id']=str(uuid.uuid4())
        with self.assertRaises(self.core.ReleaseRejected):runner.Runner(other,self.context).dispatch(io.BytesIO(self.candidate),detach=False)
        self.assertEqual([],self.calls());self.assertEqual(0,self.backups)
    def test_seal_refuses_active_authority_or_conflicting_target_outcome(self):
        self.accepted();self.h['expectedRemoteState']='FAILED'
        with patch.object(self.core,'read_authority',return_value={'state':'ACTIVE','authorized':True}),self.assertRaises(self.core.ReleaseRejected):self.r.seal()
        self.h['expectedRemoteState']='SUCCEEDED'
        with self.assertRaises(self.core.ReleaseRejected):self.r.seal()
        self.assertEqual([],self.calls())
    def test_container_provenance_and_actual_port_mount_image_environment_drift_fail_closed(self):
        ctx=self.context();active={'service':'app'}
        ctx.agent.command=lambda args:active.update(service=args[-1]) or b'e'*64
        def item(service):
            configured=self.case.compose['services'][service];project=ctx.c['project'];labels={'com.docker.compose.project':project,'com.docker.compose.service':service,'com.docker.compose.project.config_files':str(self.case.root/'deploy/compose.json')}
            paths={'app':{'/app/app.jar':str(self.case.root/'releases/current.jar'),'/config':str(self.case.root/'config'),'/state':str(self.case.root/'app-state')},'postgres':{'/docker-entrypoint-initdb.d/20-create-application-db.sh':str(self.case.root/'deploy/initdb.sh')},'redis':{}}[service]
            mounts=[{'Type':'bind','Source':source,'Destination':target,'RW':target=='/state'} for target,source in paths.items()]
            if service!='app':mounts.append({'Type':'volume','Name':project+('_pg_data' if service=='postgres' else '_redis_data'),'Destination':'/var/lib/postgresql/data' if service=='postgres' else '/data'})
            ports={}
            for port in configured.get('ports',[]):address,published,target=port.split(':');ports[target+'/tcp']=[{'HostIp':address,'HostPort':published}]
            return {'Image':configured['image'],'Config':{'Labels':labels,'User':configured.get('user'),'Entrypoint':configured.get('entrypoint'),'Cmd':configured.get('command'),'Env':[k+'='+v for k,v in configured.get('environment',{}).items()]},'HostConfig':{'Privileged':False,'ReadonlyRootfs':configured.get('read_only',False),'PortBindings':ports},'Mounts':mounts,'State':{'Running':True}}
        mutations=[lambda v:None,lambda v:v['Config']['Labels'].update({'com.docker.compose.project.config_files':'/another/compose.json'}),lambda v:v.update(Image='sha256:'+'f'*64),lambda v:v['HostConfig'].update(Privileged=True),lambda v:v['HostConfig'].update(PortBindings={}),lambda v:v['Config'].update(Env=['SPRING_DATASOURCE_URL=unexpected']),lambda v:v['Mounts'].append({'Type':'bind','Source':'/var/run/docker.sock','Destination':'/var/run/docker.sock','RW':True})]
        for i,mutate in enumerate(mutations):
            def inspect(*args,**kwargs):
                value=item(active['service'])
                if active['service']=='app':mutate(value)
                return types.SimpleNamespace(stdout=json.dumps([value]).encode())
            with patch.object(runner.subprocess,'run',side_effect=inspect):
                if i==0:self.inspection_source(self.r,ctx)
                else:
                    with self.subTest(case=i),self.assertRaises(self.core.ReleaseRejected):self.inspection_source(self.r,ctx)
    def test_failed_rollback_remains_unknown_and_cannot_be_sealed(self):
        self.candidate=b'bad-reviewed-candidate';self.h.update(artifactSha256=hashlib.sha256(self.candidate).hexdigest(),artifactBytes=len(self.candidate));self.fail_rollback=True
        self.r=runner.Runner(self.h,self.context);result=self.r.dispatch(io.BytesIO(self.candidate),detach=False);self.assertEqual('ROLLBACK_FAILED',result['state']);self.h['expectedRemoteState']='ROLLED_BACK'
        with patch.object(self.core,'read_authority',return_value={'state':'FAILED','authorized':False}),self.assertRaises(self.core.ReleaseRejected):self.r.seal()

if __name__=='__main__':unittest.main()

#!/usr/bin/env python3
"""Recovery journals/guards with real files and locks; DB/app/CLI are simulated.
Real SSH/Compose/DB and process-kill recovery need separate acceptance.
"""
import copy,hashlib,importlib.util,json,unittest,uuid
from pathlib import Path
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('runner_checks',ROOT/'scripts/test-cluster-release-runner.py');fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture);runner=fixture.runner

class RecoveryChecks(unittest.TestCase):
    def setUp(self):
        self.rig=fixture.RunnerChecks();self.rig.setUp();self.addCleanup(self.rig.tearDown)
        self.r=self.rig.r;self.core=self.rig.core;self.h=self.rig.h;self.root=self.rig.case.root
        self.rig.accepted(True,'STARTING');runner.copy_private(self.root/'releases/current.jar',self.r.directory/'previous.jar');runner.atomic(self.r.directory/'database.dump',b'inert-not-a-real-backup')
        self.h['recoveryRequestId']=str(uuid.uuid4());self.proof={'siteId':self.h['siteId'],'instanceId':self.h['instanceId'],'databaseFingerprint':self.h['databaseFingerprint'],'epoch':1}
        self.r.context_factory=self.context
    def context(self,*unused):
        value=self.rig.context();value.recovery_database=lambda:copy.deepcopy(self.proof);return value
    def preview(self):return self.r.recovery_prepare()
    def accept(self):self.h['recoveryProof']=self.preview()['recoveryProof'];return self.r.recover(detach=False)
    def test_preview_is_readonly_and_explicit_restore_proves_old_runtime_without_database_restore(self):
        before=self.r.receipt.read_bytes();preview=self.preview();self.assertEqual(before,self.r.receipt.read_bytes());self.assertEqual('RECOVERY_PREPARED',preview['state']);self.assertTrue(preview['databaseIdentityVerified'])
        result=self.accept();self.assertEqual('ROLLED_BACK',result['state']);self.assertEqual('SUCCEEDED',result['recovery']['state']);self.assertEqual(b'synthetic-running-artifact',(self.root/'releases/current.jar').read_bytes())
        recreates=[call for call in self.rig.calls() if call and call[0]=='up'];self.assertEqual([['up','-d','--no-deps','--force-recreate','app']],recreates);self.assertFalse(any('pg_restore' in call and '--list' not in call for call in self.rig.calls()));self.assertEqual(0,self.rig.backups)
    def test_replaying_recovery_uuid_never_recreates_or_reapplies(self):
        first=self.accept();calls=list(self.rig.calls());again=self.r.recover(detach=False);self.assertEqual(first,again);self.assertEqual(calls,self.rig.calls())
    def test_current_healthy_candidate_requires_readonly_reconciliation_not_rollback(self):
        self.rig.case.write('releases/current.jar',self.rig.candidate)
        with self.assertRaises(self.core.ReleaseRejected) as failure:self.preview()
        self.assertEqual('RELEASE_RECOVERY_USE_RECONCILIATION',failure.exception.code);self.assertFalse(any(call[0]=='up' for call in self.rig.calls()))
    def test_bad_current_candidate_can_be_restored_while_application_health_is_down(self):
        candidate=b'bad-reviewed-candidate';value=self.r.journal();self.h['artifactSha256']=hashlib.sha256(candidate).hexdigest();value['artifactSha256']=self.h['artifactSha256'];runner.atomic(self.r.receipt,value);self.rig.case.write('releases/current.jar',candidate)
        result=self.accept();self.assertEqual('ROLLED_BACK',result['state']);self.assertEqual('SUCCEEDED',result['recovery']['state'])
    def test_changed_receipt_configuration_previous_jar_or_database_proof_rejects_stale_plan(self):
        for change in ['receipt','config','previous','database']:
            with self.subTest(change=change):
                preview=self.preview();self.h['recoveryProof']=preview['recoveryProof'];paths={p:p.read_bytes() for p in [self.r.receipt,self.r.directory/'previous.jar',self.root/'config/application.properties']};old=copy.deepcopy(self.proof)
                try:
                    if change=='receipt':value=self.r.journal();value['stage']='DIFFERENT';runner.atomic(self.r.receipt,value)
                    elif change=='config':self.rig.case.write('config/application.properties',(self.root/'config/application.properties').read_bytes()+b'logging.level.root=ERROR\n')
                    elif change=='previous':self.rig.case.write('private/cluster-deployments/'+self.h['id']+'/previous.jar',b'foreign-artifact')
                    else:self.proof['epoch']=2
                    with self.assertRaises(self.core.ReleaseRejected):self.r.recover(detach=False)
                    self.assertFalse(any(call[0]=='up' for call in self.rig.calls()))
                finally:
                    for path,value in paths.items():runner.atomic(path,value)
                    self.proof=old
    def test_active_project_lock_or_authority_revocation_blocks_recovery(self):
        with self.r.lock(),self.assertRaises(self.core.ReleaseRejected):self.preview()
        with patch.object(self.core,'verify_authority',side_effect=self.core.ReleaseRejected('RELEASE_AUTHORITY_NOT_AUTHORIZED')),self.assertRaises(self.core.ReleaseRejected):self.preview()
        self.assertFalse(any(call[0]=='up' for call in self.rig.calls()))
    def test_unknown_recovery_is_readonly_until_explicit_new_request_and_preserves_history(self):
        value=self.r.journal();value['recovery']={'id':str(uuid.uuid4()),'state':'RUNNING','stage':'VALIDATED','mutationStarted':False};runner.atomic(self.r.receipt,value)
        result=self.accept();self.assertEqual('ROLLED_BACK',result['state']);self.assertEqual(1,len(result['recoveryHistory']));self.assertEqual('RUNNING',result['recoveryHistory'][0]['state'])
    def test_interrupted_restore_with_old_runtime_proof_is_derived_and_sealed_without_reapplying(self):
        value=self.r.journal();value['recovery']={'id':self.h['recoveryRequestId'],'state':'RUNNING','stage':'VERIFYING','mutationStarted':True,'proof':'private-proof','databaseProof':self.proof};runner.atomic(self.r.receipt,value);before=self.r.receipt.read_bytes()
        result=self.r.recovery_status();self.assertEqual('ROLLED_BACK',result['state']);self.assertEqual('SUCCEEDED',result['recovery']['state']);self.assertEqual(before,self.r.receipt.read_bytes());self.assertEqual([],self.rig.calls())
        self.h['expectedRemoteState']='ROLLED_BACK'
        with patch.object(self.core,'read_authority',return_value={'state':'FAILED','authorized':False}):sealed=self.r.seal()
        self.assertTrue(sealed['sealedAfterAuthority']);self.assertEqual('SUCCEEDED',self.r.journal()['recovery']['state']);self.assertNotIn('databaseProof',runner.public_reply(sealed)['recovery']);self.assertNotIn('proof',runner.public_reply(sealed)['recovery'])
    def test_failed_manual_restore_keeps_original_unknown(self):
        self.rig.fail_rollback=True;result=self.accept();self.assertEqual('ROLLBACK_FAILED',result['state']);self.assertEqual('OUTCOME_UNKNOWN',result['recovery']['state']);self.assertEqual(1,len([call for call in self.rig.calls() if call[0]=='up']))
    def test_status_for_unaccepted_request_does_not_create_a_receipt_or_restore(self):
        before=self.r.receipt.read_bytes();result=self.r.recovery_status();self.assertEqual('NOT_STARTED',result['recovery']['state']);self.assertEqual(before,self.r.receipt.read_bytes());self.assertEqual([],self.rig.calls())
    def test_missing_original_receipt_never_becomes_false_proof_that_recovery_was_not_accepted(self):
        self.r.receipt.unlink();result=self.r.recovery_status();self.assertEqual('OUTCOME_UNKNOWN',result['recovery']['state']);self.assertEqual('RELEASE_RECOVERY_RECORD_UNAVAILABLE',result['recovery']['code']);self.assertFalse(self.r.receipt.exists());self.assertEqual([],self.rig.calls())
    def test_terminal_publication_lost_ack_never_runs_restore_twice(self):
        self.h['recoveryProof']=self.preview()['recoveryProof'];write=runner.atomic
        def lost_ack(path,value):
            write(path,value)
            if isinstance(value,dict) and value.get('state')=='ROLLED_BACK':raise OSError('Synthetic final publication acknowledgement loss')
        with patch.object(runner,'atomic',side_effect=lost_ack),self.assertRaises(OSError):self.r.recover(detach=False)
        before=list(self.rig.calls());self.assertEqual('SUCCEEDED',self.r.recover(detach=False)['recovery']['state']);self.assertEqual(before,self.rig.calls());self.assertEqual(1,len([call for call in before if call[0]=='up']))
    def test_configuration_change_after_acceptance_fails_before_any_application_change(self):
        self.h['recoveryProof']=self.preview()['recoveryProof'];worker=self.r.recovery_worker
        def changed(context,fd):
            self.rig.case.write('config/application.properties',(self.root/'config/application.properties').read_bytes()+b'logging.level.root=ERROR\n');worker(context,fd)
        with patch.object(self.r,'recovery_worker',side_effect=changed):
            result=self.r.recover(detach=False);self.assertEqual('FAILED',result['recovery']['state'])
        value=self.r.journal()['recovery'];self.assertEqual('FAILED',value['state']);self.assertFalse(value['mutationStarted']);self.assertFalse(any(call[0]=='up' for call in self.rig.calls()))

if __name__=='__main__':unittest.main()

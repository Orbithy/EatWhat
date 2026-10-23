package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real private journal/locks, explicit mocked authority/remote phase proofs only. */
class ControlBootstrapJournalTest {
    @TempDir Path root;
    ControlServerStore store;ControlAudit audit;DeploymentExecutor deployment;ControlServers servers;ControlBootstrapJournal journal;
    UUID id,source,target,cluster;ControlBootstrapJournal.Spec spec;Map<String,Object> review;
    @BeforeEach void setup()throws Exception{
        root=root.toRealPath();audit=new ControlAudit(root);store=new ControlServerStore(audit);deployment=new DeploymentExecutor(new ControlProperties(),audit);
        servers=mock(ControlServers.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE));journal=new ControlBootstrapJournal(store,audit,deployment,servers);
        id=UUID.randomUUID();source=UUID.randomUUID();target=UUID.randomUUID();cluster=UUID.randomUUID();
        spec=new ControlBootstrapJournal.Spec(id,source,1,target,2,cluster,2,"a".repeat(64),"business-fixture","b".repeat(64),2);
        review=new LinkedHashMap<>(Map.of("sourceAddress","10.253.74.1","targetAddress","10.253.74.3","targetEndpoint","fixture.example.invalid","wireguardPort",51820,"httpPort",18090,"appBaseUrl","https://fixture.example.invalid/api"));
    }
    @AfterEach void close(){deployment.close();}
    Map<String,Object> plan(){return journal.preview("123",spec,review,Map.of("syntheticPassword","private-value-only"),()->{});}
    Map<String,Object> accept(){plan();return journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->{});}
    Map<String,Object> receipt(ControlBootstrapJournal.Phase phase,String state){return Map.of(phase==ControlBootstrapJournal.Phase.BUSINESS_INSTALL?"jobId":"id",ControlBootstrapJournal.phaseId(id,phase).toString(),"protocol",phase.protocol,"state",state,"syntheticCredential","never-public");}
    Path path(){return root.resolve("controller-job-"+id+".json");}
    Map<String,Object> managedAccept(){
        var preview=journal.preview("123",spec,review,Map.of("executionProtocol","eatwhat-controller-bootstrap-managed-v1","admission",Map.of("binding","fixture")),()->{});
        assertEquals(false,preview.get("canCancelBeforeDispatch"));return journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->{});
    }
    @Test void originalUnstartedTaskCanBeCancelledWithoutDeletingItsApprovalOrGrantingAnotherDispatch(){
        var queued=managedAccept();assertEquals(true,queued.get("canCancelBeforeDispatch"));var original=store.readJson(path());
        journal.executionObservation(id,"BOOTSTRAP_ORIGINAL_CONTROLLER_REQUIRED",true);
        var checks=new java.util.concurrent.atomic.AtomicInteger();var cancelled=journal.cancelUnstarted(id,"456",queued.get("requestHash").toString(),intent->{checks.incrementAndGet();intent.run();});
        assertEquals("FAILED",cancelled.get("state"));assertEquals(true,cancelled.get("cancelledBeforeDispatch"));assertEquals(false,cancelled.get("canCancelBeforeDispatch"));assertEquals(false,cancelled.get("requiresReapproval"));assertEquals(false,cancelled.get("effectsStarted"));
        assertEquals(original.get("_body"),store.readJson(path()).get("_body"));assertEquals(original.get("requestHash"),cancelled.get("requestHash"));assertNull(deployment.whenIdle(()->{}));
        var replay=journal.cancelUnstarted(id,"456",queued.get("requestHash").toString(),intent->{checks.incrementAndGet();intent.run();});assertEquals(cancelled,replay);assertEquals(2,checks.get());
        assertEquals("BOOTSTRAP_TASK_TERMINAL",assertThrows(ControlFailure.class,()->journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->{})).code);
    }
    @Test void evenANotStartedRemoteResultCannotBeCancelledAfterTheDurableDispatchIntent(){
        var queued=managedAccept();var claim=journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->{});
        var unknown=journal.observe(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,claim.claimToken(),receipt(ControlBootstrapJournal.Phase.SOURCE_RUNTIME,"NOT_STARTED"));
        assertEquals(false,unknown.get("canCancelBeforeDispatch"));
        assertEquals("BOOTSTRAP_DISPATCH_ALREADY_CLAIMED",assertThrows(ControlFailure.class,()->journal.cancelUnstarted(id,"123",queued.get("requestHash").toString(),intent->fail())).code);
        assertEquals("OUTCOME_UNKNOWN",journal.job(id).get("state"));assertNotNull(deployment.whenIdle(()->fail()));
    }
    @Test void cancellationUsesTheSameLockAsDispatchAndRequiresAnInvokedCurrentAuthorityBoundary(){
        var queued=managedAccept();String hash=queued.get("requestHash").toString();
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->journal.cancelUnstarted(id,"123","0".repeat(64),intent->fail())).code);
        assertEquals("ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->journal.cancelUnstarted(id,"123",hash,intent->{throw new ControlFailure(403,"ADMIN_REQUIRED");})).code);
        assertEquals("QUEUED",journal.job(id).get("state"));
        assertEquals("BOOTSTRAP_INTENT_NOT_WRITTEN",assertThrows(ControlFailure.class,()->journal.cancelUnstarted(id,"123",hash,intent->{})).code);
        journal.cancelUnstarted(id,"123",hash,intent->{assertEquals("DEPLOYMENT_RUNNING",assertThrows(ControlFailure.class,()->journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->fail())).code);intent.run();});
        assertTrue(Files.exists(path()));
    }
    @Test void legacyInternalJobsHaveNoCancellationCapability(){
        var old=accept();assertEquals(false,old.get("canCancelBeforeDispatch"));
        assertEquals("BOOTSTRAP_REAPPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->journal.cancelUnstarted(id,"123",old.get("requestHash").toString(),intent->fail())).code);
    }
    @Test @SuppressWarnings("unchecked") void storageReviewIsBoundToTheEncryptedOriginalAndExposesNoCredentials()throws Exception{
        var storage=new ControlBootstrapStorage(store);var selection=new ControlBootstrapStorage.Selection("R2","https://s3.example.invalid","auto","eatwhat","private-target-access","private-target-secret",false,"https://cdn.example.invalid",null);
        var saved=storage.freeze(id,target,2,selection,Map.of());var inputs=Map.<String,Object>of("admission",Map.of("binding","fixture"),"targetStorage",saved,"creationBinding","same-request");
        var preview=journal.preview("123",spec,review,inputs,()->{});assertEquals("CUSTOM",((Map<?,?>)preview.get("storage")).get("mode"));
        var mapper=JsonMapper.builder().build();String publicJson=mapper.writeValueAsString(preview);
        assertFalse(publicJson.contains("private-target"));assertEquals(publicJson,mapper.writeValueAsString(journal.plan(id)));
        assertEquals(publicJson,mapper.writeValueAsString(journal.existingPreview(id,"123","same-request")));assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ControlFailure.class,()->journal.existingPreview(id,"123","different")).code);
        journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->{});
        var f=org.mockito.Mockito.mock(ControlBootstrapAdmission.Frozen.class,org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
        org.mockito.Mockito.when(f.spec()).thenReturn(spec);org.mockito.Mockito.when(f.actor()).thenReturn("123");org.mockito.Mockito.when(f.review()).thenReturn(review);org.mockito.Mockito.when(f.binding()).thenReturn("fixture");
        assertEquals("private-target-secret",journal.targetStorageProperties(f).get("app.storage.s3.secret-key"));
        var job=store.readJson(path());((Map<String,Object>)job.get("storage")).put("bucket","other");store.saveJson(path(),job);
        assertEquals("BOOTSTRAP_STORAGE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->journal.job(id)).code);
    }
    @Test void previewIsReviewablePrivateEncryptedAndExactReplayIsIdempotent()throws Exception{
        var first=plan();assertEquals("PREVIEW_ONLY",first.get("state"));assertEquals(JsonMapper.builder().build().writeValueAsString(first),JsonMapper.builder().build().writeValueAsString(plan()));assertFalse(first.toString().contains("private-value-only"));
        String raw=Files.readString(root.resolve("controller-plan-"+id+".json"));assertFalse(raw.contains("private-value-only"));assertEquals(13,((List<?>)first.get("steps")).size());
        assertTrue(first.get("requestHash").toString().matches("[a-f0-9]{64}"));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(ControlFailure.class,()->journal.preview("123",spec,review,Map.of("syntheticPassword","changed"),()->{})).code);
    }
    @Test void everyListedPermissionAndOriginalAdminAreRequired(){
        plan();assertEquals("BOOTSTRAP_PERMISSION_APPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->journal.accept("123",id,Set.of(),()->{})).code);
        var withoutPrivateListener=new HashSet<>(ControlBootstrapJournal.PERMISSIONS);withoutPrivateListener.remove("ALLOW_PRIVATE_DEPENDENCY_LISTENER");
        assertEquals("BOOTSTRAP_PERMISSION_APPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->journal.accept("123",id,withoutPrivateListener,()->{})).code);
        assertEquals("BOOTSTRAP_ORIGINAL_ADMIN_REQUIRED",assertThrows(ControlFailure.class,()->journal.accept("456",id,ControlBootstrapJournal.PERMISSIONS,()->{})).code);assertFalse(Files.exists(path()));
    }
    @Test void expiredApprovalCannotBecomeAnAcceptedTask(){
        plan();var file=root.resolve("controller-plan-"+id+".json");var p=store.readJson(file);p.put("expiresAt",Instant.now().minusSeconds(1).toString());store.saveJson(file,p);
        assertEquals("BOOTSTRAP_PLAN_EXPIRED",assertThrows(ControlFailure.class,()->journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->{})).code);assertFalse(Files.exists(path()));
    }
    @Test void acceptedCompositeBlocksDeploymentAndHandoffWithoutAnyDispatch(){
        var value=accept();assertEquals("QUEUED",value.get("state"));assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",deployment.whenIdle(()->fail("must not release control to a handoff")));
    }
    @Test void phaseClaimIsDurableOnceAndRestartOnlyReturnsAnObservationDescriptor(){
        accept();var phase=ControlBootstrapJournal.Phase.SOURCE_RUNTIME;var first=journal.claim(id,phase,()->{});assertTrue(first.dispatchAllowed());
        assertTrue(first.privateInputs().toString().contains("private-value-only"));assertFalse(first.toString().contains("private-value-only"));
        var afterRestart=new ControlBootstrapJournal(store,audit,deployment,servers);var second=afterRestart.claim(id,phase,()->fail("already-dispatched phase must only be read"));
        assertFalse(second.dispatchAllowed());assertEquals(first.id(),second.id());assertEquals(first.claimToken(),second.claimToken());assertEquals(first.approvalDigest(),second.approvalDigest());
        assertEquals("OUTCOME_UNKNOWN",afterRestart.job(id).get("state"));
    }
    @Test void outOfOrderPhasesAndForgedReceiptsCannotAdvance(){
        accept();assertEquals("BOOTSTRAP_PREVIOUS_PHASE_REQUIRED",assertThrows(ControlFailure.class,()->journal.claim(id,ControlBootstrapJournal.Phase.TARGET_RUNTIME,()->{})).code);
        var phase=ControlBootstrapJournal.Phase.SOURCE_RUNTIME;var claim=journal.claim(id,phase,()->{});
        assertEquals("BOOTSTRAP_PHASE_TOKEN_MISMATCH",assertThrows(ControlFailure.class,()->journal.observe(id,phase,"forged",receipt(phase,phase.success))).code);
        assertEquals("BOOTSTRAP_PHASE_RECEIPT_MISMATCH",assertThrows(ControlFailure.class,()->journal.observe(id,phase,claim.claimToken(),receipt(ControlBootstrapJournal.Phase.TARGET_RUNTIME,phase.success))).code);
    }
    @Test void notStartedOrUnknownNeverAuthorizesASecondDispatch(){
        accept();var phase=ControlBootstrapJournal.Phase.SOURCE_RUNTIME;var claim=journal.claim(id,phase,()->{});
        assertEquals("OUTCOME_UNKNOWN",journal.observe(id,phase,claim.claimToken(),receipt(phase,"NOT_STARTED")).get("state"));
        assertFalse(journal.claim(id,phase,()->{}).dispatchAllowed());assertNotNull(deployment.whenIdle(()->fail()));
        assertEquals("RUNNING",journal.observe(id,phase,claim.claimToken(),receipt(phase,"RUNNING")).get("state"));assertFalse(journal.claim(id,phase,()->{}).dispatchAllowed());
    }
    @Test void staleRemoteObservationNeverClaimsTheWorkerStillRuns(){
        accept();var phase=ControlBootstrapJournal.Phase.SOURCE_RUNTIME;var claim=journal.claim(id,phase,()->{});journal.observe(id,phase,claim.claimToken(),receipt(phase,"RUNNING"));
        var job=store.readJson(path());((Map<String,Object>)((List<?>)job.get("steps")).getFirst()).put("observedAt",Instant.now().minusSeconds(16).toString());store.saveJson(path(),job);
        assertEquals("OUTCOME_UNKNOWN",journal.job(id).get("state"));
    }
    @Test void permanentGuardDriftBeforeFirstDispatchFailsSafelyAndReleasesFence(){
        accept();assertThrows(ControlFailure.class,()->journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->{throw new ControlFailure(409,"SERVER_REVISION_CHANGED");}));
        var job=journal.job(id);assertEquals("FAILED",job.get("state"));assertEquals(false,job.get("effectsStarted"));assertNull(deployment.whenIdle(()->{}));
    }
    @Test void transientOutageOrStandbyKeepsQueueAndPartialEffectsKeepUnknownFence(){
        accept();assertThrows(ControlFailure.class,()->journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->{throw new ControlFailure(503,"DEPENDENCY_UNAVAILABLE");}));assertEquals("QUEUED",journal.job(id).get("state"));
        var first=journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->{});journal.observe(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,first.claimToken(),receipt(ControlBootstrapJournal.Phase.SOURCE_RUNTIME,"RUNTIME_READY"));
        assertThrows(ControlFailure.class,()->journal.claim(id,ControlBootstrapJournal.Phase.TARGET_RUNTIME,()->{throw new ControlFailure(409,"ADMIN_REQUIRED");}));assertEquals("OUTCOME_UNKNOWN",journal.job(id).get("state"));assertNotNull(deployment.whenIdle(()->fail()));
        assertEquals("BOOTSTRAP_REAPPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->journal.claim(id,ControlBootstrapJournal.Phase.TARGET_RUNTIME,()->{})).code);
    }
    @Test void journalOnlyCompletesAfterEveryDistinctPhaseProofAndRawRepliesStayPrivate()throws Exception{
        accept();for(var phase:ControlBootstrapJournal.Phase.values()){
            if(phase==ControlBootstrapJournal.Phase.DEPENDENCY_LINK){
                for(var op:ControlBootstrapJournal.DependencyOperation.values()){
                    var claim=journal.claimDependencyOperation(id,op,"a".repeat(64),"b".repeat(64),()->{});
                    var proof=Map.<String,Object>of("id",claim.nativeId().toString(),"protocol",phase.protocol,"operation",op.name(),"role",op.role,"binding",claim.nativeBinding(),"state",op.success,"syntheticCredential","never-public");
                    var view=journal.observeDependencyOperation(id,op,claim.claimToken(),proof);assertEquals("QUEUED",view.get("state"));assertFalse(view.toString().contains("never-public"));
                }continue;
            }
            var claim=journal.claim(id,phase,()->{});assertTrue(claim.dispatchAllowed());var view=journal.observe(id,phase,claim.claimToken(),receipt(phase,phase.success));
            assertEquals(phase==ControlBootstrapJournal.Phase.VERIFY?"SUCCEEDED":"QUEUED",view.get("state"));assertFalse(view.toString().contains("never-public"));
        }
        assertNull(deployment.whenIdle(()->{}));assertFalse(Files.readString(path()).contains("never-public"));assertFalse(Files.readString(path()).contains("private-value-only"));
        // A completed historical record has no further operation to approve.
        store.saveJson(path(),legacyBody(store.readJson(path())));assertEquals("SUCCEEDED",journal.job(id).get("state"));assertEquals(false,journal.job(id).get("requiresReapproval"));
    }
    @Test void terminalFailedRemotePhaseIsPreservedAndCannotRestart(){
        accept();var phase=ControlBootstrapJournal.Phase.SOURCE_RUNTIME;var claim=journal.claim(id,phase,()->{});var view=journal.observe(id,phase,claim.claimToken(),receipt(phase,"FAILED"));assertEquals("FAILED",view.get("state"));assertEquals(true,view.get("effectsStarted"));
        assertEquals("BOOTSTRAP_TASK_TERMINAL",assertThrows(ControlFailure.class,()->journal.claim(id,phase,()->{})).code);
    }
    @Test void taskHistoryAndReadEndpointCannotExposeSecretsOrSkipAuthentication(){
        accept();var history=new ControlTaskHistory(root,(group,task)->journal.job(task));var response=history.list(20,"CONTROLLER_INSTALL",null,target,null);
        var row=(Map<?,?>)((List<?>)response.get("items")).getFirst();assertEquals("/admin/control/v1/controller-jobs/"+id,row.get("detailRoute"));assertFalse(response.toString().contains("private-value-only"));
        var auth=mock(ControlController.class);when(auth.admin()).thenThrow(new ControlFailure(401,"LOGIN_REQUIRED"));assertEquals(401,assertThrows(ControlFailure.class,()->new ControlBootstrapJobController(auth,journal,new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(ControlBootstrapInspection.class)).get(id)).status);
    }
    @Test void publicReviewCannotCarryAnUndisclosedSecretOrCredentialUrl(){
        review.put("hiddenCredential","secret");assertThrows(ControlFailure.class,this::plan);review.remove("hiddenCredential");review.put("appBaseUrl","https://user:secret@fixture.example.invalid/api");assertThrows(ControlFailure.class,this::plan);assertFalse(Files.exists(path()));
    }
    @Test void fenceMustPersistIntentExactlyOnceBeforeReturningAuthorization()throws Exception{
        accept();var phase=ControlBootstrapJournal.Phase.SOURCE_RUNTIME;
        assertEquals("BOOTSTRAP_INTENT_NOT_WRITTEN",assertThrows(ControlFailure.class,()->journal.claimFenced(id,phase,intent->{})).code);
        assertEquals("QUEUED",journal.job(id).get("state"));
        assertEquals("BOOTSTRAP_INTENT_ALREADY_WRITTEN",assertThrows(ControlFailure.class,()->journal.claimFenced(id,phase,intent->{intent.run();intent.run();})).code);
        assertFalse(journal.claim(id,phase,()->fail("Original intent retained after caller failure")).dispatchAllowed());
    }
    @Test void swallowedPersistenceFailureCannotBecomeADispatchPermission(){
        accept();var failing=mock(ControlServerStore.class,withSettings().spiedInstance(store).defaultAnswer(CALLS_REAL_METHODS).mockMaker(org.mockito.MockMakers.INLINE));
        doThrow(new ControlFailure(503,"SYNTHETIC_PERSISTENCE_UNAVAILABLE")).when(failing).saveJson(eq(path()),any());
        var broken=new ControlBootstrapJournal(failing,audit,deployment,servers);var phase=ControlBootstrapJournal.Phase.SOURCE_RUNTIME;
        assertEquals("BOOTSTRAP_INTENT_NOT_WRITTEN",assertThrows(ControlFailure.class,()->broken.claimFenced(id,phase,intent->{
            try{intent.run();}catch(ControlFailure deliberatelySwallowed){}
        })).code);
        assertEquals("QUEUED",journal.job(id).get("state"));assertTrue(journal.claim(id,phase,()->{}).dispatchAllowed(),"No durable intent or remote permission had been produced");
    }
    @Test @SuppressWarnings("unchecked") void permissionsAreInsideTheOriginalDigestAndCannotBeGrantedByEditingThePublicList(){
        var p=plan();var file=root.resolve("controller-plan-"+id+".json");var saved=store.readJson(file);
        var body=JsonMapper.builder().build().readValue(store.decrypt(saved.get("_body").toString()),Map.class);
        assertEquals(p.get("permissionsRequired"),body.get("permissionsRequired"));
        var permissions=new ArrayList<>((List<String>)saved.get("permissionsRequired"));permissions.add("UNAPPROVED_NETWORK_SCOPE");saved.put("permissionsRequired",permissions);store.saveJson(file,saved);
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->fail("Tampered review may not reach the authority guard"))).code);
        assertFalse(Files.exists(path()));
    }
    @Test @SuppressWarnings("unchecked") void changedReviewedTargetUrlOrArtifactCannotMasleadApproval(){
        plan();var file=root.resolve("controller-plan-"+id+".json");var original=store.readJson(file);
        for(var edit:Map.<String,Object>of("serverId",UUID.randomUUID().toString(),"controllerSha256","c".repeat(64)).entrySet()){
            var changed=new LinkedHashMap<>(original);changed.put(edit.getKey(),edit.getValue());store.saveJson(file,changed);
            assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->fail())).code);
        }
        var changed=new LinkedHashMap<>(original);var review=new LinkedHashMap<>((Map<String,Object>)original.get("review"));review.put("appBaseUrl","https://changed.example.invalid/api");changed.put("review",review);store.saveJson(file,changed);
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->fail())).code);assertFalse(Files.exists(path()));
    }
    @SuppressWarnings("unchecked") Map<String,Object> legacyBody(Map<String,Object> file){
        var body=JsonMapper.builder().build().readValue(store.decrypt(file.get("_body").toString()),Map.class);body.remove("permissionsRequired");
        String raw=org.springframework.test.util.ReflectionTestUtils.invokeMethod(journal,"encode",body);
        String hash=org.springframework.test.util.ReflectionTestUtils.invokeMethod(journal,"seal",raw);
        file.put("_body",store.encrypt(raw));file.put("requestHash",hash);return file;
    }
    @Test void legacyPreviewMustBeReviewedAgainInsteadOfAcquiringNewScope(){
        plan();var file=root.resolve("controller-plan-"+id+".json");store.saveJson(file,legacyBody(store.readJson(file)));
        assertEquals("BOOTSTRAP_REAPPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->fail())).code);assertFalse(Files.exists(path()));
    }
    @Test @SuppressWarnings("unchecked") void legacyDispatchedTaskCanObserveOriginalReceiptButCannotStartAnotherPhase(){
        accept();var saved=legacyBody(store.readJson(path()));var rows=(List<Map<String,Object>>)saved.get("steps");
        rows.getFirst().put("state","DISPATCHED");saved.put("state","RUNNING");saved.put("stage","SOURCE_RUNTIME");store.saveJson(path(),saved);
        var original=journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->fail("Legacy claimed task is observation only"));assertFalse(original.dispatchAllowed());
        var observed=journal.observe(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,original.claimToken(),receipt(ControlBootstrapJournal.Phase.SOURCE_RUNTIME,"RUNTIME_READY"));
        assertEquals(true,observed.get("requiresReapproval"));assertEquals(true,journal.job(id).get("requiresReapproval"));
        assertEquals("BOOTSTRAP_REAPPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->journal.claim(id,ControlBootstrapJournal.Phase.TARGET_RUNTIME,()->fail())).code);
        assertFalse(journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->fail()).dispatchAllowed());
        assertEquals("BOOTSTRAP_REAPPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->journal.approvedPermissions(id)).code);
    }
    @Test void changingThePlanDuringAuthorityValidationCannotProduceAnAcceptedTask(){
        plan();var file=root.resolve("controller-plan-"+id+".json");
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->journal.accept("123",id,ControlBootstrapJournal.PERMISSIONS,()->{
            var changed=store.readJson(file);changed.put("expiresAt",Instant.now().plusSeconds(3600).toString());store.saveJson(file,changed);
        })).code);assertFalse(Files.exists(path()));
    }
    @Test void acceptedPublicMetadataCannotDivergeFromItsSealedScope(){
        accept();var saved=store.readJson(path());saved.put("permissionsRequired",List.of("ALLOW_BUSINESS_DOCKER_NETWORKING"));store.saveJson(path(),saved);
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->journal.job(id)).code);
        assertEquals("BOOTSTRAP_PLAN_CHANGED",assertThrows(ControlFailure.class,()->journal.claim(id,ControlBootstrapJournal.Phase.SOURCE_RUNTIME,()->fail())).code);
    }
    @Test void taskHistoryUsesTheAuthenticatedJournalReaderAndRefusesAlteredScope(){
        accept();var saved=store.readJson(path());saved.put("serverId",UUID.randomUUID().toString());store.saveJson(path(),saved);
        var auth=mock(ControlController.class);var controller=new ControlTaskController(auth,audit,null,null,null,journal);
        assertEquals("TASK_HISTORY_UNAVAILABLE",assertThrows(ControlFailure.class,()->controller.list(20,"CONTROLLER_INSTALL",null,null,null)).code);verify(auth).admin();
    }
}

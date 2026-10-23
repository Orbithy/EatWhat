package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentExecutorTest {
    @Test void compositeIdleGuardsRemainCombinedWhenTheClusterGuardIsInstalledLater(){
        try(var e=new DeploymentExecutor(new ControlProperties(),audit)){
            var composite=new AtomicBoolean(true);e.addIdleGuard(()->composite.get()?"CONTROLLER_BOOTSTRAP_RUNNING":null);
            e.externalIdleGuard(()->null);assertEquals("CONTROLLER_BOOTSTRAP_RUNNING",e.idleFenceReason());
            e.externalIdleGuard(()->"CLUSTER_DEPLOYMENT_RUNNING");assertEquals("CLUSTER_DEPLOYMENT_RUNNING",e.idleFenceReason());
            e.externalIdleGuard(()->null);composite.set(false);assertNull(e.idleFenceReason());
        }
    }
    @TempDir Path root;
    ControlProperties p;ControlAudit audit;String sha;Path artifact;List<DeploymentExecutor.Target> targets;
    @BeforeEach void setup()throws Exception{
        p=new ControlProperties();p.setEnvironmentKind("isolated-acceptance");audit=new ControlAudit(root.resolve("state"));
        artifact=root.resolve("candidate.jar");Files.writeString(artifact,"candidate-v2");sha=DeploymentExecutor.artifactSha(artifact);
        var release=new ControlProperties.Release();release.setArtifact(artifact);release.setSha256(sha);release.setRollbackCompatible(true);release.setAcceptanceEvidenceId("compat-reviewed");release.setCompatibleFromSha256(List.of("a".repeat(64)));p.getReleases().put("v2",release);
        var d=p.getDeployment();d.setEnabled(true);Path key=root.resolve("key"),known=root.resolve("known_hosts");Files.writeString(key,"synthetic private key");Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));Files.writeString(known,"synthetic known host");d.setIdentityFile(key);d.setKnownHostsFile(known);
        targets=new ArrayList<>();
        for(String site:List.of("campus","external")){var t=new ControlProperties.DeploymentTarget();t.setHost(site+".invalid");t.setUser("deploy");t.setSiteId(site);t.setInstanceId(UUID.randomUUID());t.setCurrentSha256("a".repeat(64));t.setRestoreEvidenceId("restore-reviewed");d.getTargets().put(site,t);targets.add(new DeploymentExecutor.Target(site,site,t.getInstanceId()));}
    }
    Map<String,Object> reply(String state,UUID id,ControlProperties.DeploymentTarget t,String s){return Map.of("id",id.toString(),"siteId",t.getSiteId(),"instanceId",t.getInstanceId().toString(),"artifactSha256",s,"state",state);}
    Map<String,Object> done(DeploymentExecutor e,UUID id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);Map<String,Object> job;do{job=e.get(id);if(!Set.of("QUEUED","RUNNING").contains(job.get("state")))return job;Thread.sleep(10);}while(System.nanoTime()<end);fail("rollout did not finish");return null;}
    @Test void detachedRemoteWorkerIsDispatchedOnceThenOnlyPolledWithFrozenIdentity()throws Exception{
        UUID id=UUID.randomUUID();var calls=new CopyOnWriteArrayList<String>();
        try(var executor=new DeploymentExecutor(p,audit,(action,job,target,file,sha)->{
            calls.add(action+":"+target.getSiteId());
            if(action.equals("prepare"))return reply("PREPARED",job,target,sha);
            if(action.equals("apply")){assertNotNull(file);var value=new LinkedHashMap<>(reply("RUNNING",job,target,sha));value.put("stage","BACKUP");return value;}
            assertEquals("status",action);assertNull(file);return reply("SUCCEEDED",job,target,sha);
        })){
            executor.execute("admin",id,"v2",sha,targets,()->{});assertEquals("SUCCEEDED",done(executor,id).get("state"));
            assertEquals(List.of("prepare:campus","prepare:external","apply:campus","status:campus","apply:external","status:external"),calls);
        }
    }
    @Test void lostPollingReplyDoesNotResendDetachedDispatchAndReconciliationOnlyReadsStatus()throws Exception{
        UUID id=UUID.randomUUID();var calls=new CopyOnWriteArrayList<String>();
        try(var executor=new DeploymentExecutor(p,audit,(action,job,target,file,sha)->{
            calls.add(action);if(action.equals("status"))throw new IOException("simulated lost status response");return reply(action.equals("prepare")?"PREPARED":"RUNNING",job,target,sha);
        })){
            executor.execute("admin",id,"v2",sha,targets,()->{});assertEquals("OUTCOME_UNKNOWN",done(executor,id).get("state"));assertEquals(List.of("prepare","prepare","apply","status"),calls);
        }
        calls.clear();try(var restarted=new DeploymentExecutor(p,audit,(action,job,target,file,sha)->{calls.add(action);assertEquals("status",action);return reply(target.getSiteId().equals("campus")?"SUCCEEDED":"NOT_STARTED",job,target,sha);})){
            assertEquals("PARTIAL_FAILURE",restarted.reconcile("admin",id).get("state"));assertEquals(List.of("status","status"),calls);
        }
    }
    @Test void allPreflightsPrecedeOrderedApplyAndRepeatedExecuteNeverRepeatsEffects()throws Exception{
        var calls=new CopyOnWriteArrayList<String>();UUID id=UUID.randomUUID();
        try(var e=new DeploymentExecutor(p,audit,(action,j,t,file,s)->{calls.add(action+":"+t.getSiteId());assertEquals(sha,DeploymentExecutor.artifactSha(file));return reply(action.equals("prepare")?"PREPARED":"SUCCEEDED",j,t,s);})){
            e.execute("admin",id,"v2",sha,targets,()->{});assertEquals("SUCCEEDED",done(e,id).get("state"));
            e.execute("admin",id,"v2",sha,targets,()->{fail("retry cannot repeat guard/effects");});
            assertEquals(List.of("prepare:campus","prepare:external","apply:campus","apply:external"),calls);
            assertTrue(Files.readString(audit.directory().resolve("audit.jsonl")).contains("DEPLOY_SITE"));
        }
        assertFalse(Files.exists(audit.directory().resolve("artifact-"+id+".jar")));
        try(var restarted=new DeploymentExecutor(p,audit)){assertEquals("SUCCEEDED",restarted.get(id).get("state"));}
    }
    @Test void failedPreparePreventsEveryApply()throws Exception{
        var calls=new CopyOnWriteArrayList<String>();UUID id=UUID.randomUUID();try(var e=new DeploymentExecutor(p,audit,(action,j,t,file,s)->{calls.add(action);if(t.getSiteId().equals("external"))throw new IOException();return reply("PREPARED",j,t,s);})){
            e.execute("admin",id,"v2",sha,targets,()->{});assertEquals("FAILED",done(e,id).get("state"));assertEquals(List.of("prepare","prepare"),calls);
        }
    }
    @Test void failedSecondSiteRecordsPartialRolloutAndStops()throws Exception{
        UUID id=UUID.randomUUID();try(var e=new DeploymentExecutor(p,audit,(action,j,t,file,s)->reply(action.equals("prepare")?"PREPARED":t.getSiteId().equals("external")?"ROLLED_BACK":"SUCCEEDED",j,t,s))){
            e.execute("admin",id,"v2",sha,targets,()->{});var job=done(e,id);assertEquals("PARTIAL_FAILURE",job.get("state"));assertEquals("ROLLED_BACK",job.get("reason"));
        }
    }
    @Test void ambiguousRemoteResponseFencesNewRolloutsAndDoesNotReapply()throws Exception{
        UUID id=UUID.randomUUID();var calls=new AtomicInteger();try(var e=new DeploymentExecutor(p,audit,(action,j,t,file,s)->{calls.incrementAndGet();if(action.equals("apply"))throw new IOException();return reply("PREPARED",j,t,s);})){
            e.execute("admin",id,"v2",sha,targets,()->{});assertEquals("OUTCOME_UNKNOWN",done(e,id).get("state"));
            e.execute("admin",id,"v2",sha,targets,()->{});assertEquals(3,calls.get());
            assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",assertThrows(ControlFailure.class,()->e.execute("admin",UUID.randomUUID(),"v2",sha,targets,()->{})).code);
        }
    }
    @Test void reconciliationReadsRemoteJournalsWithoutReplayingUpdates()throws Exception{
        var actions=new CopyOnWriteArrayList<String>();var statusAvailable=new AtomicBoolean();UUID id=UUID.randomUUID();
        try(var e=new DeploymentExecutor(p,audit,(action,j,t,file,s)->{
            actions.add(action);if(action.equals("prepare"))return reply("PREPARED",j,t,s);
            if(action.equals("status")&&statusAvailable.get())return reply(t.getSiteId().equals("campus")?"SUCCEEDED":"NOT_STARTED",j,t,s);
            throw new IOException();
        })){
            e.execute("admin",id,"v2",sha,targets,()->{});assertEquals("OUTCOME_UNKNOWN",done(e,id).get("state"));
            assertEquals("OUTCOME_UNKNOWN",e.reconcile("admin",id).get("state"));statusAvailable.set(true);
            assertEquals("PARTIAL_FAILURE",e.reconcile("admin",id).get("state"));assertEquals(1,actions.stream().filter(a->a.equals("apply")).count());
            assertEquals(4,actions.stream().filter(a->a.equals("status")).count());
        }
    }
    @Test void twoExecutorInstancesShareOneRolloutLock()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);UUID id=UUID.randomUUID();
        try(var first=new DeploymentExecutor(p,audit,(action,j,t,file,s)->{entered.countDown();release.await();return reply(action.equals("prepare")?"PREPARED":"SUCCEEDED",j,t,s);});var second=new DeploymentExecutor(p,audit)){
            first.execute("admin",id,"v2",sha,targets,()->{});assertTrue(entered.await(2,TimeUnit.SECONDS));
            try{assertEquals("DEPLOYMENT_ALREADY_ACTIVE",assertThrows(ControlFailure.class,()->second.execute("admin",UUID.randomUUID(),"v2",sha,targets,()->{})).code);}finally{release.countDown();}
            assertEquals("SUCCEEDED",done(first,id).get("state"));
        }
    }
    @Test void artifactMutationAfterPreviewOrDuringSpoolIsRejected()throws Exception{
        Files.writeString(artifact,"tampered");try(var e=new DeploymentExecutor(p,audit)){
            assertEquals("ARTIFACT_SHA256_MISMATCH",assertThrows(ControlFailure.class,()->e.execute("admin",UUID.randomUUID(),"v2",sha,targets,()->{})).code);
        }
    }
    @Test void leaderOrEpochGuardIsRecheckedBeforeRemoteEffects()throws Exception{
        var lostLease=new java.util.concurrent.atomic.AtomicBoolean();var calls=new AtomicInteger();UUID id=UUID.randomUUID();try(var e=new DeploymentExecutor(p,audit,(action,j,t,file,s)->{calls.incrementAndGet();lostLease.set(true);return reply("PREPARED",j,t,s);})){
            e.execute("admin",id,"v2",sha,targets,()->{if(lostLease.get())throw new ControlFailure(503,"CONTROL_LEASE_NOT_HELD");});assertEquals("FAILED",done(e,id).get("state"));assertEquals(1,calls.get());
        }
    }
    @Test void defaultDisabledAndMissingCompatibilityIdentityAndControlHostBlockExecution(){
        p.getDeployment().setEnabled(false);try(var e=new DeploymentExecutor(p,audit)){assertEquals(423,assertThrows(ControlFailure.class,()->e.execute("admin",UUID.randomUUID(),"v2",sha,targets,()->{})).status);}
        p.getDeployment().setEnabled(true);p.getDeployment().getTargets().get("campus").setControlHost(true);p.getReleases().get("v2").setRollbackCompatible(false);
        try(var e=new DeploymentExecutor(p,audit)){assertTrue(e.blockers("v2",targets).contains("CONTROL_HOST_REQUIRES_SEPARATE_EXECUTOR:campus"));assertEquals("DEPLOYMENT_PREREQUISITES_REQUIRED",assertThrows(ControlFailure.class,()->e.execute("admin",UUID.randomUUID(),"v2",sha,targets,()->{})).code);}
    }
    @Test void remoteShellMetacharactersAndInsecurePrivateKeyFailStartup()throws Exception{
        p.getDeployment().getTargets().get("campus").setHost("host;touch /tmp/pwn");assertThrows(ControlFailure.class,()->new DeploymentExecutor(p,audit));
        p.getDeployment().getTargets().get("campus").setHost("campus.invalid");Files.setPosixFilePermissions(p.getDeployment().getIdentityFile(),PosixFilePermissions.fromString("rw-r--r--"));assertEquals("PRIVATE_DEPLOYMENT_KEY_REQUIRED",assertThrows(ControlFailure.class,()->new DeploymentExecutor(p,audit)).code);
    }
    @Test void actualProcessTransportUsesPinnedNoninteractiveSshAndConsumesStdin()throws Exception{
        Path ssh=root.resolve("ssh-fixture");Files.writeString(ssh,"""
            #!/usr/bin/python3
            import sys,json
            args=sys.argv[1:]
            assert 'StrictHostKeyChecking=yes' in args and 'BatchMode=yes' in args and 'IdentitiesOnly=yes' in args
            remote=args[-1].split()
            _,agent,config,action,j,sha,site,instance,old=remote
            data=sys.stdin.buffer.read()
            assert action!='apply' or data==b'candidate-v2'
            print(json.dumps(dict(id=j,siteId=site,instanceId=instance,artifactSha256=sha,state='PREPARED' if action=='prepare' else 'SUCCEEDED')))
            """);Files.setPosixFilePermissions(ssh,PosixFilePermissions.fromString("rwx------"));p.getDeployment().setSshExecutable(ssh);
        UUID id=UUID.randomUUID();try(var e=new DeploymentExecutor(p,audit)){e.execute("admin",id,"v2",sha,targets,()->{});assertEquals("SUCCEEDED",done(e,id).get("state"));}
    }
    @Test void stalePersistedRunningJobIsUnknownAndPreventsNewWork()throws Exception{
        UUID id=UUID.randomUUID();Files.writeString(audit.directory().resolve("deployment-"+id+".json"),"{\"id\":\""+id+"\",\"state\":\"RUNNING\"}");
        try(var e=new DeploymentExecutor(p,audit)){assertEquals("OUTCOME_UNKNOWN",e.get(id).get("state"));assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",assertThrows(ControlFailure.class,()->e.execute("admin",UUID.randomUUID(),"v2",sha,targets,()->{})).code);}
    }
    @Test void registryTransportNeedsNoSecondSshKeyAndPrivateBindingsNeverEscapeProgress()throws Exception{
        p.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);p.getDeployment().setKnownHostsFile(null);p.getDeployment().setIdentityFile(null);var calls=new CopyOnWriteArrayList<String>();
        DeploymentExecutor.RegisteredTransport transport=new DeploymentExecutor.RegisteredTransport(){
            public boolean available(){return true;}
            public ControlProperties.DeploymentTarget resolve(DeploymentExecutor.Target expected){var t=p.getDeployment().getTargets().get(expected.key());t.setServerId(UUID.nameUUIDFromBytes(expected.key().getBytes()));t.setServerRevision(1);t.setConnectionFingerprint("stable-binding-"+expected.key());t.setEncryptedConnection("private-encrypted-connection-"+UUID.randomUUID());return t;}
            public Map<String,Object> call(String action,UUID id,ControlProperties.DeploymentTarget target,Path artifact,String sha){calls.add(action+":"+target.getSiteId());return reply(action.equals("prepare")?"PREPARED":"SUCCEEDED",id,target,sha);}
        };
        try(var executor=new DeploymentExecutor(p,audit)){executor.registeredTransport(transport);assertEquals(executor.configurationHash("v2",targets),executor.configurationHash("v2",targets));UUID id=UUID.randomUUID();executor.execute("admin",id,"v2",sha,targets,()->{});var result=done(executor,id);assertEquals("SUCCEEDED",result.get("state"));assertEquals(List.of("prepare:campus","prepare:external","apply:campus","apply:external"),calls);assertFalse(result.toString().contains("private-encrypted"));assertFalse(result.containsKey("_targets"));assertTrue(Files.readString(audit.directory().resolve("deployment-"+id+".json")).contains("private-encrypted"));}
    }
    @Test void reconciliationKeepsTheOriginalDestinationEvenIfSettingsChangeAfterLostAcknowledgement()throws Exception{
        UUID id=UUID.randomUUID();var hosts=new CopyOnWriteArrayList<String>();
        try(var executor=new DeploymentExecutor(p,audit,(action,job,target,file,digest)->{if(action.equals("prepare"))return reply("PREPARED",job,target,digest);if(action.equals("apply"))throw new IOException();hosts.add(target.getHost());return reply("NOT_STARTED",job,target,digest);})){executor.execute("admin",id,"v2",sha,targets,()->{});assertEquals("OUTCOME_UNKNOWN",done(executor,id).get("state"));p.getDeployment().getTargets().get("campus").setHost("different.invalid");assertEquals("FAILED",executor.reconcile("admin",id).get("state"));assertEquals(List.of("campus.invalid","external.invalid"),hosts);}
    }
    @Test void changedPreviewBindingCannotSlipThroughTheCrossProcessAdmissionLock(){
        var effects=new AtomicInteger();try(var executor=new DeploymentExecutor(p,audit,(action,id,target,file,sha)->{effects.incrementAndGet();throw new AssertionError();})){String expected=executor.configurationHash("v2",targets);assertEquals("DEPLOYMENT_PLAN_RECREATE_REQUIRED",assertThrows(ControlFailure.class,()->executor.execute("admin",UUID.randomUUID(),"v2",sha,targets,expected,()->p.getDeployment().getTargets().get("campus").setHost("edited.invalid"))).code);assertEquals(0,effects.get());}
    }
    @Test void unresolvedRegisteredProtocolNeverEnablesAnExecutablePlan(){
        p.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);try(var executor=new DeploymentExecutor(p,audit)){executor.registeredTransport(new DeploymentExecutor.RegisteredTransport(){public boolean available(){return false;}public ControlProperties.DeploymentTarget resolve(DeploymentExecutor.Target target){return p.getDeployment().getTargets().get(target.key());}public Map<String,Object> call(String action,UUID id,ControlProperties.DeploymentTarget target,Path file,String sha){fail("Unreviewed remote protocol must never run");return null;}});assertTrue(executor.blockers("v2",targets).contains("REGISTERED_DEPLOYMENT_PROTOCOL_NOT_READY"));assertEquals("DEPLOYMENT_PREREQUISITES_REQUIRED",assertThrows(ControlFailure.class,()->executor.execute("admin",UUID.randomUUID(),"v2",sha,targets,()->{})).code);}
    }
    @Test void legacyUnknownJournalWithoutFrozenDestinationCannotBeReconciledAgainstCurrentSettings()throws Exception{
        UUID id=UUID.randomUUID();var rows=targets.stream().map(t->Map.of("site",t.key(),"siteId",t.siteId(),"instanceId",t.instanceId().toString(),"fromSha256","a".repeat(64))).toList();var mapper=tools.jackson.databind.json.JsonMapper.builder().build();Files.writeString(audit.directory().resolve("deployment-"+id+".json"),mapper.writeValueAsString(Map.of("id",id.toString(),"releaseId","v2","artifactSha256",sha,"state","RUNNING","targets",rows)));var calls=new AtomicInteger();try(var executor=new DeploymentExecutor(p,audit,(action,j,t,file,sha)->{calls.incrementAndGet();throw new AssertionError();})){var result=executor.reconcile("admin",id);assertEquals("OUTCOME_UNKNOWN",result.get("state"));assertEquals("FROZEN_DEPLOYMENT_BINDING_REQUIRED",result.get("reason"));assertEquals(0,calls.get());}
    }
    @Test void remoteSuccessWithUnrecordedRegistryVersionRemainsUnknownAndReconciliationNeverReapplies()throws Exception{
        p.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);var failRecord=new AtomicBoolean(true);var applies=new AtomicInteger();
        var transport=new DeploymentExecutor.RegisteredTransport(){public boolean available(){return true;}public ControlProperties.DeploymentTarget resolve(DeploymentExecutor.Target expected){var t=p.getDeployment().getTargets().get(expected.key());t.setServerId(UUID.nameUUIDFromBytes(expected.key().getBytes()));t.setServerRevision(1);t.setEncryptedConnection("synthetic-private-binding");return t;}public Map<String,Object> call(String action,UUID job,ControlProperties.DeploymentTarget t,Path file,String sha){if(action.equals("apply"))applies.incrementAndGet();return reply(action.equals("prepare")?"PREPARED":action.equals("status")&&t.getSiteId().equals("external")?"NOT_STARTED":"SUCCEEDED",job,t,sha);}public void record(UUID job,ControlProperties.DeploymentTarget t,String sha,Map<String,Object> reply){if(reply.get("state").equals("SUCCEEDED")&&failRecord.get())throw new ControlFailure(503,"SERVER_PRIVATE_STATE_UNAVAILABLE");}};
        try(var executor=new DeploymentExecutor(p,audit)){executor.registeredTransport(transport);UUID id=UUID.randomUUID();executor.execute("admin",id,"v2",sha,targets,()->{});assertEquals("OUTCOME_UNKNOWN",done(executor,id).get("state"));failRecord.set(false);assertEquals("PARTIAL_FAILURE",executor.reconcile("admin",id).get("state"));assertEquals(1,applies.get());}
    }
    final class FencedRegistry implements DeploymentExecutor.RegisteredTransport {
        final List<String> calls=new CopyOnWriteArrayList<>();final Set<String> applied=ConcurrentHashMap.newKeySet();boolean uncertainReserve,rejectedReserve,failFinalization,noDispatchProof;volatile boolean finalized;int reservations;
        public boolean available(){return true;}
        public ControlProperties.DeploymentTarget resolve(DeploymentExecutor.Target expected){var t=p.getDeployment().getTargets().get(expected.key());t.setServerId(UUID.nameUUIDFromBytes(expected.key().getBytes()));t.setServerRevision(1);t.setEncryptedConnection("synthetic-private-connection");return t;}
        public void reserve(UUID id,String manifest,List<ControlProperties.DeploymentTarget> targets)throws Exception{reservations++;if(rejectedReserve)throw new DeploymentExecutor.AdmissionRejected("MEMBERSHIP_CHANGE_PENDING");if(uncertainReserve)throw new IOException("Lost database commit acknowledgement");}
        public Map<String,Object> call(String action,UUID id,ControlProperties.DeploymentTarget t,Path file,String digest){calls.add(action);if(action.equals("apply"))applied.add(t.getSiteId());return reply(action.equals("prepare")?"PREPARED":applied.contains(t.getSiteId())?"SUCCEEDED":"NOT_STARTED",id,t,digest);}
        public void complete(UUID id,String manifest,Map<String,Object> job){if(failFinalization)throw new ControlFailure(503,"DEPLOYMENT_FENCE_UNAVAILABLE");finalized=true;}
        public boolean finalized(UUID id,String manifest,Map<String,Object> job){return finalized;}
        public boolean noDispatchProven(UUID id,String manifest){return noDispatchProof;}
    }
    @Test void positivelyRejectedAdmissionMakesNoRemoteCallAndDoesNotLeaveAnUnknownFence()throws Exception{
        p.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);var transport=new FencedRegistry();transport.rejectedReserve=true;try(var executor=new DeploymentExecutor(p,audit)){executor.registeredTransport(transport);UUID id=UUID.randomUUID();var result=executor.execute("admin",id,"v2",sha,targets,()->{});assertEquals("FAILED",result.get("state"));assertEquals("MEMBERSHIP_CHANGE_PENDING",result.get("reason"));assertTrue(transport.calls.isEmpty());assertNull(executor.whenIdle(()->{}));assertFalse(Files.exists(audit.directory().resolve("artifact-"+id+".jar")));}
    }
    @Test void ambiguousReservationIsUnknownUntilReadOnlyProofAndIsNeverResent()throws Exception{
        p.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);var transport=new FencedRegistry();transport.uncertainReserve=true;try(var executor=new DeploymentExecutor(p,audit)){executor.registeredTransport(transport);UUID id=UUID.randomUUID();assertEquals("OUTCOME_UNKNOWN",executor.execute("admin",id,"v2",sha,targets,()->{}).get("state"));assertTrue(transport.calls.isEmpty());executor.execute("admin",id,"v2",sha,targets,()->{});assertEquals(1,transport.reservations);assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",executor.whenIdle(()->{}));assertEquals("FAILED",executor.reconcile("admin",id).get("state"));assertEquals(List.of("status","status"),transport.calls);assertTrue(transport.finalized);}
    }
    @Test void finalizationFailureNeverPublishesSuccessAndCanBeReconciledWithoutRepeatingApply()throws Exception{
        p.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);var transport=new FencedRegistry();transport.failFinalization=true;try(var executor=new DeploymentExecutor(p,audit)){executor.registeredTransport(transport);UUID id=UUID.randomUUID();executor.execute("admin",id,"v2",sha,targets,()->{});var result=done(executor,id);assertEquals("OUTCOME_UNKNOWN",result.get("state"));assertEquals("DEPLOYMENT_FENCE_FINALIZATION_REQUIRED",result.get("reason"));assertEquals(2,transport.calls.stream().filter("apply"::equals).count());transport.failFinalization=false;assertEquals("SUCCEEDED",executor.reconcile("admin",id).get("state"));assertEquals(2,transport.calls.stream().filter("apply"::equals).count());}
    }
    @Test void terminalLocalReceiptAfterCrashCannotHideAnUnfinalizedAuthorityFence()throws Exception{
        p.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);var transport=new FencedRegistry();transport.failFinalization=true;UUID id=UUID.randomUUID();try(var executor=new DeploymentExecutor(p,audit)){executor.registeredTransport(transport);executor.execute("admin",id,"v2",sha,targets,()->{});assertEquals("OUTCOME_UNKNOWN",done(executor,id).get("state"));}
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();Path file=audit.directory().resolve("deployment-"+id+".json");var job=mapper.readValue(Files.readString(file),LinkedHashMap.class);job.put("state","SUCCEEDED");job.remove("_fenceFinalized");Files.writeString(file,mapper.writeValueAsString(job));try(var restarted=new DeploymentExecutor(p,audit)){restarted.registeredTransport(transport);assertEquals("OUTCOME_UNKNOWN",restarted.get(id).get("state"));assertEquals("DEPLOYMENT_RECONCILIATION_REQUIRED",restarted.whenIdle(()->{}));transport.failFinalization=false;assertEquals("SUCCEEDED",restarted.reconcile("admin",id).get("state"));assertEquals(2,transport.calls.stream().filter("apply"::equals).count());}
    }
    @Test void authorityNoDispatchProofCanResolveAnUnadmittedReleaseWithoutContactingOrReplayingAnyTarget()throws Exception{
        p.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);var transport=new FencedRegistry();transport.uncertainReserve=true;transport.noDispatchProof=true;try(var executor=new DeploymentExecutor(p,audit)){executor.registeredTransport(transport);UUID id=UUID.randomUUID();assertEquals("OUTCOME_UNKNOWN",executor.execute("admin",id,"v2",sha,targets,()->{}).get("state"));var result=executor.reconcile("admin",id);assertEquals("FAILED",result.get("state"));assertEquals("AUTHORITY_NO_DISPATCH_PROVEN",result.get("reason"));assertTrue(transport.calls.isEmpty());assertEquals(1,transport.reservations);}
    }
    @Test void anOlderLocalNoDispatchFlagCannotOverrideAuthorityEvidenceThatEffectsWereAuthorized()throws Exception{
        p.getDeployment().setTargetSource(ControlProperties.Deployment.TargetSource.REGISTERED_SERVERS);var transport=new FencedRegistry();transport.failFinalization=true;try(var executor=new DeploymentExecutor(p,audit)){executor.registeredTransport(transport);UUID id=UUID.randomUUID();executor.execute("admin",id,"v2",sha,targets,()->{});assertEquals("OUTCOME_UNKNOWN",done(executor,id).get("state"));var mapper=tools.jackson.databind.json.JsonMapper.builder().build();Path file=audit.directory().resolve("deployment-"+id+".json");var saved=mapper.readValue(Files.readString(file),LinkedHashMap.class);saved.put("_remoteDispatchAllowed",false);Files.writeString(file,mapper.writeValueAsString(saved));transport.failFinalization=false;assertEquals("SUCCEEDED",executor.reconcile("admin",id).get("state"));assertEquals(2,transport.calls.stream().filter("status"::equals).count());assertEquals(2,transport.calls.stream().filter("apply"::equals).count());}
    }
}

package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import you.v50to.eatwhat.control.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Real signed originals, two PostgreSQL JOINs and bundled target phase-proof
 * validation. SOURCE process/files and remote target health are explicit mocks;
 * this is not retained Linux SSH or production acceptance. */
class ControlBootstrapSourceInspectionIT {
    @TempDir static Path pki;@TempDir Path root;
    ControlBootstrapVerifyIT fixture;ControlBootstrapAdmissionIT base;
    @BeforeAll static void start()throws Exception{ControlBootstrapVerifyIT.pki=pki;ControlBootstrapVerifyIT.start();}
    @AfterAll static void stop(){ControlBootstrapVerifyIT.stop();}
    @BeforeEach void setup()throws Exception{fixture=new ControlBootstrapVerifyIT();fixture.root=root;fixture.setup();base=fixture.base;base.managedPreview=true;}
    @AfterEach void close(){fixture.close();}
    class Observer {
        boolean sourceChanged;int sourceReads;
        final ControlBootstrapInspection inspection;
        Observer(ControlBootstrapVerifyIT.Case c){
            var f=c.member.profile.frozen;
            var source=mock(ControlBootstrapSources.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).defaultAnswer(inv->{
                if(!inv.getMethod().getName().equals("inspectOriginal"))throw new AssertionError("Independent observation cannot use planning/execution collection");
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(base.sourceId,inv.getArgument(0));assertEquals(1L,(long)inv.getArgument(1));assertEquals(f.sourceRoute(),inv.getArgument(2));
                var request=(ControlBootstrapSources.Request)inv.getArgument(3);var original=c.member.profile.pair.sourceCaptureRequest();var e=original.expected();assertEquals(2L,request.expected().epoch());
                assertEquals(e.applicationSha256(),request.expected().applicationSha256());assertEquals(e.profilesSha256(),request.expected().profilesSha256());assertEquals(original.sourceArtifactSha256(),request.sourceArtifactSha256());assertEquals(original.probeArtifactSha256(),request.probeArtifactSha256());
                var hashes=new HashMap<>(c.member.profile.pair.sourceCaptureFileHashes());if(sourceChanged)hashes.put("application.properties","0".repeat(64));sourceReads++;
                return new ControlBootstrapSources.Captured(base.sourceId,1,f.sourceRoute(),Map.of("fileHashes",hashes,"sourceProcess",f.sourceEvidence().get("sourceProcess")),Map.of());
            }));
            var origin=new ControlBootstrapSourceInspection(c.member.profile.state,c.member.profile.nativePhases,source);
            inspection=new ControlBootstrapInspection(base.journal,c.member.profile.nativePhases,c.member.manager,c.member.runtime,origin);
        }
    }
    Map<String,byte[]> files()throws Exception{var result=new TreeMap<String,byte[]>();try(var paths=Files.walk(root)){for(var path:paths.filter(Files::isRegularFile).toList())result.put(root.relativize(path).toString(),Files.readAllBytes(path));}return result;}
    Map<String,String> sql(ControlBootstrapVerifyIT.Case c){
        var result=new TreeMap<String,String>();int index=0;for(var db:List.of(base.db,c.member.target)){
            for(String name:List.of("command","audit","change","change_vote","member","local_state","local_change","local_phase"))
                result.put(index+":"+name,db.runtime().queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM eatwhat_cluster."+name+" t",String.class));index++;
        }return result;
    }
    void unchanged(ControlBootstrapVerifyIT.Case c,Runnable check)throws Exception{
        var before=files();var state=sql(c);check.run();var after=files();assertEquals(before.keySet(),after.keySet());before.forEach((name,bytes)->assertArrayEquals(bytes,after.get(name),name));assertEquals(state,sql(c));
    }
    @Test void explicitCurrentInspectionVerifiesBothSidesWithoutUpdatingTheSuccessfulOriginal()throws Exception{try(var c=fixture.new Case()){
        assertEquals("SUCCEEDED",c.advance().get("state"));var observer=new Observer(c);int calls=c.observations.get();
        unchanged(c,()->{
            assertEquals("TERMINAL_HISTORY_ONLY",observer.inspection.inspect(base.spec.id()).get("inspectionState"));assertEquals(0,observer.sourceReads);assertEquals(calls,c.observations.get());
            var result=observer.inspection.inspect(base.spec.id(),true);assertEquals("CURRENT_STATE",result.get("inspectionMode"));assertEquals("ORIGINAL_MEMBERSHIP_STATUS_OBSERVED",result.get("inspectionState"));
            var seen=(Map<?,?>)result.get("observation");assertEquals(true,seen.get("targetRuntimeVerified"));assertEquals(true,seen.get("sourceConfigurationRevalidated"));assertEquals(true,seen.get("sourceProcessRevalidated"));
            assertEquals(false,result.get("canResume"));assertEquals(false,result.get("jobChangedDuringInspection"));assertEquals("SUCCEEDED",base.journal.job(base.spec.id()).get("state"));
        });assertEquals(1,observer.sourceReads);
    }}
    @Test void currentSourceDriftDoesNotRewriteTheSuccessfulDeploymentOrExposeItsPrivateEvidence()throws Exception{try(var c=fixture.new Case()){
        c.advance();var observer=new Observer(c);observer.sourceChanged=true;
        unchanged(c,()->{var result=observer.inspection.inspect(base.spec.id(),true);assertEquals("OBSERVATION_UNAVAILABLE",result.get("inspectionState"));assertEquals("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED",result.get("reason"));assertFalse(result.containsKey("observation"));assertEquals("SUCCEEDED",base.journal.job(base.spec.id()).get("state"));});
    }}
    @Test void invalidatedApprovalCanBeInspectedWithBothFreshProofsButNeverBecomesExecutable()throws Exception{try(var c=fixture.new Case()){
        var lost=mock(ControlBootstrapJournal.class,withSettings().mockMaker(org.mockito.MockMakers.INLINE).spiedInstance(base.journal).defaultAnswer(CALLS_REAL_METHODS));
        doThrow(new ControlFailure(503,"FIXTURE_LOST_FINAL_WRITE")).when(lost).observe(eq(base.spec.id()),eq(VERIFY),anyString(),anyMap());
        assertThrows(ControlFailure.class,()->c.verifier(lost).advance(c.member.profile.frozen));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(base.journal,"executionObservation",base.spec.id(),"BOOTSTRAP_ORIGINAL_CONTROLLER_REQUIRED",true);
        base.db.admin().update("UPDATE users SET banned=true WHERE id=?",Long.parseLong(base.actor));var observer=new Observer(c);
        unchanged(c,()->{var result=observer.inspection.inspect(base.spec.id());assertEquals("ORIGINAL_MEMBERSHIP_STATUS_OBSERVED",result.get("inspectionState"));assertEquals(false,result.get("canResume"));assertEquals(false,result.get("approvalGranted"));
            assertEquals(true,base.journal.job(base.spec.id()).get("requiresReapproval"));assertNotEquals("SUCCEEDED",base.journal.job(base.spec.id()).get("state"));});
    }}
}

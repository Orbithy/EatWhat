package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Routing/redaction tests; transport is mocked. Original encrypted input and
 * immutable journal behavior are covered by NativePhasesTest. */
class ControlBootstrapInspectionTest {
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    final UUID id=UUID.randomUUID();
    final ControlBootstrapJournal journal=stub(ControlBootstrapJournal.class);
    final ControlBootstrapNativePhases nativePhases=stub(ControlBootstrapNativePhases.class);
    final ControlBootstrapAdmission.Frozen frozen=stub(ControlBootstrapAdmission.Frozen.class);
    final ControlBootstrapMembership membership=stub(ControlBootstrapMembership.class);
    final ControlBootstrapMemberRuntime runtime=stub(ControlBootstrapMemberRuntime.class);
    final ControlBootstrapSourceInspection source=stub(ControlBootstrapSourceInspection.class);
    final ControlBootstrapInspection inspection=new ControlBootstrapInspection(journal,nativePhases,membership,runtime,source);
    Map<String,Object> job(String state,String phaseState){
        return Map.of("id",id.toString(),"requestHash","a".repeat(64),"state",state,"requiresReapproval",true,
                "steps",List.of(Map.of("phase","SOURCE_RUNTIME","id",ControlBootstrapJournal.phaseId(id,SOURCE_RUNTIME).toString(),"state",phaseState)));
    }
    void given(Map<String,Object> value){when(journal.job(id)).thenReturn(value);when(journal.originalAdmission(id,false)).thenReturn(frozen);}
    void givenPhase(ControlBootstrapJournal.Phase phase){
        var retained=new LinkedHashMap<>(job("OUTCOME_UNKNOWN","DISPATCHED"));retained.put("steps",List.of(Map.of("phase",phase.name(),"id",ControlBootstrapJournal.phaseId(id,phase).toString(),"state","DISPATCHED")));given(retained);
        if(phase==VERIFY)when(source.inspectJoined(frozen)).thenAnswer(inv->Map.of("sourceConfigurationRevalidated",true,"sourceProcessRevalidated",true,"sourceCheckedAt",java.time.Instant.now().toString()));
    }
    @Test void authenticatedGetCannotGrantApprovalOrLeakRemotePrivateFields(){
        given(job("OUTCOME_UNKNOWN","DISPATCHED"));
        when(nativePhases.inspectSaved(frozen,SOURCE_RUNTIME)).thenReturn(Map.of("state","RUNTIME_READY","privateKey","never-public","publicKey","not-needed","arbitrarySecret","never-public","effectsStarted",true));
        var report=inspection.inspect(id);assertEquals("ORIGINAL_NATIVE_STATUS_OBSERVED",report.get("inspectionState"));
        assertEquals(Map.of("state","RUNTIME_READY","effectsStarted",true),report.get("observation"));assertFalse(report.toString().contains("never-public"));
        assertEquals(false,report.get("canResume"));assertEquals(false,report.get("approvalGranted"));assertEquals(false,report.get("jobChangedDuringInspection"));
        verify(journal,never()).executionObservation(any(),anyString(),anyBoolean());verify(journal,never()).observe(any(),any(),any(),any());
    }
    @Test void failedRetainedPhaseCanBeInspectedButNeverBecomesAResumedJob(){
        given(job("FAILED","FAILED"));when(nativePhases.inspectSaved(frozen,SOURCE_RUNTIME)).thenReturn(Map.of("state","FAILED"));
        assertEquals("ORIGINAL_NATIVE_STATUS_OBSERVED",inspection.inspect(id).get("inspectionState"));verify(nativePhases).inspectSaved(frozen,SOURCE_RUNTIME);
    }
    @Test void pendingAndSuccessfulHistoryDoNotContactRemoteHosts(){
        given(job("QUEUED","PENDING"));assertEquals("NOT_DISPATCHED",inspection.inspect(id).get("inspectionState"));
        given(job("SUCCEEDED","SUCCEEDED"));assertEquals("TERMINAL_HISTORY_ONLY",inspection.inspect(id).get("inspectionState"));
        verify(nativePhases,never()).inspectSaved(any(),any());
    }
    @Test void failuresAreBoundedAndDoNotEchoArbitraryExceptionMessages(){
        given(job("OUTCOME_UNKNOWN","DISPATCHED"));when(nativePhases.inspectSaved(frozen,SOURCE_RUNTIME)).thenThrow(new ControlFailure(503,"secret-untrusted-output"));
        var report=inspection.inspect(id);assertEquals("BOOTSTRAP_INSPECTION_UNCONFIRMED",report.get("reason"));assertFalse(report.toString().contains("secret-untrusted-output"));
        doReturn(Map.of("state","RUNTIME_READY")).when(nativePhases).inspectSaved(frozen,SOURCE_RUNTIME);assertEquals("ORIGINAL_NATIVE_STATUS_OBSERVED",inspection.inspect(id).get("inspectionState"));
    }
    @Test void liveWorkerChangesMakeTheSnapshotExplicitlyInconclusive(){
        var before=job("OUTCOME_UNKNOWN","DISPATCHED");given(before);when(journal.job(id)).thenReturn(before,job("QUEUED","SUCCEEDED"));
        when(nativePhases.inspectSaved(frozen,SOURCE_RUNTIME)).thenReturn(Map.of("state","RUNTIME_READY"));assertEquals(true,inspection.inspect(id).get("jobChangedDuringInspection"));
    }
    @Test void currentAdministratorAuthorizationPrecedesAnyPrivateOrRemoteInspection(){
        var auth=stub(ControlController.class);var controller=new ControlBootstrapInspectionController(auth,inspection);
        when(auth.admin()).thenThrow(new ControlFailure(403,"ADMIN_REQUIRED"));assertThrows(ControlFailure.class,()->controller.get(id,false));verifyNoInteractions(journal,nativePhases);
    }
    @Test void componentSuccessCannotBecomeAParentSuccessOrGrantResume(){
        givenPhase(MEMBER_CONFIGURE);when(nativePhases.inspectSaved(frozen,MEMBER_CONFIGURE)).thenReturn(Map.of("state","ROLE_READY","inspectionComponent","MEMBER_AUTH_ROLE","privateRolePassword","never-public"));
        var report=inspection.inspect(id);assertEquals(Map.of("state","ROLE_READY","component","MEMBER_AUTH_ROLE"),report.get("observation"));
        assertEquals(true,report.get("parentPhaseCompletionNotInferred"));assertEquals(false,report.get("canResume"));assertFalse(report.toString().contains("never-public"));
        verify(journal,never()).observe(any(),any(),any(),any());
    }
    @Test void httpRouteAcceptsOnlyGetAndReturnsTheRedactedObservation()throws Exception{
        given(job("OUTCOME_UNKNOWN","DISPATCHED"));when(nativePhases.inspectSaved(frozen,SOURCE_RUNTIME)).thenReturn(Map.of("state","RUNTIME_READY","privateKey","never-public"));
        var auth=stub(ControlController.class);var controller=new ControlBootstrapInspectionController(auth,inspection);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();String url="/admin/control/v1/controller-jobs/"+id+"/inspection";
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)).andReturn().getResponse();
        assertEquals(200,response.getStatus());assertTrue(response.getContentAsString().contains("RUNTIME_READY"));assertFalse(response.getContentAsString().contains("never-public"));verify(auth).admin();
        clearInvocations(auth,journal,nativePhases);
        assertEquals(405,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)).andReturn().getResponse().getStatus());verifyNoInteractions(auth,journal,nativePhases);
    }
    @Test void joinInspectionReadsOwnedSqlFactsWithoutCallingTheAdvancerOrSsh(){
        givenPhase(MEMBER_JOIN);when(membership.inspectOriginal(frozen)).thenReturn(new ControlBootstrapMembership.Inspection(Map.of("state","PREPARING","inspectionComponent","MEMBER_JOIN","joinCommandObserved",true,"membershipBinding","never-public","clusterEpoch",1L),Optional.empty()));
        var report=inspection.inspect(id);assertEquals("ORIGINAL_MEMBERSHIP_STATUS_OBSERVED",report.get("inspectionState"));assertFalse(report.toString().contains("never-public"));
        var seen=(Map<?,?>)report.get("observation");assertEquals("PREPARING",seen.get("state"));assertEquals(false,seen.get("targetRuntimeVerified"));assertEquals(false,seen.get("sourceConfigurationRevalidated"));
        verify(membership).inspectOriginal(frozen);verifyNoMoreInteractions(membership);verifyNoInteractions(runtime);verify(nativePhases,never()).inspectSaved(any(),any());
    }
    @Test void finalInspectionRequiresCompleteOwnedJoinProofBeforeRemoteDiagnostics(){
        givenPhase(VERIFY);when(membership.inspectOriginal(frozen)).thenReturn(new ControlBootstrapMembership.Inspection(Map.of("state","APPLIED"),Optional.empty()));
        var report=inspection.inspect(id);assertEquals("BOOTSTRAP_JOIN_CONFIRMATIONS_REQUIRED",report.get("reason"));verifyNoInteractions(runtime);
    }
    @Test void joinedDiagnosticsCannotGrantParentSuccessAndPrivateTargetFieldsNeverEscape(){
        givenPhase(VERIFY);var proof=Map.<String,Object>of("privateBinding","never-public");
        when(membership.inspectOriginal(frozen)).thenReturn(new ControlBootstrapMembership.Inspection(Map.of("state","APPLIED","allJoinConfirmationsObserved",true),Optional.of(proof)));
        when(runtime.inspectJoined(frozen,proof)).thenReturn(Map.of("state","JOINED_VERIFIED","healthCheckedAt",java.time.Instant.now().toString(),"privatePayload","never-public"));
        var report=inspection.inspect(id);var seen=(Map<?,?>)report.get("observation");assertEquals("JOINED_VERIFIED",seen.get("state"));assertEquals(true,seen.get("targetRuntimeVerified"));
        assertEquals(true,seen.get("sourceConfigurationRevalidated"));assertEquals(true,seen.get("sourceProcessRevalidated"));assertEquals(false,report.get("canResume"));assertEquals(true,report.get("parentPhaseCompletionNotInferred"));assertFalse(report.toString().contains("never-public"));
        verify(membership,times(2)).inspectOriginal(frozen);verify(journal,never()).observe(any(),any(),any(),any());
    }
    @Test void membershipChangeDuringSlowTargetDiagnosticsMakesTheResultUnconfirmed(){
        givenPhase(VERIFY);var proof=Map.<String,Object>of("originalJoin","proof");
        when(membership.inspectOriginal(frozen)).thenReturn(new ControlBootstrapMembership.Inspection(Map.of("state","APPLIED"),Optional.of(proof)),new ControlBootstrapMembership.Inspection(Map.of("state","APPLIED"),Optional.empty()));
        when(runtime.inspectJoined(frozen,proof)).thenReturn(Map.of("state","JOINED_VERIFIED","healthCheckedAt",java.time.Instant.now().toString()));
        var report=inspection.inspect(id);assertEquals("OBSERVATION_UNAVAILABLE",report.get("inspectionState"));assertEquals("BOOTSTRAP_MEMBERSHIP_PROOF_CHANGED",report.get("reason"));assertFalse(report.containsKey("observation"));
    }
    @Test void unexpectedDiagnosticExceptionsCannotExposeConnectionOrPrivateFailureText(){
        givenPhase(MEMBER_JOIN);when(membership.inspectOriginal(frozen)).thenThrow(new IllegalStateException("private-connection-detail"));
        var report=inspection.inspect(id);assertEquals("BOOTSTRAP_INSPECTION_UNCONFIRMED",report.get("reason"));assertFalse(report.toString().contains("private-connection-detail"));assertFalse(report.containsKey("observation"));
    }
    @Test void jobReadAdvertisesOnlyTheActuallyWiredInspectionCapabilityWithoutRunningIt(){
        given(job("OUTCOME_UNKNOWN","DISPATCHED"));var auth=stub(ControlController.class);
        var disabled=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var enabled=new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("inspection",inspection));
        assertEquals(false,((Map<?,?>)new ControlBootstrapJobController(auth,journal,disabled.getBeanProvider(ControlBootstrapInspection.class)).get(id)).get("readOnlyInspectionSupported"));
        assertEquals(true,((Map<?,?>)new ControlBootstrapJobController(auth,journal,enabled.getBeanProvider(ControlBootstrapInspection.class)).get(id)).get("readOnlyInspectionSupported"));
        assertEquals(false,((Map<?,?>)new ControlBootstrapJobController(auth,journal,disabled.getBeanProvider(ControlBootstrapInspection.class)).get(id)).get("currentInspectionSupported"));
        assertEquals(true,((Map<?,?>)new ControlBootstrapJobController(auth,journal,enabled.getBeanProvider(ControlBootstrapInspection.class)).get(id)).get("currentInspectionSupported"));
        verifyNoInteractions(nativePhases,membership,runtime);verify(journal,never()).originalAdmission(any(),anyBoolean());
    }
    @Test void sourceConfigurationFailureCannotPublishTheTargetsSuccessfulDiagnosis(){
        givenPhase(VERIFY);var proof=Map.<String,Object>of("ownedJoin","proof");when(membership.inspectOriginal(frozen)).thenReturn(new ControlBootstrapMembership.Inspection(Map.of("state","APPLIED"),Optional.of(proof)));
        when(runtime.inspectJoined(frozen,proof)).thenReturn(Map.of("state","JOINED_VERIFIED","healthCheckedAt",java.time.Instant.now().toString()));
        doThrow(new ControlFailure(409,"BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED")).when(source).inspectJoined(frozen);
        var report=inspection.inspect(id);assertEquals("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED",report.get("reason"));assertFalse(report.containsKey("observation"));assertEquals(false,report.get("canResume"));
    }
    @Test void targetEvidenceThatExpiresDuringSourceObservationCannotBeReportedAsFresh(){
        givenPhase(VERIFY);var proof=Map.<String,Object>of("ownedJoin","proof");when(membership.inspectOriginal(frozen)).thenReturn(new ControlBootstrapMembership.Inspection(Map.of("state","APPLIED"),Optional.of(proof)));
        when(runtime.inspectJoined(frozen,proof)).thenReturn(Map.of("state","JOINED_VERIFIED","healthCheckedAt",java.time.Instant.now().minusSeconds(61).toString()));
        var report=inspection.inspect(id);assertEquals("BOOTSTRAP_JOINED_RUNTIME_NOT_VERIFIED",report.get("reason"));assertFalse(report.containsKey("observation"));
    }
    @Test void explicitCurrentInspectionOfSuccessUsesOriginalVerificationWithoutChangingHistory(){
        var successful=new LinkedHashMap<>(job("SUCCEEDED","SUCCEEDED"));successful.put("steps",List.of(Map.of("phase","VERIFY","id",ControlBootstrapJournal.phaseId(id,VERIFY).toString(),"state","SUCCEEDED")));given(successful);
        var proof=Map.<String,Object>of("ownedJoin","proof");when(membership.inspectOriginal(frozen)).thenReturn(new ControlBootstrapMembership.Inspection(Map.of("state","APPLIED"),Optional.of(proof)));
        when(runtime.inspectJoined(frozen,proof)).thenReturn(Map.of("state","JOINED_VERIFIED","healthCheckedAt",java.time.Instant.now().toString()));
        when(source.inspectJoined(frozen)).thenReturn(Map.of("sourceConfigurationRevalidated",true,"sourceProcessRevalidated",true,"sourceCheckedAt",java.time.Instant.now().toString()));
        assertEquals("TERMINAL_HISTORY_ONLY",inspection.inspect(id).get("inspectionState"));verifyNoInteractions(membership,runtime,source);
        var report=inspection.inspect(id,true);assertEquals("CURRENT_STATE",report.get("inspectionMode"));assertEquals("ORIGINAL_MEMBERSHIP_STATUS_OBSERVED",report.get("inspectionState"));assertEquals(false,report.get("jobChangedDuringInspection"));
        assertEquals(false,report.get("canResume"));verify(journal,never()).observe(any(),any(),any(),any());assertEquals("SUCCEEDED",((Map<?,?>)report.get("jobSnapshot")).get("state"));
    }
    @Test void currentModeCannotBeUsedToSkipTheUnfinishedTaskOrTreatARequestAsApproval(){
        given(job("OUTCOME_UNKNOWN","DISPATCHED"));assertEquals("BOOTSTRAP_CURRENT_INSPECTION_REQUIRES_SUCCESS",assertThrows(ControlFailure.class,()->inspection.inspect(id,true)).code);verifyNoInteractions(nativePhases,membership,runtime,source);
    }
    @Test void httpCurrentFlagIsExplicitAndInvalidValuesCannotReachTheObserver()throws Exception{
        var auth=stub(ControlController.class);var mocked=stub(ControlBootstrapInspection.class);var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ControlBootstrapInspectionController(auth,mocked)).build();
        var url="/admin/control/v1/controller-jobs/"+id+"/inspection";when(mocked.inspect(id,true)).thenReturn(Map.of("inspectionMode","CURRENT_STATE"));
        assertEquals(200,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).param("current","true")).andReturn().getResponse().getStatus());verify(mocked).inspect(id,true);
        clearInvocations(mocked,auth);assertEquals(400,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).param("current","unexpected")).andReturn().getResponse().getStatus());verifyNoInteractions(mocked,auth);
    }
}

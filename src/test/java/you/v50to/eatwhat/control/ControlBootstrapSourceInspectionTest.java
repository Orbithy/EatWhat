package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import you.v50to.eatwhat.control.bootstrap.ControllerSourceSnapshot;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Sealed-profile routing and comparison contract; signed storage/real SQL are
 * exercised separately by integration fixtures. Remote collection is mocked. */
class ControlBootstrapSourceInspectionTest {
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    final ControlBootstrapProfileState profiles=stub(ControlBootstrapProfileState.class);
    final ControlBootstrapNativePhases nativePhases=stub(ControlBootstrapNativePhases.class);
    final ControlBootstrapSources sources=stub(ControlBootstrapSources.class);
    final ControlBootstrapAdmission.Frozen frozen=stub(ControlBootstrapAdmission.Frozen.class);
    final ControlBootstrapProfileState.Accepted profile=stub(ControlBootstrapProfileState.Accepted.class);
    final ControlBootstrapProfilePlans.Pair pair=stub(ControlBootstrapProfilePlans.Pair.class);
    final ControlBootstrapSourceInspection inspection=new ControlBootstrapSourceInspection(profiles,nativePhases,sources);
    ControlBootstrapJournal.Spec spec;ControlBootstrapSources.Request original;Map<String,Object> evidence;Map<String,String> hashes;
    @BeforeEach void setup(){
        spec=new ControlBootstrapJournal.Spec(UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),2,UUID.randomUUID(),1,"a".repeat(64),"fixture-business","b".repeat(64),1);
        var expected=new ControllerSourceSnapshot.Expected(spec.clusterId(),"campus",UUID.randomUUID(),"c".repeat(32),UUID.randomUUID(),1,"original-source","d".repeat(64),"e".repeat(64),"f".repeat(64));
        original=new ControlBootstrapSources.Request("a".repeat(64),"a".repeat(64),"c".repeat(64),expected);
        hashes=Map.of("application.properties","d".repeat(64),"cluster-profiles.json","e".repeat(64));
        when(frozen.spec()).thenReturn(spec);when(frozen.binding()).thenReturn("original-capsule");when(frozen.sourceRoute()).thenReturn("original-route");when(frozen.sourceEvidence()).thenReturn(Map.of("sourceProcess",Map.of("pid",42,"startTicks",123)));
        when(profile.frozen()).thenReturn(frozen);when(profile.binding()).thenReturn("original-profile");when(profile.plans()).thenReturn(pair);
        when(pair.sourceCaptureRequest()).thenReturn(original);when(pair.sourceCaptureFileHashes()).thenReturn(hashes);
        when(profiles.original(spec.id())).thenReturn(profile);when(profiles.progress(profile)).thenReturn(Map.of("SOURCE","SUCCEEDED","TARGET","SUCCEEDED"));
        evidence=new LinkedHashMap<>(Map.of("fileHashes",hashes,"sourceProcess",Map.of("pid",42,"startTicks",123)));
        when(sources.inspectOriginal(eq(spec.sourceServerId()),eq(1L),eq("original-route"),any())).thenAnswer(inv->{
            var request=(ControlBootstrapSources.Request)inv.getArgument(3);var e=original.expected();assertEquals(2,request.expected().epoch());
            assertEquals(original.sourceArtifactSha256(),request.sourceArtifactSha256());assertEquals(original.probeArtifactSha256(),request.probeArtifactSha256());assertEquals(original.unitSha256(),request.unitSha256());
            assertEquals(new ControllerSourceSnapshot.Expected(e.clusterId(),e.authoritySiteId(),e.authorityInstanceId(),e.authorityDatabaseFingerprint(),e.stateId(),2,e.controllerName(),e.applicationSha256(),e.profilesSha256(),e.sharedKeySha256()),request.expected());
            return new ControlBootstrapSources.Captured(spec.sourceServerId(),1,"original-route",evidence,Map.of());
        });
    }
    @Test void exactOriginalProfileSuccessorAndProcessCanBeInspectedWithoutRebasingApproval(){
        var seen=inspection.inspectJoined(frozen);assertEquals(true,seen.get("sourceConfigurationRevalidated"));assertEquals(true,seen.get("sourceProcessRevalidated"));assertEquals(Set.of("sourceConfigurationRevalidated","sourceProcessRevalidated","sourceCheckedAt"),seen.keySet());
        assertEquals(1,frozen.spec().epoch());verify(nativePhases).bound(frozen);verify(sources,never()).observeExecuting(any(),anyLong(),any());verify(sources,never()).capture(any(),anyLong(),any());
    }
    @Test void sourcePidOrStartIdentityChangeCannotBeConfusedWithTheOriginalProcess(){
        evidence.put("sourceProcess",Map.of("pid",42,"startTicks",124));assertEquals("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED",assertThrows(ControlFailure.class,()->inspection.inspectJoined(frozen)).code);
    }
    @Test void modifiedSourceConfigurationCannotPassOnTheOldProfileReceipt(){
        evidence.put("fileHashes",Map.of("application.properties","0".repeat(64),"cluster-profiles.json","e".repeat(64)));
        assertEquals("BOOTSTRAP_SOURCE_CONFIGURATION_CHANGED",assertThrows(ControlFailure.class,()->inspection.inspectJoined(frozen)).code);
    }
    @Test void incompleteProfilePublicationNeverStartsSourceCollection(){
        when(profiles.progress(profile)).thenReturn(Map.of("SOURCE","SUCCEEDED","TARGET","CLAIMED"));
        assertEquals("BOOTSTRAP_PROFILES_NOT_READY",assertThrows(ControlFailure.class,()->inspection.inspectJoined(frozen)).code);verifyNoInteractions(sources);
    }
    @Test void concurrentProfileProgressChangeCannotBeMergedIntoTheOldObservation(){
        when(profiles.progress(profile)).thenReturn(Map.of("SOURCE","SUCCEEDED","TARGET","SUCCEEDED"),Map.of("SOURCE","SUCCEEDED","TARGET","CLAIMED"));
        assertEquals("BOOTSTRAP_PROFILE_RECORD_CHANGED",assertThrows(ControlFailure.class,()->inspection.inspectJoined(frozen)).code);
    }
}

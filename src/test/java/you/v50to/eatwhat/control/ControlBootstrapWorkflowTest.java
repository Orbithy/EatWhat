package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static you.v50to.eatwhat.control.ControlBootstrapJournal.Phase.*;

/** Routing tests. Native/SQL behavior is covered by the existing child suites;
 * these mocks do not establish successful installation on a Linux host. */
class ControlBootstrapWorkflowTest {
    static <T>T stub(Class<T> type){return mock(type,withSettings().mockMaker(org.mockito.MockMakers.INLINE));}
    UUID id=UUID.randomUUID();ControlBootstrapJournal journal=stub(ControlBootstrapJournal.class);ControlBootstrapAdmission admission=stub(ControlBootstrapAdmission.class);
    ControlBootstrapAdmission.Frozen f=stub(ControlBootstrapAdmission.Frozen.class);
    ControlBootstrapNativePhases nativePhases=stub(ControlBootstrapNativePhases.class);ControlBootstrapDependencyPhases dependencies=stub(ControlBootstrapDependencyPhases.class);
    ControlBootstrapControllerPhases controllers=stub(ControlBootstrapControllerPhases.class);ControlBootstrapBusinessPhases business=stub(ControlBootstrapBusinessPhases.class);
    ControlBootstrapMemberRelay relay=stub(ControlBootstrapMemberRelay.class);ControlBootstrapMemberAuth auth=stub(ControlBootstrapMemberAuth.class);
    ControlBootstrapMemberIngress ingress=stub(ControlBootstrapMemberIngress.class);ControlBootstrapMemberRuntime runtime=stub(ControlBootstrapMemberRuntime.class);
    ControlBootstrapProfilePhases profiles=stub(ControlBootstrapProfilePhases.class);ControlBootstrapMembership membership=stub(ControlBootstrapMembership.class);ControlBootstrapVerify verification=stub(ControlBootstrapVerify.class);
    ControlBootstrapWorkflow flow=new ControlBootstrapWorkflow(journal,admission,nativePhases,dependencies,controllers,business,relay,auth,ingress,runtime,profiles,membership,verification);
    Map<String,Object> result=Map.of("state","RUNNING");
    @BeforeEach void setup(){when(journal.originalAdmission(id,false)).thenReturn(f);}
    void at(ControlBootstrapJournal.Phase phase){
        var steps=new ArrayList<Map<String,Object>>();for(var p:ControlBootstrapJournal.Phase.values()){
            var row=new LinkedHashMap<String,Object>(Map.of("phase",p.name(),"state",p.ordinal()<phase.ordinal()?"SUCCEEDED":"PENDING"));
            if(p==DEPENDENCY_LINK)row.put("operations",Arrays.stream(ControlBootstrapJournal.DependencyOperation.values()).map(op->Map.of("operation",op.name(),"state","PENDING")).toList());steps.add(row);
        }when(journal.job(id)).thenReturn(Map.of("state","QUEUED","steps",steps));
    }
    @Test void routesAllNativeAndIndependentControllerBusinessJoinAndVerifyPhases(){
        for(var phase:List.of(SOURCE_RUNTIME,TARGET_RUNTIME,SOURCE_PRIVATE_PEER,TARGET_PRIVATE_PEER,SOURCE_SHARED_STATE,TARGET_SHARED_STATE)){
            at(phase);when(nativePhases.advance(f,phase)).thenReturn(result);assertSame(result,flow.advance(id));verify(nativePhases).advance(f,phase);
        }
        at(CONTROLLER_CONFIG);when(controllers.configure(f)).thenReturn(result);assertSame(result,flow.advance(id));
        at(CONTROLLER_VERIFY);when(controllers.verify(f)).thenReturn(result);assertSame(result,flow.advance(id));
        at(BUSINESS_INSTALL);when(business.advance(f)).thenReturn(result);assertSame(result,flow.advance(id));
        at(MEMBER_JOIN);when(membership.advance(f)).thenReturn(result);assertSame(result,flow.advance(id));
        at(VERIFY);when(verification.advance(f)).thenReturn(result);assertSame(result,flow.advance(id));
        verifyNoInteractions(relay,auth,ingress,runtime,profiles);
    }
    @Test void dependencyResumesOnlyFirstUnfinishedOriginalOperation(){
        for(var op:ControlBootstrapJournal.DependencyOperation.values()){
            at(DEPENDENCY_LINK);var job=new HashMap<>(journal.job(id));var steps=(List<Map<String,Object>>)job.get("steps");
            steps.get(DEPENDENCY_LINK.ordinal()).put("operations",Arrays.stream(ControlBootstrapJournal.DependencyOperation.values()).map(p->Map.of("state",p.ordinal()<op.ordinal()?"SUCCEEDED":"OUTCOME_UNKNOWN")).toList());
            when(journal.job(id)).thenReturn(job);when(dependencies.advance(f,op)).thenReturn(result);assertSame(result,flow.advance(id));verify(dependencies).advance(f,op);
        }
    }
    static Map<String,Object> operations(String key,String... names){var values=new LinkedHashMap<String,Object>();for(var name:names)values.put(name,"SUCCEEDED");return Map.of(key,values);}
    void configuredChildren(){
        when(relay.advance(f)).thenReturn(operations("memberRelayOperations","PREPARE","START"));
        when(auth.advance(f)).thenReturn(operations("memberAuthOperations","PREPARE","CREATE"));
        when(ingress.advance(f)).thenReturn(operations("memberIngressOperations","PREPARE","START"));
        when(runtime.advance(f)).thenReturn(Map.of("memberRuntimeState","CONFIGURED"));
        when(profiles.advance(f,ControlBootstrapProfileState.Role.SOURCE)).thenReturn(Map.of("profileOperations",Map.of("SOURCE","SUCCEEDED","TARGET","PENDING")));
        when(profiles.advance(f,ControlBootstrapProfileState.Role.TARGET)).thenReturn(operations("profileOperations","SOURCE","TARGET"));
    }
    @Test void everyConfigurationChildAndBothProfilePublicationsPrecedeParentCompletion(){
        at(MEMBER_CONFIGURE);configuredChildren();when(membership.configure(f)).thenReturn(result);assertSame(result,flow.advance(id));
        var order=inOrder(relay,auth,ingress,runtime,profiles,membership);
        order.verify(relay).advance(f);order.verify(auth).advance(f);order.verify(ingress).advance(f);order.verify(runtime).advance(f);
        order.verify(profiles).advance(f,ControlBootstrapProfileState.Role.SOURCE);order.verify(profiles).advance(f,ControlBootstrapProfileState.Role.TARGET);order.verify(membership).configure(f);
        verify(membership,never()).advance(any());
    }
    @Test void unknownMissingOrFailedChildNeverAdvancesTheFollowingOperation(){
        at(MEMBER_CONFIGURE);
        for(var incomplete:List.of(Map.<String,Object>of("memberRelayOperations",Map.of("PREPARE","SUCCEEDED","START","CLAIMED")),Map.<String,Object>of("state","FAILED"),Map.<String,Object>of())){
            when(relay.advance(f)).thenReturn(incomplete);assertSame(incomplete,flow.advance(id));
        }verifyNoInteractions(auth,ingress,runtime,profiles,membership,verification);
    }
    @Test void sourceProfileMustCompleteBeforeTargetAndBothMustCompleteBeforeConfigure(){
        at(MEMBER_CONFIGURE);configuredChildren();var unknown=Map.<String,Object>of("profileOperations",Map.of("SOURCE","CLAIMED","TARGET","PENDING"));
        when(profiles.advance(f,ControlBootstrapProfileState.Role.SOURCE)).thenReturn(unknown);assertSame(unknown,flow.advance(id));verify(profiles,never()).advance(f,ControlBootstrapProfileState.Role.TARGET);verifyNoInteractions(membership);
    }
    @Test void historicalTerminalOrInvalidatedTasksCannotBecomeNewExecution(){
        when(journal.job(id)).thenReturn(Map.of("state","SUCCEEDED"));assertEquals("SUCCEEDED",flow.advance(id).get("state"));verify(journal,never()).originalAdmission(any(),anyBoolean());
        at(SOURCE_RUNTIME);var job=new HashMap<>(journal.job(id));job.put("requiresReapproval",true);when(journal.job(id)).thenReturn(job);
        assertEquals("BOOTSTRAP_REAPPROVAL_REQUIRED",assertThrows(ControlFailure.class,()->flow.advance(id)).code);verifyNoInteractions(nativePhases);
    }
}

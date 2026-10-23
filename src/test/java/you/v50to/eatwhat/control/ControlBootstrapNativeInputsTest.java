package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** The forced original is independent of child-ledger initialization success. */
class ControlBootstrapNativeInputsTest {
    @TempDir Path root;
    @Test void initialization_runs_once_after_the_original_is_forced_and_never_on_observation(){
        var store=new ControlServerStore(new ControlAudit(root));var inputs=new ControlBootstrapNativeInputs(store);UUID job=UUID.randomUUID(),server=UUID.randomUUID();String approval="a".repeat(64),agent="original fixture";
        var header=Map.<String,Object>of("approvalDigest",approval,"agentSha256",ControlBootstrapNativeJson.sha(agent));var calls=new AtomicInteger();
        var first=inputs.prepare(job,ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,approval,server,1,"route",true,()->new ControlBootstrapNativeInputs.Prepared(header,agent),saved->{
            calls.incrementAndGet();assertEquals(saved,inputs.load(job,ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,approval,server,1,"route"));
        });
        var again=inputs.prepare(job,ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,approval,server,1,"route",false,()->{throw new AssertionError();},saved->{throw new AssertionError();});
        assertEquals(first,again);assertEquals(1,calls.get());
    }
    @Test void lost_initialization_never_recreates_ledger_or_replaces_private_inputs(){
        var store=new ControlServerStore(new ControlAudit(root));var inputs=new ControlBootstrapNativeInputs(store);UUID job=UUID.randomUUID(),server=UUID.randomUUID();String approval="a".repeat(64),agent="retained fixture";
        var header=Map.<String,Object>of("approvalDigest",approval,"agentSha256",ControlBootstrapNativeJson.sha(agent));
        assertThrows(ControlFailure.class,()->inputs.prepare(job,ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,approval,server,1,"route",true,
                ()->new ControlBootstrapNativeInputs.Prepared(header,agent),saved->{throw new IllegalStateException("synthetic lost initialization");}));
        var original=inputs.load(job,ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,approval,server,1,"route");
        assertEquals(original,inputs.prepare(job,ControlBootstrapJournal.Phase.MEMBER_CONFIGURE,approval,server,1,"route",true,()->{throw new AssertionError();},saved->{throw new AssertionError();}));
    }
}

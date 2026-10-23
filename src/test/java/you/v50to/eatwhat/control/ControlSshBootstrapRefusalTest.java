package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlSshBootstrapRefusalTest {
    @TempDir Path root;
    void call(String protocol,String code)throws Exception{
        Path body=root.resolve("response.json");Files.writeString(body,tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("protocol",protocol,"code",code)));
        ControlSsh.process(List.of("/usr/bin/python3","-I","-c","import pathlib,sys;sys.stdout.buffer.write(pathlib.Path(sys.argv[1]).read_bytes());sys.exit(1)",body.toString()),Map.of(),out->{},5,1024,true);
    }
    @Test void onlyBundledBootstrapCodesAndKnownProtocolsBecomeRemoteRefusals(){
        var f=assertThrows(ControlSsh.RemoteRefusal.class,()->call("eatwhat-controller-bootstrap-preflight-v1","BOOTSTRAP_TARGET_CONTROLLER_RETAINED"));
        assertEquals("BOOTSTRAP_TARGET_CONTROLLER_RETAINED",f.code);assertEquals("SSH_REMOTE_REFUSAL",f.getMessage());
        var host=assertThrows(ControlSsh.RemoteRefusal.class,()->call("eatwhat-controller-bootstrap-dependency-v1","BOOTSTRAP_DEPENDENCY_SOURCE_HOST_KEY_UNAVAILABLE"));
        assertEquals("BOOTSTRAP_DEPENDENCY_SOURCE_HOST_KEY_UNAVAILABLE",host.code);assertEquals("SSH_REMOTE_REFUSAL",host.getMessage());
        var business=assertThrows(ControlSsh.RemoteRefusal.class,()->call("eatwhat-controller-bootstrap-business-v1","BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED"));
        assertEquals("BOOTSTRAP_BUSINESS_INSTALLATION_UNCONFIRMED",business.code);assertEquals("SSH_REMOTE_REFUSAL",business.getMessage());
    }
    @Test void unknownProtocolAndArbitraryRemoteTextStayGeneric(){
        for(var pair:List.of(List.of("untrusted","BOOTSTRAP_TARGET_CONTROLLER_RETAINED"),List.of("eatwhat-controller-bootstrap-preflight-v1","private-password-output"))){
            var f=assertThrows(java.io.IOException.class,()->call(pair.get(0),pair.get(1)));assertFalse(f instanceof ControlSsh.RemoteRefusal);assertEquals("SSH_FAILED",f.getMessage());
        }
    }
}

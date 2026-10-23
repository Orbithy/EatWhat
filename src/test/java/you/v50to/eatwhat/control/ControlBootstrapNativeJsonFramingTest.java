package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlBootstrapNativeJsonFramingTest {
    @Test void reviewedRefusalRemainsDiagnosticAndUntrustedTransportTextStaysHidden()throws Exception{
        var ssh=mock(ControlSsh.class);var driver=new ControlBootstrapNativePhases(null,null,null,null,null,ssh);
        when(ssh.call(any(),anyString(),anyList(),any(),anyInt())).thenThrow(new ControlSsh.RemoteRefusal("BOOTSTRAP_PROFILE_REQUEST_UNAVAILABLE"));
        var safe=assertThrows(ControlFailure.class,()->driver.call(Map.of(),"reviewed",List.of("status"),Map.of()));
        assertEquals("BOOTSTRAP_PROFILE_REQUEST_UNAVAILABLE",safe.code);
        reset(ssh);when(ssh.call(any(),anyString(),anyList(),any(),anyInt())).thenThrow(new IOException("synthetic-private-transport-text"));
        var hidden=assertThrows(ControlFailure.class,()->driver.call(Map.of(),"reviewed",List.of("status"),Map.of()));
        assertEquals("BOOTSTRAP_NATIVE_OBSERVATION_UNAVAILABLE",hidden.code);assertFalse(hidden.getMessage().contains("synthetic-private"));
    }
    @Test void actualMemberCliAcceptsDriverJsonWithItsOriginalLineReader()throws Exception{
        var ssh=mock(ControlSsh.class);var captured=new ByteArrayOutputStream();
        when(ssh.call(any(),anyString(),anyList(),any(),anyInt())).thenAnswer(inv->{
            ((ControlSsh.Upload)inv.getArgument(3)).write(captured);return Map.of("captured",true);
        });
        var driver=new ControlBootstrapNativePhases(null,null,null,null,null,ssh);
        var header=Map.<String,Object>of("id",UUID.randomUUID().toString(),"marker","synthetic-only");
        driver.call(Map.of(),ControlBootstrapMemberRuntime.agent(),List.of("inspect"),header);
        Path member=Path.of(getClass().getResource("/control/member-configure.py").toURI());
        Path install=Path.of(getClass().getResource("/control/first-install.py").toURI());
        String python="""
            import pathlib,sys,types
            b=types.ModuleType('real_install_line_reader');exec(compile(pathlib.Path(sys.argv[2]).read_bytes(),'<actual-install>','exec'),b.__dict__)
            m=types.ModuleType('real_member_cli');exec(compile(pathlib.Path(sys.argv[1]).read_bytes(),'<actual-member-cli>','exec'),m.__dict__)
            # Keep the actual CLI and line reader; isolate host/database effects.
            m.recovery=lambda:types.SimpleNamespace(bootstrap=lambda:b)
            m.inspect=lambda h:{'protocol':m.PROTOCOL,'marker':h['marker'],'acceptedLineHeader':True}
            sys.argv=['member-configure','inspect'];m.main()
            """;
        byte[] bytes=captured.toByteArray();
        for(boolean framed:List.of(false,true)){
            var p=new ProcessBuilder("python3","-I","-B","-c",python,member.toString(),install.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try(var out=p.getOutputStream()){out.write(framed?bytes:Arrays.copyOf(bytes,bytes.length-1));}
            assertTrue(p.waitFor(10,TimeUnit.SECONDS));String result=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            if(framed){assertEquals(0,p.exitValue());assertTrue(result.contains("acceptedLineHeader"));}
            else{assertNotEquals(0,p.exitValue());assertTrue(result.contains("MEMBER_CONFIGURATION_PRECONDITION_FAILED"));}
        }
    }
}

package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ControlBootstrapProfileLoopbackTest {
    @TempDir Path root;
    @Test void actualBridgeAcceptsMappedLoopbackAndStillRequiresExactProcessNonce()throws Exception{
        Path source=Path.of(getClass().getResource("/control/controller-bootstrap-profile-request.py").toURI());
        String python="""
            import pathlib,types,sys,os,json
            m=types.ModuleType('actual_profile_bridge');exec(compile(pathlib.Path(sys.argv[1]).read_bytes(),'<actual-bridge>','exec'),m.__dict__)
            root=pathlib.Path(sys.argv[2]);m.CONTROL=root/'control';m.PROC=root/'proc';pid=1234;process=m.PROC/str(pid);process.mkdir(parents=True)
            (process/'exe').symlink_to('/usr/bin/java')
            argv=['/usr/bin/java','-Xms128m','-Xmx768m','-jar',str(m.CONTROL/'acceptance'/('a'*64+'.jar')),'--spring.config.location=file:'+str(m.CONTROL/'config/application.properties'),'--spring.config.additional-location=','--spring.config.import=']
            (process/'cmdline').write_bytes(b'\\0'.join(x.encode() for x in argv)+b'\\0')
            environment={'PATH':b'/usr/bin:/bin','LANG':b'C.UTF-8'};(process/'environ').write_bytes(b'\\0'.join(k.encode()+b'='+v for k,v in environment.items())+b'\\0')
            (process/'stat').write_text('1234 (fixture java) '+' '.join(['S']+['0']*18+['500']+['0']*5))
            proof={'pid':pid,'startTicks':'500','argvSha256':m.digest(m.canonical(argv)),'environmentSha256':m.digest(m.canonical({k:v.hex() for k,v in environment.items()}))}
            data={'role':'SOURCE','artifactSha256':'a'*64,'sourceProcess':proof};listener=''
            m.command=lambda args:b'1234\\n' if args[0]=='systemctl' else ('LISTEN 0 100 '+listener+' *:* users:(("java",pid=1234,fd=5))\\n').encode()
            for listener in ['127.0.0.1:18081','[::ffff:127.0.0.1]:18081','[::ffff:7f00:1]:18081']:
                assert m.process(data,os.getuid())==proof
            for listener in ['0.0.0.0:18081','[::]:18081','[::1]:18081','127.0.0.2:18081','[::ffff:192.168.1.1]:18081','[::ffff:127.0.0.1]:18080','[::ffff:127.0.0.1%lo]:18081']:
                try:m.process(data,os.getuid())
                except m.Refusal:pass
                else:raise AssertionError('non-contract listener accepted')
            listener='[::ffff:127.0.0.1]:18081';data['sourceProcess']={**proof,'startTicks':'501'}
            try:m.process(data,os.getuid())
            except m.Refusal:pass
            else:raise AssertionError('changed source process adopted')
            print(json.dumps({'mappedAccepted':True,'otherListenersRejected':True,'originalProcessStillRequired':True}))
            """;
        var p=new ProcessBuilder("python3","-I","-B","-c",python,source.toString(),root.toRealPath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertTrue(p.waitFor(15,TimeUnit.SECONDS));assertEquals(0,p.exitValue());assertTrue(new String(p.getInputStream().readAllBytes()).contains("originalProcessStillRequired"));
    }
}

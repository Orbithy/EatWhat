package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native parser with synthetic procfs records, never real mounts. */
class ControlBootstrapSourceProfileStorageTest {
    @TempDir Path root;
    Map<?,?> python(String program,Map<String,Object> input)throws Exception{
        var p=new ProcessBuilder("/usr/bin/python3","-I","-c",program).redirectError(ProcessBuilder.Redirect.INHERIT).start();var json=JsonMapper.builder().build();
        try(var out=p.getOutputStream()){out.write(json.writeValueAsBytes(input));}
        assertTrue(p.waitFor(20,TimeUnit.SECONDS));assertEquals(0,p.exitValue());return json.readValue(p.getInputStream().readAllBytes(),Map.class);
    }
    @Test void actualServiceMountViewMustAllowBothConfigAndItsPrivateJournal()throws Exception{
        String program="""
            import base64,json,os,pathlib,sys,types
            h=json.load(sys.stdin);m=types.ModuleType('actual_source_storage')
            exec(compile(base64.b64decode(h['source']),'<actual-source>','exec'),m.__dict__)
            root=pathlib.Path(h['root']);m.CONTROL=root/'control';config=m.CONTROL/'config';config.mkdir(parents=True,mode=0o700);os.chmod(config,0o700)
            m.PROC=root/'proc';proc=m.PROC/'42';proc.mkdir(parents=True)
            m.pwd.getpwnam=lambda _:types.SimpleNamespace(pw_uid=os.getuid())
            def mount(path,options='rw',super_options='rw'):
                return '1 0 0:1 / '+str(path)+' '+options+' - ext4 /dev/vda '+super_options+'\\n'
            cases={'plainWritable':mount('/'),'rootReadOnly':mount('/','ro'),
                'stateOnlyWritable':mount('/','ro')+mount(m.CONTROL/'state'),
                'configWritable':mount('/','ro')+mount(config),
                'journalReadOnly':mount('/','ro')+mount(config)+mount(config/'profile-publications','ro'),
                'superblockReadOnly':mount('/')+mount(config,'rw','ro'),
                'ambiguousReadOnlyOverlay':mount('/')+mount(config,'ro')+mount(config),'malformed':'not mountinfo'}
            results={}
            for name,raw in cases.items():
                (proc/'mountinfo').write_text(raw)
                try:m.profile_storage(42);results[name]='PASS'
                except m.Refusal as e:results[name]=e.code
            (proc/'mountinfo').write_text(mount('/'));os.chmod(config,0o500)
            try:m.profile_storage(42);raise AssertionError('must reject non-writable directory')
            except m.Refusal as e:results['directoryNotWritable']=e.code
            finally:os.chmod(config,0o700)
            print(json.dumps(results))
            """;
        var seen=python(program,Map.of("root",root.toRealPath().toString(),"source",Base64.getEncoder().encodeToString(ControlServers.resource("controller-bootstrap-source.py").getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertEquals("PASS",seen.get("plainWritable"));assertEquals("PASS",seen.get("configWritable"));
        for(String key:List.of("rootReadOnly","stateOnlyWritable","journalReadOnly","superblockReadOnly","ambiguousReadOnlyOverlay"))assertEquals("BOOTSTRAP_SOURCE_PROFILE_STORAGE_READ_ONLY",seen.get(key));
        assertEquals("BOOTSTRAP_SOURCE_PROFILE_DIRECTORY_REQUIRED",seen.get("directoryNotWritable"));assertEquals("BOOTSTRAP_SOURCE_SNAPSHOT_UNAVAILABLE",seen.get("malformed"));
    }
    @Test void generatedTargetKeepsStrictSandboxAndOnlyAddsItsPrivateConfigWritePath()throws Exception{
        String program="""
            import base64,json,sys,types
            h=json.load(sys.stdin);m=types.ModuleType('actual_controller_policy')
            exec(compile(base64.b64decode(h['source']),'<actual-controller-policy>','exec'),m.__dict__)
            unit=m.service_unit({'controllerSha256':'a'*64},h['id'],'fixture-state.mount').decode()
            values={row.split('=',1)[0]:row.split('=',1)[1] for row in unit.splitlines() if '=' in row}
            print(json.dumps({k:values[k] for k in ['User','ProtectSystem','NoNewPrivileges','ReadWritePaths']}))
            """;
        assertEquals(Map.of("User","eatwhat-control","ProtectSystem","strict","NoNewPrivileges","true","ReadWritePaths","/opt/eatwhat-control/state /opt/eatwhat-control/config"),
                python(program,Map.of("id",UUID.randomUUID().toString(),"source",Base64.getEncoder().encodeToString(ControlServers.resource("controller-bootstrap-controller-policy.py").getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
    }
}

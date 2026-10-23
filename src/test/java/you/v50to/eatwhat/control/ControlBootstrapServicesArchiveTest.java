package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual native observer against sparse public fixture files. */
class ControlBootstrapServicesArchiveTest {
    @TempDir Path root;
    @Test void approvedLargeArchiveKeepsExactContentAndSizeBindingWithoutOpeningOtherFiles()throws Exception {
        String program="""
            import base64,hashlib,json,os,pathlib,sys,types
            h=json.load(sys.stdin);m=types.ModuleType('actual_services')
            exec(compile(base64.b64decode(h['source']),'<actual-services>','exec'),m.__dict__)
            m.CONTROL=pathlib.Path(h['root']);folder=m.CONTROL/'acceptance';folder.mkdir()
            temporary=folder/'sparse';size=301*1024*1024
            with temporary.open('wb') as out:out.truncate(size)
            os.chmod(temporary,0o600)
            digest=hashlib.sha256()
            with temporary.open('rb') as inp:
                while block:=inp.read(65536):digest.update(block)
            sha=digest.hexdigest();archive=folder/(sha+'.images.tar');temporary.rename(archive)
            plan={'files':[str(archive)],'directories':[],'symlinks':{},'absent':[],'units':[],'listeners':{},'inactiveUnits':[]}
            def refused(action,code):
                try:action()
                except m.Refusal as failure:assert failure.code==code;return
                raise AssertionError('Unexpected acceptance')
            refused(lambda:m.observe_files(plan),'BOOTSTRAP_BUSINESS_SERVICES_UNSAFE_FILE')
            plan['imageArchive']={'sha256':sha,'size':size}
            observed=m.observe_files(plan);assert observed[str(archive)]['size']==size
            with archive.open('r+b') as out:out.write(b'changed')
            refused(lambda:m.observe_files(plan),'BOOTSTRAP_BUSINESS_SERVICES_ARCHIVE_CHANGED')
            with archive.open('r+b') as out:out.write(b'\\0'*7)
            other=folder/'unreviewed';other.touch();other.chmod(0o600)
            with other.open('wb') as out:out.truncate(size)
            plan['files'].append(str(other))
            refused(lambda:m.observe_files(plan),'BOOTSTRAP_BUSINESS_SERVICES_UNSAFE_FILE')
            plan['files'].pop();plan['imageArchive']['size']=2*1024*1024*1024+1
            refused(lambda:m.observe_files(plan),'BOOTSTRAP_BUSINESS_SERVICES_ARCHIVE_BINDING_CHANGED')
            plan['imageArchive']['size']=size+1
            refused(lambda:m.observe_files(plan),'BOOTSTRAP_BUSINESS_SERVICES_ARCHIVE_CHANGED')
            plan['imageArchive']['size']=size;archive.unlink();archive.symlink_to(other)
            refused(lambda:m.observe_files(plan),'BOOTSTRAP_BUSINESS_SERVICES_UNSAFE_FILE')
            print(json.dumps({'approvedLargeArchive':True,'changedBytesRefused':True,'wrongSizeRefused':True,'unreviewedLargeFileRefused':True,'overLimitRefused':True,'symlinkRefused':True}))
            """;
        var p=new ProcessBuilder("/usr/bin/python3","-I","-c",program).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var json=JsonMapper.builder().build();try(var out=p.getOutputStream()){
            out.write(json.writeValueAsBytes(Map.of("source",Base64.getEncoder().encodeToString(ControlServers.resource("controller-bootstrap-business-services.py").getBytes(java.nio.charset.StandardCharsets.UTF_8)),"root",root.toRealPath().toString())));
        }
        assertTrue(p.waitFor(30,TimeUnit.SECONDS));assertEquals(0,p.exitValue());var result=json.readValue(p.getInputStream().readAllBytes(),Map.class);
        assertEquals(6,result.size());assertTrue(result.values().stream().allMatch(Boolean.TRUE::equals));
    }
}

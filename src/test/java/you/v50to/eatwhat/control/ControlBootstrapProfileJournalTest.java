package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Python successor guard: a retained legacy intent must not become an
 * apparently absent witness after the journal location changes. */
class ControlBootstrapProfileJournalTest {
    @TempDir Path root;
    final UUID server=UUID.randomUUID();
    Map<?,?> probe()throws Exception {
        String program="""
            import base64,json,pathlib,sys,types
            h=json.load(sys.stdin);m=types.ModuleType('actual_profile_journal')
            exec(compile(base64.b64decode(h['source']),'<actual-profile-journal>','exec'),m.__dict__)
            m.CONTROL=pathlib.Path(h['root'])
            baseline={'plan':{'original':'plan'},'observation':{'original':'observation'}}
            try:
                value=m.profile_successor({'serverId':h['serverId']},baseline)
                print(json.dumps({'state':'BASELINE_UNCHANGED','sameBaseline':value==(baseline['plan'],baseline['observation'],None)}))
            except Exception as e:print(json.dumps({'state':'REFUSED','code':getattr(e,'code',None)}))
            """;
        var p=new ProcessBuilder("/usr/bin/python3","-I","-c",program).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var json=JsonMapper.builder().build();try(var out=p.getOutputStream()){
            out.write(json.writeValueAsBytes(Map.of("source",Base64.getEncoder().encodeToString(ControlServers.resource("controller-bootstrap-business-services.py").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "root",root.toString(),"serverId",server.toString())));
        }
        assertTrue(p.waitFor(20,TimeUnit.SECONDS));assertEquals(0,p.exitValue());return json.readValue(p.getInputStream().readAllBytes(),Map.class);
    }
    @Test void untouchedPrePublicationBaselineStillHasNoSuccessor()throws Exception {
        assertEquals(Map.of("state","BASELINE_UNCHANGED","sameBaseline",true),probe());
        assertFalse(Files.exists(root.resolve("config/profile-publications")));
    }
    @Test void oldIntentCannotBeSilentlyTreatedAsAnUnpublishedBaseline()throws Exception {
        Path task=Files.createDirectories(root.resolve("profile-publications").resolve(server.toString()));
        Files.writeString(task.resolve("intent.json"),"retain-this-original-intent");
        assertEquals(Map.of("state","REFUSED","code","BOOTSTRAP_PROFILE_SERVICES_RETAINED_LAYOUT"),probe());
        assertEquals("retain-this-original-intent",Files.readString(task.resolve("intent.json")));
        assertFalse(Files.exists(root.resolve("config/profile-publications")));
    }
    @Test void legacySymlinkCannotRedirectTheRetentionCheck()throws Exception {
        Path other=Files.createDirectory(root.resolve("other"));Files.createSymbolicLink(root.resolve("profile-publications"),other);
        assertEquals(Map.of("state","REFUSED","code","BOOTSTRAP_PROFILE_SERVICES_UNSAFE_FILE"),probe());
        try(var entries=Files.list(other)){assertEquals(0,entries.count());}
    }
}

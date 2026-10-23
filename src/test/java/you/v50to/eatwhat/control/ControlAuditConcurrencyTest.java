package you.v50to.eatwhat.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlAuditConcurrencyTest {
    @TempDir Path root;
    @Test void independentInstancesAppendWholePrivateRecordsWithoutCreationRaces()throws Exception{
        var one=new ControlAudit(root);var two=new ControlAudit(root);String body="synthetic".repeat(4096);
        try(var pool=Executors.newFixedThreadPool(8)){
            var work=new ArrayList<Future<?>>();for(int n=0;n<64;n++){int index=n;work.add(pool.submit(()->(index%2==0?one:two).append(UUID.randomUUID(),"fixture","CONCURRENT_APPEND","fixture","PASS",Map.of("index",index,"body",body))));}
            for(var item:work)item.get(15,TimeUnit.SECONDS);
        }
        var json=JsonMapper.builder().build();var lines=Files.readAllLines(root.resolve("audit.jsonl"));assertEquals(64,lines.size());var ids=new HashSet<Integer>();
        for(String line:lines){var row=json.readTree(line);assertEquals(body,row.path("details").path("body").asText());ids.add(row.path("details").path("index").asInt());}assertEquals(64,ids.size());
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(root.resolve("audit.jsonl")));
    }
    @Test void anExistingPublicAuditFileFailsBeforeWritingAnything()throws Exception{
        var audit=new ControlAudit(root);Path file=root.resolve("audit.jsonl");Files.writeString(file,"sentinel\n");Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals("AUDIT_UNAVAILABLE",assertThrows(ControlFailure.class,()->audit.append(UUID.randomUUID(),"fixture","FIXTURE","fixture","PASS")).code);assertEquals("sentinel\n",Files.readString(file));
    }
    @Test void requiredSharedStateNeverCreatesLocalFallbackOrTrustsACopiedStateIdentity()throws Exception{
        Path missing=root.resolve("not-mounted");assertEquals("PRIVATE_STATE_DIRECTORY_REQUIRED",assertThrows(ControlFailure.class,()->new ControlAudit(missing,true)).code);assertFalse(Files.exists(missing));
        Files.writeString(root.resolve("controller-state-id"),UUID.randomUUID().toString());Files.write(root.resolve("servers.key"),new byte[32]);
        assertEquals("PRIVATE_STATE_DIRECTORY_REQUIRED",assertThrows(ControlFailure.class,()->new ControlAudit(root,true)).code);assertFalse(Files.exists(root.resolve("deployment.lock")));assertFalse(Files.exists(root.resolve("servers.json")));
    }
}

package you.v50to.eatwhat.integration;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit browser opt-in, separate from the portable Java integration suite. Missing prerequisites FAIL. */
class LocalBusinessBrowserAcceptance extends LocalBusinessFileFixture {
    @TempDir Path scratch;
    @Test void browserUploadsRestoresAndReadsCdnWhileUntrustedOriginsAreBlocked()throws Exception {
        String module=System.getProperty("eatwhat.acceptance.playwright-module");
        assertNotNull(module,"Supply an installed Playwright module; no downloads or skipped browser checks");
        assertTrue(Files.isRegularFile(Path.of(module,"package.json")));
        var a=account(true);makeAdmin(a);long restaurant=restaurant(a);byte[] bytes=png(98304);
        Path bridge=scratch.resolve("bridge.json"),stage=scratch.resolve("stage.json"),resume=scratch.resolve("resume.json");
        Path output=Path.of("target","business-browser-acceptance",UUID.randomUUID().toString(),"report.json").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(bridge,JSON.writeValueAsString(Map.of("api",base(),"origin",ORIGIN,"deniedOrigin","http://127.0.0.1:5173","token",a.token(),"restaurant",restaurant,
                "payload",Base64.getEncoder().encodeToString(bytes),"stage",stage.toString(),"resume",resume.toString(),"output",output.toString())));
        Files.setPosixFilePermissions(bridge,PosixFilePermissions.fromString("rw-------"));
        var builder=new ProcessBuilder(System.getProperty("eatwhat.acceptance.browser-node","node"),
                Path.of("scripts/local-business-browser.mjs").toAbsolutePath().toString(),bridge.toString());
        builder.environment().put("EATWHAT_PLAYWRIGHT_MODULE",module);
        builder.redirectOutput(scratch.resolve("browser.stdout").toFile());builder.redirectError(scratch.resolve("browser.stderr").toFile());
        HttpServer originServer=HttpServer.create(new InetSocketAddress("127.0.0.1",5173),0);
        originServer.createContext("/",exchange->{
            byte[] html="<!doctype html><title>EatWhat local browser acceptance</title><main>Business file acceptance</main>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
            exchange.sendResponseHeaders(200,html.length);exchange.getResponseBody().write(html);exchange.close();
        });originServer.start();
        Process process;
        try{process=builder.start();}catch(Exception error){originServer.stop(0);Files.deleteIfExists(bridge);throw error;}
        try {
            Instant deadline=Instant.now().plusSeconds(120);
            while(!Files.exists(stage)&&process.isAlive()&&Instant.now().isBefore(deadline))Thread.sleep(100);
            assertTrue(Files.isRegularFile(stage),"Browser must finish upload/reference/release/restore stage; inspect its redacted report");
            var first=JSON.readTree(Files.readString(stage));assertEquals("PASS",first.path("status").asText());
            var f=file(first.path("key").asText());assertEquals(1,live(f.key()));assertEquals(f.sha(),first.path("sha256").asText());
            replicate(f);
            int intents=jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.upload_intent WHERE owner_id=?",Integer.class,a.id());
            Files.writeString(resume,"{\"replication\":\"PASS\"}");
            assertTrue(process.waitFor(120,TimeUnit.SECONDS),"Browser must finish within the local acceptance deadline");
            assertEquals(0,process.exitValue(),"Browser acceptance failed; redacted report: "+output);
            var report=JSON.readTree(Files.readString(output));assertEquals("PASS",report.path("status").asText());
            assertEquals(intents,jdbc.queryForObject("SELECT count(*) FROM eatwhat_file.upload_intent WHERE owner_id=?",Integer.class,a.id()),"Blocked browser origin must not create an intent");
            assertEquals(f.sha(),localReplicaStore.readbackDigest(f.finalKey(),f.size()).sha256Hex());
            assertEquals(1,live(f.key()));assertEquals("APPLIED",pair.campus.sql().queryForObject("SELECT status FROM eatwhat_sync.inbox ORDER BY applied_at DESC LIMIT 1",String.class));
        } finally {
            if(process.isAlive()){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
            originServer.stop(0);Files.deleteIfExists(bridge);
        }
    }
}

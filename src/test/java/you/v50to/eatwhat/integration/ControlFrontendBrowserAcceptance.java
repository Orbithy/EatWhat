package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.*;
import you.v50to.eatwhat.control.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit browser opt-in: actual frontend -> same-origin proxy -> Spring auth/control -> two real V35 databases.
 * External HTTP wrapper is a fixture around production ControlNode, not a second full business application. */
class ControlFrontendBrowserAcceptance extends LocalBusinessFileFixture {
    static final Path PRIVATE;
    static final String EXTERNAL_TOKEN="synthetic-browser-external-"+"e".repeat(44);
    static final HttpServer CAMPUS,EXTERNAL;
    static final AtomicReference<String> CAMPUS_ORIGIN=new AtomicReference<>();
    static final AtomicReference<ControlNode> EXTERNAL_NODE=new AtomicReference<>();
    static final ExecutorService IO=Executors.newVirtualThreadPerTaskExecutor();
    static {
        try {
            PRIVATE=Files.createTempDirectory("eatwhat-control-browser-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.writeString(PRIVATE.resolve("external.token"),EXTERNAL_TOKEN);Files.setPosixFilePermissions(PRIVATE.resolve("external.token"),PosixFilePermissions.fromString("rw-------"));
            CAMPUS=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);CAMPUS.setExecutor(IO);CAMPUS.createContext("/",exchange->relay(exchange,CAMPUS_ORIGIN.get()));CAMPUS.start();
            EXTERNAL=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);EXTERNAL.setExecutor(IO);
            EXTERNAL.createContext("/internal/control/v1/",exchange->{
                try {
                    var node=EXTERNAL_NODE.get();if(node==null)throw new ControlFailure(503,"FIXTURE_STARTING");node.authenticate(exchange.getRequestHeaders().getFirst("X-Control-Node-Token"));
                    Object response;
                    if(exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/internal/control/v1/snapshot"))response=node.snapshot();
                    else if(exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getPath().equals("/internal/control/v1/faults")) {
                        var body=JSON.readTree(exchange.getRequestBody());response=node.fault(body.path("channel").asText(),body.path("action").asText(),body.path("seconds").asInt(),UUID.fromString(body.path("requestId").asText()));
                    } else throw new ControlFailure(404,"NOT_FOUND");
                    send(exchange,200,JSON.writeValueAsBytes(response));
                } catch(ControlFailure e){send(exchange,e.status,JSON.writeValueAsBytes(Map.of("code",e.code)));}
                catch(Exception e){send(exchange,503,"{}".getBytes());}
            });EXTERNAL.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void control(DynamicPropertyRegistry r) {
        ControlPlaneIT.controlProperties(r);
        for(String key:List.of("campus","external")) {
            String prefix="app.control.nodes."+key+".";
            r.add(prefix+"origin",()->"http://127.0.0.1:"+(key.equals("campus")?CAMPUS:EXTERNAL).getAddress().getPort());
            r.add(prefix+"site-id",()->(key.equals("campus")?pair.campus:pair.external).db.site().getId());
            r.add(prefix+"instance-id",()->(key.equals("campus")?pair.campus:pair.external).db.site().getInstanceId().toString());
            r.add(prefix+"token-file",()->(key.equals("campus")?ControlPlaneIT.TOKEN:PRIVATE.resolve("external.token")).toString());
            r.add(prefix+"channels."+(key.equals("campus")?"events":"storage"),()->2);
        }
    }
    @TempDir Path scratch;
    @Test void realBrowserLogsInRestoresOfflineNodeBlocksWithinLimitAndOnlyPreviewsDeployment()throws Exception {
        Process browser=null;HttpServer page=null;ControlNode external=null;boolean denied=false;
        try {
            String module=System.getProperty("eatwhat.acceptance.playwright-module");assertNotNull(module,"An installed Playwright module is required; never skip or download");
            var account=account(true);makeAdmin(account);
            String username=jdbc.queryForObject("SELECT nick_name FROM users WHERE id=?",String.class,account.id());
            var ordinary=account(true);String ordinaryUsername=jdbc.queryForObject("SELECT nick_name FROM users WHERE id=?",String.class,ordinary.id());
            CAMPUS_ORIGIN.set(base());
            var config=new ControlProperties();config.setNodeTokenFile(PRIVATE.resolve("external.token"));
            var channel=new ControlProperties.Proxy();try(var free=new java.net.ServerSocket(0)){channel.setListenPort(free.getLocalPort());}channel.setTargetHost("127.0.0.1");channel.setTargetPort(1);channel.setMaximumBlockSeconds(2);config.getProxies().put("storage",channel);
            external=new ControlNode(config,pair.external.sql(),pair.external.db.site(),new ControlAudit(PRIVATE.resolve("external-state")));EXTERNAL_NODE.set(external);
            assertEquals("OBSERVED",external.snapshot().get("observationStatus"));
            pair.external.db.admin().execute("REVOKE SELECT ON eatwhat_file.file_sync_receipt FROM "+pair.external.db.runtimeUsername());denied=true;
            assertEquals("UNKNOWN",external.snapshot().get("observationStatus"));
            // Use the application's existing localhost CORS allowlist; do not strip Origin or relax CORS.
            page=HttpServer.create(new InetSocketAddress("127.0.0.1",5173),0);page.setExecutor(IO);page.createContext("/",exchange->{
                String path=exchange.getRequestURI().getPath();
                if(path.startsWith("/auth/") || path.startsWith("/admin/control/v1/")){relay(exchange,base());return;}
                String name=path.equals("/")?"index.html":path.substring(1);
                if(!name.matches("[a-z-]+\\.(html|css|js)")){send(exchange,404,new byte[0]);return;}
                Path asset=Path.of("frontend/control",name);if(!Files.isRegularFile(asset)){send(exchange,404,new byte[0]);return;}
                exchange.getResponseHeaders().set("Content-Type",name.endsWith(".js")?"text/javascript; charset=utf-8":name.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control","no-store");byte[] bytes=Files.readAllBytes(asset);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });page.start();
            Path bridge=scratch.resolve("bridge.json"),stage=scratch.resolve("stage.json"),resume=scratch.resolve("resume.json");
            Path output=Path.of("output/playwright/control-"+UUID.randomUUID()).toAbsolutePath();Files.createDirectories(output);
            Files.writeString(bridge,JSON.writeValueAsString(Map.of("origin",ORIGIN,"username",username,"ordinaryUsername",ordinaryUsername,"password",PASSWORD,"stage",stage.toString(),"resume",resume.toString(),"output",output.toString())));Files.setPosixFilePermissions(bridge,PosixFilePermissions.fromString("rw-------"));
            var builder=new ProcessBuilder(System.getProperty("eatwhat.acceptance.browser-node","node"),Path.of("src/test/resources/control-frontend-browser.mjs").toAbsolutePath().toString(),bridge.toString());builder.environment().put("EATWHAT_PLAYWRIGHT_MODULE",module);builder.redirectOutput(scratch.resolve("stdout").toFile());builder.redirectError(scratch.resolve("stderr").toFile());browser=builder.start();
            Instant limit=Instant.now().plusSeconds(90);while(!Files.exists(stage)&&browser.isAlive()&&Instant.now().isBefore(limit))Thread.sleep(100);
            assertTrue(Files.exists(stage),"Browser must restore a configured channel on the UNKNOWN external node; see report under "+output);
            assertEquals("RESTORED_OFFLINE",JSON.readTree(Files.readString(stage)).path("stage").asText());
            pair.external.db.admin().execute("GRANT SELECT ON eatwhat_file.file_sync_receipt TO "+pair.external.db.runtimeUsername());denied=false;assertEquals("OBSERVED",external.snapshot().get("observationStatus"));Files.writeString(resume,"{}");
            assertTrue(browser.waitFor(120,TimeUnit.SECONDS));assertEquals(0,browser.exitValue(),"Browser failed; report under "+output);
            var report=JSON.readTree(Files.readString(output.resolve("report.json")));assertEquals("PASS",report.path("status").asText());
            assertTrue(Files.readString(ControlPlaneIT.STATE.resolve("audit.jsonl")).contains("AUTO_RESTORE"));
        }finally {
            if(browser!=null&&browser.isAlive()){browser.destroy();if(!browser.waitFor(5,TimeUnit.SECONDS))browser.destroyForcibly();}
            if(denied)pair.external.db.admin().execute("GRANT SELECT ON eatwhat_file.file_sync_receipt TO "+pair.external.db.runtimeUsername());
            if(page!=null)page.stop(0);CAMPUS.stop(0);EXTERNAL.stop(0);if(external!=null)external.close();IO.shutdownNow();Files.deleteIfExists(scratch.resolve("bridge.json"));
        }
    }
    static void relay(HttpExchange exchange,String origin)throws java.io.IOException {
        try {
            if(origin==null)throw new IllegalStateException();
            var request=HttpRequest.newBuilder(URI.create(origin+exchange.getRequestURI())).timeout(Duration.ofSeconds(15));
            exchange.getRequestHeaders().forEach((name,values)->{if(!Set.of("host","connection","content-length","upgrade","http2-settings").contains(name.toLowerCase(Locale.ROOT)))values.forEach(v->request.header(name,v));});
            byte[] body=exchange.getRequestBody().readNBytes(65537);if(body.length>65536)throw new IllegalArgumentException();request.method(exchange.getRequestMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
            var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofByteArray());response.headers().map().forEach((name,values)->{if(!Set.of("connection","content-length","transfer-encoding").contains(name.toLowerCase(Locale.ROOT)))exchange.getResponseHeaders().put(name,values);});
            exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());exchange.close();
        }catch(Exception e){send(exchange,503,"{}".getBytes());}
    }
    static void send(HttpExchange exchange,int status,byte[] body)throws java.io.IOException {exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();}
}

package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.cluster.*;
import you.v50to.eatwhat.control.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Explicit manual-browser opt-in. Real Spring/auth/PG/Redis and static UI; task journals are test seeds.
 * No remote task runs: the SSH bean is a strict observed boundary, not a simulated successful executor. */
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ControlTaskFrontendAcceptance extends SingleAuthorityEnrollmentIT {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String PRIVATE_MARKER="synthetic-ui-private-marker-never-render";
    @Autowired ControlServerStore serverStore;
    @Autowired ControlServers servers;
    @MockitoBean ControlSsh ssh;
    final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();
    final Queue<Map<String,Object>> requests=new ConcurrentLinkedQueue<>();
    final java.util.concurrent.atomic.AtomicBoolean privateLeakDetected=new java.util.concurrent.atomic.AtomicBoolean();
    final UUID serverId=UUID.randomUUID();

    @AfterAll static void stopDependencies(){REDIS.stop();PG.stop();}
    @AfterEach void stopBrowserThreads(){io.shutdownNow();}

    Map<String,String> seedHistory()throws Exception {
        var p=new ClusterProperties.Profile();p.setSiteId("installed-campus");p.setInstanceId(INSTANCE);p.setMode("CAMPUS");p.setAllocationSlot(0);p.setOrigin("http://127.0.0.1:"+port);p.setTokenFile(tokenFile);settings.setProfiles(Map.of("authority",p));profiles.reload(List.of());assertTrue(lease.tick());
        coordinator.register(Long.toString(user),UUID.randomUUID(),0,"authority");UUID join=UUID.randomUUID();coordinator.begin(Long.toString(user),join,0,"JOIN","installed-campus");
        for(int i=0;i<3&&!"APPLIED".equals(membership.change(join).get("state"));i++)coordinator.reconcile(Long.toString(user),join);
        assertEquals("APPLIED",membership.change(join).get("state"));
        db.runtime().update("INSERT INTO users(nick_name,password_hash,role) VALUES('fixture-viewer',?,'user')",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("synthetic-password"));
        byte[] type="ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hostKey="ssh-ed25519 "+Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(4+type.length+4+32).putInt(type.length).put(type).putInt(32).put(new byte[32]).array());
        servers.save(Long.toString(user),serverId,new ControlServerController.ServerInput(serverId,null,"Browser fixture server","fixture.example.invalid",22,"root","REMOTE","PASSWORD","synthetic-private-ssh",hostKey,ControlSsh.key(hostKey).get("fingerprint")),true);
        serverStore.mutate(data->{
            @SuppressWarnings("unchecked") var servers=(Map<String,Object>)data.get("servers");
            @SuppressWarnings("unchecked") var server=(Map<String,Object>)servers.get(serverId.toString());server.put("installation",Map.of("state","FAILED"));
            return null;
        });
        assertEquals("Browser fixture server",servers.list().getFirst().get("name"));
        var ids=new LinkedHashMap<String,String>();Instant now=Instant.now();
        for(int i=0;i<25;i++) {
            String kind=List.of("INSTALL","UPDATE","RECOVER","ENROLLMENT","DEPLOYMENT").get(i%5);UUID id=UUID.randomUUID();ids.putIfAbsent(kind,id.toString());
            String state=kind.equals("UPDATE")||kind.equals("ENROLLMENT")?"SUCCEEDED":"FAILED";
            var job=new LinkedHashMap<String,Object>();job.put("id",id.toString());job.put("kind",kind);job.put("state",state);job.put("createdAt",now.minusSeconds(i+60).toString());job.put("serverId",serverId.toString());job.put("siteId","installed-campus");job.put("artifactSha256","a".repeat(64));job.put("_private",PRIVATE_MARKER);
            if(kind.equals("DEPLOYMENT")){job.put("releaseId","fixture-candidate");job.put("targets",List.of(Map.of("siteId","installed-campus","state","FAILED","stage","STARTING","failureStage","STARTING","code","RELEASE_APPLICATION_NOT_READY")));}
            else if(kind.equals("ENROLLMENT"))job.put("result",Map.of("clusterMembership","ACTIVE","changeState","APPLIED","epoch",1));
            else {job.put("result",Map.of("stage","DATABASE_REDIS","code",kind.equals("RECOVER")?"RECOVERY_FAILED_PRESERVED":"FIRST_INSTALL_FAILED_PRESERVED"));}
            if(i==0){job.put("state","RUNNING");job.put("code","SERVER_TASK_OUTCOME_UNKNOWN");ids.put("UNKNOWN",id.toString());}
            String prefix=kind.equals("DEPLOYMENT")?"deployment-":kind.equals("ENROLLMENT")?"server-cluster-job-":"server-job-";
            Path file=serverStore.directory().resolve(prefix+id+".json");serverStore.saveJson(file,job);Files.setLastModifiedTime(file,FileTime.from(now.minusSeconds(i)));
        }
        return ids;
    }

    HttpResponse<String> api(String method,String path,Object input,String token)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(token!=null)request.header("eatwhat-test-token",token.startsWith("Bearer ")?token:"Bearer "+token);
        return http.send(request.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
    }
    String token(String username)throws Exception {var response=api("POST","/auth/login",Map.of("username",username,"password","synthetic-password","device","WEB"),null);assertEquals(200,response.statusCode());var result=JSON.readTree(response.body());assertEquals(0,result.path("code").asInt(-1));return result.path("data").path("tokenValue").asText();}
    @Test void realTaskHistoryHttpCombinesAllReadersPaginationFiltersCorruptionAndAuthorization()throws Exception {
        var ids=seedHistory();String admin=token("fixture-admin"),viewer=token("fixture-viewer");String root="/admin/control/v1/tasks";
        assertEquals(401,api("GET",root,null,null).statusCode());assertEquals(403,api("GET",root,null,viewer).statusCode());
        var first=api("GET",root+"?limit=20",null,admin);assertEquals(200,first.statusCode());assertTrue(first.headers().firstValue("Cache-Control").orElse("").contains("no-store"));var page=JSON.readTree(first.body());assertEquals(20,page.path("items").size());String cursor=page.path("nextCursor").asText();assertFalse(cursor.isBlank());
        var second=api("GET",root+"?limit=20&cursor="+cursor,null,admin);assertEquals(200,second.statusCode());var tail=JSON.readTree(second.body());assertEquals(5,tail.path("items").size());assertTrue(tail.path("nextCursor").isNull());
        var unique=new HashSet<String>();for(var row:page.path("items"))assertTrue(unique.add(row.path("id").asText()));for(var row:tail.path("items"))assertTrue(unique.add(row.path("id").asText()));assertEquals(25,unique.size());
        for(String kind:List.of("INSTALL","UPDATE","RECOVER","ENROLLMENT","DEPLOYMENT")){
            var filtered=api("GET",root+"?type="+kind+"&limit=100&serverId="+serverId,null,admin);assertEquals(200,filtered.statusCode());var rows=JSON.readTree(filtered.body()).path("items");assertEquals(5,rows.size());for(var row:rows)assertEquals(kind,row.path("type").asText());
            String prefix=kind.equals("DEPLOYMENT")?"deployments/":kind.equals("ENROLLMENT")?"server-cluster-jobs/":"server-jobs/";var detail=api("GET","/admin/control/v1/"+prefix+ids.get(kind),null,admin);assertEquals(200,detail.statusCode());assertFalse(detail.body().contains(PRIVATE_MARKER));assertFalse(detail.body().contains("_private"));assertEquals(403,api("GET","/admin/control/v1/"+prefix+ids.get(kind),null,viewer).statusCode());
        }
        var unknown=api("GET",root+"?state=OUTCOME_UNKNOWN",null,admin);assertEquals(1,JSON.readTree(unknown.body()).path("items").size());assertEquals(ids.get("UNKNOWN"),JSON.readTree(unknown.body()).path("items").get(0).path("id").asText());assertTrue(JSON.readTree(unknown.body()).path("items").get(0).path("requiresReconciliation").asBoolean());
        var noServer=api("GET",root+"?serverId="+UUID.randomUUID(),null,admin);assertEquals(200,noServer.statusCode());assertEquals(0,JSON.readTree(noServer.body()).path("items").size());
        assertEquals(400,api("GET",root+"?cursor=invalid",null,admin).statusCode());assertEquals(400,api("GET",root+"?type=UNRECOGNIZED",null,admin).statusCode());
        Path corrupt=serverStore.directory().resolve("server-job-"+UUID.randomUUID()+".json");Files.writeString(corrupt,"{test-corrupt");Files.setPosixFilePermissions(corrupt,PosixFilePermissions.fromString("rw-------"));
        try{var failed=api("GET",root,null,admin);assertEquals(503,failed.statusCode());assertEquals("TASK_HISTORY_UNAVAILABLE",JSON.readTree(failed.body()).path("code").asText());assertFalse(failed.body().contains(PRIVATE_MARKER));}finally{Files.delete(corrupt);}
        assertEquals(200,api("GET",root,null,admin).statusCode());verifyNoInteractions(ssh);
    }

    @Test void holdRealBackendForTaskHistoryBrowserAcceptance()throws Exception {
        String configured=System.getProperty("eatwhat.acceptance.task-browser-directory");
        assertNotNull(configured,"Explicit private acceptance directory required");
        Path bridge=Path.of(configured).toAbsolutePath();assertTrue(Files.isDirectory(bridge));
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(bridge));
        HttpServer page=null;
        try {
            var ids=seedHistory();
            page=HttpServer.create(new InetSocketAddress("127.0.0.1",5173),0);page.setExecutor(io);
            page.createContext("/",exchange->{
                try {
                    String path=exchange.getRequestURI().getPath();
                    if(path.startsWith("/auth/")||path.startsWith("/admin/control/v1/")) {
                        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+exchange.getRequestURI())).timeout(Duration.ofSeconds(20));
                        exchange.getRequestHeaders().forEach((name,values)->{if(!Set.of("host","connection","content-length","upgrade","http2-settings","transfer-encoding").contains(name.toLowerCase(Locale.ROOT)))values.forEach(v->builder.header(name,v));});
                        byte[] input=exchange.getRequestBody().readNBytes(65537);if(input.length>65536)throw new IllegalArgumentException();builder.method(exchange.getRequestMethod(),input.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(input));
                        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
                        if(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8).contains(PRIVATE_MARKER)){privateLeakDetected.set(true);throw new java.io.IOException("Private fixture marker in response");}
                        requests.add(Map.of("method",exchange.getRequestMethod(),"path",exchange.getRequestURI().toString(),"status",response.statusCode()));
                        write(bridge.resolve("requests.json"),requests);
                        response.headers().map().forEach((name,values)->{if(!Set.of("connection","content-length","transfer-encoding").contains(name.toLowerCase(Locale.ROOT)))exchange.getResponseHeaders().put(name,values);});
                        exchange.sendResponseHeaders(response.statusCode(),response.body().length==0?-1:response.body().length);if(response.body().length>0)exchange.getResponseBody().write(response.body());return;
                    }
                    String name=path.equals("/control-ui/")?"index.html":path.startsWith("/control-ui/")?path.substring("/control-ui/".length()):"";
                    if(!name.matches("[a-z-]+\\.(html|css|js)")&&!name.equals("vendor/opencc/t2cn.js")){exchange.sendResponseHeaders(404,-1);return;}
                    Path asset=Path.of("frontend/control",name);if(!Files.isRegularFile(asset)){exchange.sendResponseHeaders(404,-1);return;}
                    exchange.getResponseHeaders().set("Content-Type",name.endsWith(".js")?"text/javascript; charset=utf-8":name.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");byte[] bytes=Files.readAllBytes(asset);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Exception failure){try{exchange.sendResponseHeaders(502,-1);}catch(Exception ignored){}}
                finally{exchange.close();}
            });page.start();
            for(String asset:List.of("index.html","app.js","locale.js","vendor/opencc/t2cn.js")){
                var checked=http.send(HttpRequest.newBuilder(URI.create("http://localhost:5173/control-ui/"+asset)).GET().build(),HttpResponse.BodyHandlers.discarding());assertEquals(200,checked.statusCode(),asset);
            }
            write(bridge.resolve("ready.json"),Map.of("status","READY","url","http://localhost:5173/control-ui/#/tasks","ids",ids,"serverId",serverId.toString(),"scope","REAL_BACKEND_WITH_SEEDED_TASK_JOURNALS"));
            Path corrupt=serverStore.directory().resolve("server-job-"+UUID.randomUUID()+".json");
            Instant deadline=Instant.now().plusSeconds(1200);String last="";boolean done=false;
            while(Instant.now().isBefore(deadline)) {
                Path command=bridge.resolve("command.json");
                if(Files.exists(command)) {
                    var message=JSON.readTree(Files.readString(command));String phase=message.path("phase").asText();
                    if(!phase.equals(last)) {
                        if(phase.equals("CORRUPT")){Files.writeString(corrupt,"{malformed-test-journal",StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(corrupt,PosixFilePermissions.fromString("rw-------"));}
                        else if(phase.equals("RESTORE"))Files.deleteIfExists(corrupt);
                        else if(phase.equals("CANCEL")){write(bridge.resolve("backend-report.json"),Map.of("status","INCOMPLETE","reason","BROWSER_ACCEPTANCE_CANCELLED","productionDeployment",false));Assumptions.abort("Browser acceptance cancelled without claiming completion");}
                        else if(phase.equals("DONE")){done=true;break;}
                        else fail("Unknown fixture phase");
                        last=phase;write(bridge.resolve("phase.json"),Map.of("phase",phase));
                    }
                }
                Thread.sleep(200);
            }
            assertTrue(done,"Manual browser acceptance did not finish within the explicit fixture deadline");
            assertFalse(privateLeakDetected.get(),"A response contained private journal fields");
            verifyNoInteractions(ssh);
            assertTrue(requests.stream().anyMatch(r->r.get("path").toString().startsWith("/admin/control/v1/tasks?")&&r.get("status").equals(200)));
            assertTrue(requests.stream().anyMatch(r->r.get("path").toString().startsWith("/admin/control/v1/tasks?")&&r.get("status").equals(503)));
            assertTrue(requests.stream().anyMatch(r->r.get("status").equals(403)));
            write(bridge.resolve("backend-report.json"),Map.of("status","PASS","scope","REAL_SPRING_AUTH_PG_REDIS_SEEDED_JOURNAL_BROWSER_READS","requests",requests,"sshCalls",0,"productionDeployment",false));
        }finally{if(page!=null)page.stop(0);io.shutdownNow();}
    }
    static void write(Path path,Object value)throws Exception{Path temp=Files.createTempFile(path.getParent(),"task-browser-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));try{Files.writeString(temp,JSON.writeValueAsString(value));Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
}

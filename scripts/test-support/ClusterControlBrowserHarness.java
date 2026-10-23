package you.v50to.eatwhat.integration;

import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone optional browser acceptance; reuses the compiled, isolated three-server fixture. */
public final class ClusterControlBrowserHarness {
    static final ClusterSeamlessIT fixture=new ClusterSeamlessIT();
    static final ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();
    static final AtomicBoolean closed=new AtomicBoolean();
    static HttpServer page;
    static void close(){if(!closed.compareAndSet(false,true))return;if(page!=null)page.stop(0);io.shutdownNow();try{fixture.stop();}catch(Exception ignored){}try{ClusterSeamlessIT.cleanup();}catch(Exception ignored){}}
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Browser driver and output directory required");
        Path output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);
        fixture.directory=Files.createTempDirectory("eatwhat-cluster-browser-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Runtime.getRuntime().addShutdownHook(new Thread(ClusterControlBrowserHarness::close));
        Process browser=null;
        try {
            ClusterSeamlessIT.services();fixture.prepare();
            // Configure disposable local control channels and an inert preview artifact, then restart our own nodes.
            fixture.stop();fixture.processes.clear();
            Path artifact=fixture.directory.resolve("synthetic-release.jar");Files.writeString(artifact,"isolated preview only; never executable");
            Path runner=Files.createDirectory(fixture.directory.resolve("inert-runner"));
            Path wrapper=runner.resolve("mvnw");Files.writeString(wrapper,"#!/bin/sh\nexec /bin/sleep 120\n");
            Files.setPosixFilePermissions(wrapper,PosixFilePermissions.fromString("rwx------"));
            String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
            for(int n=0;n<3;n++){
                Path file=fixture.directory.resolve("node-"+n+".properties");var properties=new Properties();try(var in=Files.newInputStream(file)){properties.load(in);}
                int port;try(var free=new java.net.ServerSocket(0)){port=free.getLocalPort();}
                String prefix="app.control.proxies.events.";properties.setProperty(prefix+"listen-port",Integer.toString(port));properties.setProperty(prefix+"target-host","127.0.0.1");properties.setProperty(prefix+"target-port","9");properties.setProperty(prefix+"maximum-block-seconds","2");
                if(n==0){for(var member:fixture.members)properties.setProperty("app.cluster.profiles."+member.siteId()+".channels.events","2");properties.setProperty("app.control.releases.synthetic.artifact",artifact.toString());properties.setProperty("app.control.releases.synthetic.sha256",digest);properties.setProperty("app.control.repository",runner.toString());properties.setProperty("app.control.java-home",System.getProperty("java.home"));}
                try(var out=Files.newOutputStream(file)){properties.store(out,"synthetic isolated browser acceptance");}
                var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx384m","-cp",System.getProperty("surefire.test.class.path"),ClusterNodeProcessMain.class.getName(),file.toString());child.redirectErrorStream(true);child.redirectOutput(fixture.directory.resolve("node-"+n+".log").toFile());fixture.processes.add(child.start());
            }
            for(int n=0;n<3;n++){final int node=n;ClusterSeamlessIT.await(90,()->{if(!fixture.processes.get(node).isAlive())throw new AssertionError("Owned browser test node exited");try{return fixture.machine(node,"GET","probe",null).statusCode()==200;}catch(Exception starting){return false;}});}
            fixture.adminToken=fixture.login(0,"meshadmin");fixture.join(0,0);fixture.adminToken=fixture.login(0,"meshadmin");fixture.join(1,1);
            var registered=fixture.request(0,"POST","/auth/register",Map.of("username","meshviewer","password","synthetic-password","device","WEB","crossBorderConsent",false),null);
            if(ClusterSeamlessIT.JSON.readTree(registered.body()).path("code").asInt(-1)!=0)throw new AssertionError("Ordinary synthetic account registration failed");
            page=HttpServer.create(new InetSocketAddress("127.0.0.1",5173),0);page.setExecutor(io);page.createContext("/",ClusterControlBrowserHarness::serve);page.start();
            Path bridge=fixture.directory.resolve("bridge.json");Files.writeString(bridge,ClusterSeamlessIT.JSON.writeValueAsString(Map.of("origin","http://localhost:5173","username","meshadmin","ordinaryUsername","meshviewer","password","synthetic-password","output",output.toString())));Files.setPosixFilePermissions(bridge,PosixFilePermissions.fromString("rw-------"));
            var driver=new ProcessBuilder(System.getenv().getOrDefault("EATWHAT_BROWSER_NODE","node"),Path.of(args[0]).toAbsolutePath().toString(),bridge.toString());driver.redirectOutput(output.resolve("browser.stdout.log").toFile());driver.redirectError(output.resolve("browser.stderr.log").toFile());browser=driver.start();
            if(!browser.waitFor(300,TimeUnit.SECONDS))throw new AssertionError("Browser acceptance exceeded its deadline");if(browser.exitValue()!=0)throw new AssertionError("Browser acceptance failed; inspect output report");
            var db=fixture.databases.getFirst().admin();if(!"RETIRED".equals(db.queryForObject("SELECT state FROM eatwhat_cluster.member WHERE site_id='third'",String.class)))throw new AssertionError("UI must retire the joined third site");
            if(db.queryForObject("SELECT epoch FROM eatwhat_cluster.state WHERE singleton",Long.class)!=4L)throw new AssertionError("UI membership epoch must be four");
            System.out.println("PASS: real three-server control browser acceptance; output="+output);
        }finally{
            if(browser!=null&&browser.isAlive()){browser.destroy();if(!browser.waitFor(5,TimeUnit.SECONDS))browser.destroyForcibly();}
            close();
            try(var files=Files.walk(fixture.directory)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    static void serve(HttpExchange exchange)throws java.io.IOException {
        try{
            String path=exchange.getRequestURI().getPath();
            if(path.startsWith("/auth/")||path.startsWith("/admin/control/v1/")){
                var request=HttpRequest.newBuilder(URI.create(fixture.base(0)+exchange.getRequestURI())).timeout(Duration.ofSeconds(120));
                exchange.getRequestHeaders().forEach((name,values)->{if(!Set.of("host","connection","content-length","upgrade","http2-settings").contains(name.toLowerCase(Locale.ROOT)))values.forEach(value->request.header(name,value));});
                byte[] body=exchange.getRequestBody().readNBytes(65537);if(body.length>65536){send(exchange,413,new byte[0]);return;}
                request.method(exchange.getRequestMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
                var response=fixture.http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());response.headers().map().forEach((name,values)->{if(!Set.of("content-length","connection","transfer-encoding").contains(name.toLowerCase(Locale.ROOT)))values.forEach(value->exchange.getResponseHeaders().add(name,value));});send(exchange,response.statusCode(),response.body());return;
            }
            String name=path.equals("/")?"index.html":path.substring(1);if(!name.matches("[a-z-]+\\.(html|css|js)")&&!name.equals("vendor/opencc/t2cn.js")){send(exchange,404,new byte[0]);return;}
            Path file=Path.of("frontend/control",name);if(!Files.isRegularFile(file)){send(exchange,404,new byte[0]);return;}
            exchange.getResponseHeaders().set("Content-Type",name.endsWith(".js")?"text/javascript; charset=utf-8":name.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");send(exchange,200,Files.readAllBytes(file));
        }catch(Exception failure){send(exchange,503,"{\"code\":\"BROWSER_TEST_RELAY_UNAVAILABLE\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    static void send(HttpExchange exchange,int status,byte[] bytes)throws java.io.IOException {exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
}

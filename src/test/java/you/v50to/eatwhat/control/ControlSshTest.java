package you.v50to.eatwhat.control;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ControlSshTest {
    @TempDir Path temp;ControlProperties settings;ControlServerStore store;
    @BeforeEach void setup()throws Exception{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));store=new ControlServerStore(new ControlAudit(temp));settings=new ControlProperties();}
    String generate(String name)throws Exception{Path key=temp.resolve(name);var p=new ProcessBuilder("/usr/bin/ssh-keygen","-q","-t","ed25519","-N","","-f",key.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();assertTrue(p.waitFor(5,TimeUnit.SECONDS));assertEquals(0,p.exitValue());return Files.readString(key.resolveSibling(name+".pub")).strip().split(" ")[0]+" "+Files.readString(key.resolveSibling(name+".pub")).strip().split(" ")[1];}
    Map<String,Object> server(String key,String authentication,String credential,int port){return Map.of("host","127.0.0.1","port",port,"username",System.getProperty("user.name"),"hostKey",key,"authentication",authentication,"credential",store.encrypt(credential));}
    @Test void elevationIsExplicitAndUsesOnlyFixedNoninteractiveSudoWithoutConsumingStdin()throws Exception{
        String key=generate("sudo-host");Path executable=temp.resolve("sudo-command-fixture");
        Files.writeString(executable,"#!/bin/sh\nfor argument do remote=$argument; done\ncase \"$remote\" in 'sudo -n -- python3 -c '*) mode=SUDO;; 'python3 -c '*) mode=NONE;; *) exit 1;; esac\n[ \"$(cat)\" = synthetic-stdin ] || exit 1\nprintf '{\"privilege\":\"%s\"}' \"$mode\"\n");
        Files.setPosixFilePermissions(executable,PosixFilePermissions.fromString("rwx------"));settings.getDeployment().setSshExecutable(executable);var ssh=new ControlSsh(store,settings);
        var target=new LinkedHashMap<>(server(key,"PRIVATE_KEY","synthetic fixture credential",22));
        for(String mode:List.of("NONE","SUDO")){
            if(mode.equals("SUDO"))target.put("privilege",mode);
            assertEquals(mode,ssh.call(target,"print('fixture')",List.of(),out->out.write("synthetic-stdin".getBytes()),5).get("privilege"));
        }
        target.put("privilege","SUDO; arbitrary-command");assertThrows(ControlFailure.class,()->ssh.call(target,"print('fixture')",List.of(),out->{},5));
        assertFalse(ControlSsh.rootExecution(Map.of("username","ubuntu")));assertTrue(ControlSsh.rootExecution(Map.of("username","ubuntu","privilege","SUDO")));
    }
    @Test void knownHostsAreFoundByRealOpenSshLookupAtDefaultAndCustomPorts()throws Exception{
        String key=generate("lookup-host");Path known=temp.resolve("known_hosts");
        for(String host:List.of("127.0.0.1","::1","server.example"))for(int port:List.of(22,2222)){
            Files.writeString(known,ControlSsh.knownHost(host,port)+" "+key+"\n");
            String lookup=port==22?host:"["+host+"]:2222";
            var process=new ProcessBuilder("/usr/bin/ssh-keygen","-F",lookup,"-f",known.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertTrue(process.waitFor(5,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),host+":"+port);assertTrue(new String(process.getInputStream().readAllBytes()).contains(key));
        }
    }
    @Test void passwordUsesPrivateAskpassWithoutArgumentsEnvironmentSecretsOrResidualFiles()throws Exception{
        String key=generate("host");Path executable=temp.resolve("fake-ssh");Files.writeString(executable,"#!/bin/sh\n[ \"$SSH_ASKPASS_REQUIRE\" = force ] || exit 1\n[ \"$(\"$SSH_ASKPASS\")\" = synthetic-password-fixture ] || exit 1\ncase \"$*\" in *synthetic-password-fixture*) exit 1;; esac\n[ -z \"$SSH_AUTH_SOCK\" ] || exit 1\ncat >/dev/null\nprintf '{\"protocol\":\"password-helper-ok\"}'\n");Files.setPosixFilePermissions(executable,PosixFilePermissions.fromString("rwx------"));settings.getDeployment().setSshExecutable(executable);var ssh=new ControlSsh(store,settings);assertEquals("password-helper-ok",ssh.call(server(key,"PASSWORD","synthetic-password-fixture",22),"print('not executed by fixture')",List.of(),out->out.write("synthetic-payload".getBytes()),5).get("protocol"));try(var files=Files.list(store.directory())){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith("ssh-session-")));}
    }
    @Test void actualOpenSshKeyAuthenticationPinsHostAndRejectsAChangedHostKey()throws Exception{
        String key=generate("host");generate("identity");String wrong=generate("wrong");Path authorized=temp.resolve("authorized_keys");Files.copy(temp.resolve("identity.pub"),authorized);Files.setPosixFilePermissions(authorized,PosixFilePermissions.fromString("rw-------"));int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        Path config=temp.resolve("sshd_config");Files.writeString(config,"Port "+port+"\nListenAddress 127.0.0.1\nHostKey "+temp.resolve("host")+"\nPidFile "+temp.resolve("sshd.pid")+"\nAuthorizedKeysFile "+authorized+"\nPasswordAuthentication no\nKbdInteractiveAuthentication no\nUsePAM no\nStrictModes yes\nAllowUsers "+System.getProperty("user.name")+"\nAllowTcpForwarding no\nX11Forwarding no\nPermitTunnel no\nForceCommand /usr/bin/printf '{\"protocol\":\"synthetic-real-ssh\"}'\n");Path log=temp.resolve("sshd.log");Process process=new ProcessBuilder("/usr/sbin/sshd","-D","-e","-f",config.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            boolean ready=false;for(int n=0;n<100&&process.isAlive();n++){try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),100);ready=true;break;}catch(Exception e){Thread.sleep(30);}}
            assertTrue(ready,()->"Owned loopback sshd failed: "+read(log));var ssh=new ControlSsh(store,settings);var keys=ssh.scan("127.0.0.1",port);assertTrue(keys.stream().anyMatch(k->k.get("publicKey").equals(key)));assertEquals("synthetic-real-ssh",ssh.call(server(key,"PRIVATE_KEY",Files.readString(temp.resolve("identity")),port),"print('fixture')",List.of(),out->{},15).get("protocol"));
            UUID id=UUID.randomUUID();Path routes=temp.resolve("routes.json");
            var route=Map.of("host","127.0.0.1","port",port,"logicalHost","127.0.0.1","logicalPort",22,"hostKey",key);
            ControlSsh.secret(routes,tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("version",1,"routes",Map.of(id.toString(),route))));
            settings.setSharedStateRequired(true);settings.getServers().setSshRoutesFile(routes.toRealPath());var routed=new ControlSsh(store,settings);
            var logical=new LinkedHashMap<>(server(key,"PRIVATE_KEY",Files.readString(temp.resolve("identity")),22));logical.put("id",id.toString());
            assertEquals("synthetic-real-ssh",routed.call(logical,"print('fixture')",List.of(),out->{},15).get("protocol"));
            logical.put("hostKey",wrong);assertEquals("SSH_ROUTE_BINDING_CHANGED",assertThrows(ControlFailure.class,()->routed.call(logical,"print('fixture')",List.of(),out->{},10)).code);
            settings.setSharedStateRequired(false);
assertThrows(Exception.class,()->ssh.call(server(wrong,"PRIVATE_KEY",Files.readString(temp.resolve("identity")),port),"print('fixture')",List.of(),out->{},10));
        }finally{process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
    }
    static String read(Path p){try{return Files.readString(p);}catch(Exception e){return "no diagnostics";}}
    @Test void remoteRefusalExposesOnlyCodesPresentInReviewedProtocolSources()throws Exception{
        for(String code:List.of("RELEASE_PROJECT_BUSY","RELEASE_SYNTHETIC_SECRET_DO_NOT_ECHO")){
            String reply="{\"protocol\":\"eatwhat-cluster-release-v1\",\"code\":\""+code+"\",\"detail\":\"synthetic-private-output\"}";
            var failure=assertThrows(java.io.IOException.class,()->ControlSsh.process(List.of("/bin/sh","-c","printf '%s' '"+reply+"'; exit 1"),Map.of(),out->{},5,65536,true));
            assertFalse(failure.getMessage().contains("synthetic-private"));if(code.equals("RELEASE_PROJECT_BUSY"))assertEquals(code,assertInstanceOf(ControlSsh.RemoteRefusal.class,failure).code);else assertFalse(failure instanceof ControlSsh.RemoteRefusal);
        }
    }
    @Test void largeReviewedSourceUsesCountedStdinWithoutChangingArgumentsOrArtifactBytes()throws Exception{
        String key=generate("framed-source-host");Path executable=temp.resolve("execute-owned-loader");
        Files.writeString(executable,"#!/bin/sh\nfor argument do remote=$argument; done\n[ ${#remote} -lt 1024 ] || exit 1\nexec /bin/sh -c \"$remote\"\n");
        Files.setPosixFilePermissions(executable,PosixFilePermissions.fromString("rwx------"));settings.getDeployment().setSshExecutable(executable);
        byte[] publicNoise=new byte[160000];new Random(379).nextBytes(publicNoise);
        String script="# reviewed public source fixture "+Base64.getEncoder().encodeToString(publicNoise)+"\nimport sys,json,hashlib\nraw=sys.stdin.buffer.read()\nprint(json.dumps({'args':sys.argv[1:],'sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw)}))\n";
        byte[] upload=new byte[100000];new Random(419).nextBytes(upload);upload[0]=0;
        var result=new ControlSsh(store,settings).call(server(key,"PRIVATE_KEY","synthetic-only",22),script,List.of("inspect","original_id"),out->out.write(upload),10);
        assertEquals(List.of("inspect","original_id"),result.get("args"));assertEquals(upload.length,((Number)result.get("bytes")).intValue());
        assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(upload)),result.get("sha256"));
        try(var files=Files.list(store.directory())){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith("ssh-session-")));}
    }
    @Test void memberAndProfileRefusalsExposeOnlyFixedReviewedCodes()throws Exception{
        for(var item:List.of(
                List.of("eatwhat-controller-profile-publication-v1","BOOTSTRAP_PROFILE_REQUEST_UNAVAILABLE","yes"),
                List.of("eatwhat-controller-bootstrap-member-relay-v1","BOOTSTRAP_MEMBER_RELAY_NETWORK_CHANGED","yes"),
                List.of("eatwhat-member-configure-v1","MEMBER_CONFIGURATION_PRECONDITION_FAILED","yes"),
                List.of("eatwhat-member-configure-v1","MEMBER_SYNTHETIC_PRIVATE_SECRET","no"),
                List.of("unreviewed-protocol","BOOTSTRAP_PROFILE_REQUEST_UNAVAILABLE","no"))){
            String reply="{\"protocol\":\""+item.get(0)+"\",\"code\":\""+item.get(1)+"\",\"detail\":\"synthetic-private-output\"}";
            var error=assertThrows(java.io.IOException.class,()->ControlSsh.process(List.of("/bin/sh","-c","printf '%s' '"+reply+"'; exit 1"),Map.of(),out->{},5,65536,true));
            assertFalse(error.getMessage().contains("synthetic-private"));
            if(item.get(2).equals("yes"))assertEquals(item.get(1),assertInstanceOf(ControlSsh.RemoteRefusal.class,error).code);
            else assertFalse(error instanceof ControlSsh.RemoteRefusal);
        }
    }
}

package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine OpenSSH with pinned keys and both auth modes. Not a Compose/application rollout test. */
@EnabledIfSystemProperty(named="eatwhat.test.ssh-image",matches=".+")
class ControlSshTransportIT {
    @TempDir static Path input;@TempDir Path root;
    static GenericContainer<?> host;static Path clientKey;static String password,hostKey,script;static final JsonMapper JSON=JsonMapper.builder().build();
    ControlServerStore store;ControlSsh ssh;
    @BeforeAll static void start()throws Exception{
        String image=System.getProperty("eatwhat.test.ssh-image");assertTrue(image.matches("sha256:[0-9a-f]{64}"),"Use the locally built immutable image ID");
        Files.setPosixFilePermissions(input,PosixFilePermissions.fromString("rwx------"));clientKey=input.resolve("client");
        var keygen=new ProcessBuilder("/usr/bin/ssh-keygen","-q","-t","ed25519","-N","","-f",clientKey.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();assertTrue(keygen.waitFor(10,TimeUnit.SECONDS));assertEquals(0,keygen.exitValue());
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);password=Base64.getUrlEncoder().withoutPadding().encodeToString(random);secret(input.resolve("password"),password);
        script="import hashlib,json,sys\nh=json.loads(sys.stdin.buffer.readline(65537));data=sys.stdin.buffer.read(1048577)\nassert len(data)<=1048576 and sys.argv[1:]==['echo']\nprint(json.dumps({'protocol':'eatwhat-ssh-fixture-v1','nonce':h['nonce'],'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}))\n#"+"padding".repeat(9200)+"\n";
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));secret(input.resolve("allowed-sources.json"),JSON.writeValueAsString(Map.of(digest,List.of(List.of("echo")))));
        host=new GenericContainer<>(DockerImageName.parse(image)).withImagePullPolicy(name->false).withFileSystemBind(input.toString(),"/fixture-input",BindMode.READ_ONLY).withExposedPorts(22).withCreateContainerCmdModifier(command->command.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"),new ExposedPort(22)))).waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(60));
        host.start();var read=host.execInContainer("cat","/run/eatwhat-ssh/host_key.pub");assertEquals(0,read.getExitCode());String[] parts=read.getStdout().strip().split(" +");hostKey=parts[0]+" "+parts[1];ControlSsh.key(hostKey);
    }
    @AfterAll static void stop(){if(host!=null)host.stop();}
    @BeforeEach void setup(){store=new ControlServerStore(new ControlAudit(root.resolve("state")));ssh=new ControlSsh(store,new ControlProperties());}
    Map<String,Object> server(String mode)throws Exception{return new LinkedHashMap<>(Map.of("host","127.0.0.1","port",host.getMappedPort(22),"username","root","hostKey",hostKey,"authentication",mode,"credential",store.encrypt(mode.equals("PASSWORD")?password:Files.readString(clientKey))));}
    Map<String,Object> echo(Map<String,Object> server,String code,List<String> args)throws Exception{String nonce=UUID.randomUUID().toString();byte[] payload=new byte[262144];new SecureRandom().nextBytes(payload);var reply=ssh.call(server,code,args,out->{out.write(JSON.writeValueAsBytes(Map.of("nonce",nonce)));out.write('\n');out.write(payload);},30);assertEquals("eatwhat-ssh-fixture-v1",reply.get("protocol"));assertEquals(nonce,reply.get("nonce"));assertEquals(payload.length,((Number)reply.get("bytes")).intValue());assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),reply.get("sha256"));return reply;}
    @Test void realPasswordAndPrivateKeyAuthenticationDeliverBoundedPayloadAndCleanLocalSecrets()throws Exception{
        for(String mode:List.of("PASSWORD","PRIVATE_KEY")){echo(server(mode),script,List.of("echo"));try(var paths=Files.list(store.directory())){assertEquals(0,paths.filter(path->path.getFileName().toString().startsWith("ssh-session-")).count());}}
    }
    @Test void scannedHostKeyMatchesTheIndependentServerPublicKeyAndWrongPinIsRejected()throws Exception{
        assertTrue(ssh.scan("127.0.0.1",host.getMappedPort(22)).stream().anyMatch(key->key.get("fingerprint").equals(ControlSsh.key(hostKey).get("fingerprint"))));var target=server("PRIVATE_KEY");byte[] type="ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII);target.put("hostKey","ssh-ed25519 "+Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(4+type.length+4+32).putInt(type.length).put(type).putInt(32).put(new byte[32]).array()));assertThrows(Exception.class,()->echo(target,script,List.of("echo")));
    }
    @Test void wrongPasswordAndUnreviewedSourceOrActionCannotExecute()throws Exception{
        var wrong=server("PASSWORD");wrong.put("credential",store.encrypt("wrong-synthetic-password"));assertThrows(Exception.class,()->echo(wrong,script,List.of("echo")));
        assertThrows(Exception.class,()->echo(server("PRIVATE_KEY"),script+"#not-reviewed\n",List.of("echo")));
        assertThrows(Exception.class,()->echo(server("PRIVATE_KEY"),script,List.of("not-allowed")));
    }
    static void secret(Path path,String value)throws Exception{Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(path,value);}
}

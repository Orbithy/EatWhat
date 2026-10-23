package you.v50to.eatwhat.control.bootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.support.SourceMaterialFixture;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControllerSourceMaterialsTest {
    public static class IndexProcess {
        public static void main(String[] args)throws Exception{
            var expected=JsonMapper.builder().build().readValue(System.in.readNBytes(8192),ControllerSourceSnapshot.Expected.class);
            var capture=ControllerSourceSnapshot.capture(Path.of(args[0]),expected);byte[] raw=Base64.getDecoder().decode(capture.privateFiles().get(ControllerSourceMaterials.INDEX));
            if(!new String(raw,StandardCharsets.UTF_8).startsWith("{\"files\":"))throw new IllegalStateException("Noncanonical index");
            System.out.println(ControllerSourceSnapshot.sha(raw));
        }
    }
    @TempDir Path root;
    @SuppressWarnings("unchecked") Map<String,String> index(ControllerSourceSnapshot.Captured capture){
        var metadata=JsonMapper.builder().build().readValue(Base64.getDecoder().decode(capture.privateFiles().get(ControllerSourceMaterials.INDEX)),Map.class);return (Map<String,String>)metadata.get("files");
    }
    @Test void explicitCredentialAndMemberFilesAreBoundWithoutReadingUnreferencedFiles()throws Exception{
        var f=new SourceMaterialFixture(root);f.credentials();f.members();f.routes();
        Path unused=f.privateDirectory.resolve("unused.key");SourceMaterialFixture.write(unused,"not-for-export".getBytes(StandardCharsets.US_ASCII));
        f.properties.put("app.control.deployment.identity-file",unused.toString());var capture=f.capture();var names=index(capture);
        for(Path path:List.of(f.credentials,f.key,f.memberConfig,f.password,f.ca,f.routes)){
            String name=names.get(path.toString());assertNotNull(name);assertArrayEquals(Files.readAllBytes(path),Base64.getDecoder().decode(capture.privateFiles().get(name)));
            assertEquals(ControllerSourceSnapshot.sha(Files.readAllBytes(path)),capture.hashes().get(name));
        }
        assertFalse(names.containsKey(unused.toString()));assertFalse(capture.properties().containsKey("spring.flyway.password"));
        assertFalse(capture.hashes().toString().contains(root.toString()));assertFalse(capture.toString().contains("secret"));
    }
    @Test void unchangedLegacySourceKeepsTheOriginalTwoPrivateFiles()throws Exception{
        var capture=new SourceMaterialFixture(root).capture();assertEquals(Set.of("cluster-profiles.json","authority.token"),capture.privateFiles().keySet());
    }
    @Test void actualPrivateFileIndexIsStableAcrossIndependentCollectorProcesses()throws Exception{
        var f=new SourceMaterialFixture(root);f.credentials();f.members();var capture=f.capture();byte[] app=Files.readAllBytes(f.config.resolve("application.properties"));
        var expected=new ControllerSourceSnapshot.Expected(f.cluster,"campus",f.instance,"a".repeat(32),f.state,1,"fixture-source",ControllerSourceSnapshot.sha(app),
                capture.hashes().get("cluster-profiles.json"),ControllerSourceSnapshot.sha(new byte[32]));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(int i=0;i<4;i++){
            var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",classpath,IndexProcess.class.getName(),f.root.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try(var out=p.getOutputStream()){out.write(JsonMapper.builder().build().writeValueAsBytes(expected));}
            assertTrue(p.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue());assertEquals(capture.hashes().get(ControllerSourceMaterials.INDEX),new String(p.getInputStream().readAllBytes(),StandardCharsets.US_ASCII).strip());
        }assertArrayEquals(app,Files.readAllBytes(f.config.resolve("application.properties")));
    }
    @Test void v2MemberTransportIsCapturedExactlyAndUnknownOrCaLessRoutesAreRefused()throws Exception{
        var f=new SourceMaterialFixture(root);f.terminatedMembers();var captured=f.capture();String name=index(captured).get(f.memberConfig.toString());
        assertArrayEquals(Files.readAllBytes(f.memberConfig),Base64.getDecoder().decode(captured.privateFiles().get(name)));
        String good=Files.readString(f.memberConfig);
        for(String bad:List.of(good.replace("TLS_TERMINATED","disable"),good.replace("\"version\":2","\"version\":1"),good.replace(f.ca.toString(),""))){
            SourceMaterialFixture.write(f.memberConfig,bad.getBytes(StandardCharsets.UTF_8));assertThrows(Exception.class,f::capture);
        }
    }
    @Test void symlinksAndLooseModesAreRefusedWithoutRepairingThem()throws Exception{
        var f=new SourceMaterialFixture(root);f.credentials();byte[] key=Files.readAllBytes(f.key);Files.setPosixFilePermissions(f.key,PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(Exception.class,f::capture);assertEquals(PosixFilePermissions.fromString("rw-r-----"),Files.getPosixFilePermissions(f.key));
        Files.delete(f.key);Path other=f.privateDirectory.resolve("other.key");SourceMaterialFixture.write(other,key);Files.createSymbolicLink(f.key,other);
        assertThrows(Exception.class,f::capture);assertTrue(Files.isSymbolicLink(f.key));assertArrayEquals(key,Files.readAllBytes(other));
    }
    @Test void credentialSchemaAndKeyKindCannotBeUsedForAnArbitraryFileDump()throws Exception{
        var f=new SourceMaterialFixture(root);f.credentials();SourceMaterialFixture.write(f.key,"not-a-private-key".getBytes(StandardCharsets.US_ASCII));assertThrows(Exception.class,f::capture);
        f.credentials();SourceMaterialFixture.write(f.credentials,"{\"version\":1,\"version\":1,\"credentials\":{}}".getBytes(StandardCharsets.US_ASCII));assertThrows(Exception.class,f::capture);
    }
    @Test void privateLeafChangesAreInTheSnapshotEvenWithoutAnApplicationChange()throws Exception{
        var f=new SourceMaterialFixture(root);f.members();var first=f.capture();SourceMaterialFixture.write(f.password,"different-password\n".getBytes(StandardCharsets.US_ASCII));var second=f.capture();
        assertEquals(first.hashes().get("application.properties"),second.hashes().get("application.properties"));assertNotEquals(first,second);
        String name=index(first).get(f.password.toString());assertNotEquals(first.hashes().get(name),second.hashes().get(name));
    }
    @Test void jdbcCertificateReferencesAreDecodedAndPkcs12SuffixIsPreserved()throws Exception{
        var f=new SourceMaterialFixture(root);Path key=f.privateDirectory.resolve("client key.p12");SourceMaterialFixture.write(key,new byte[]{0,1,2,3,4});
        String encoded=URLEncoder.encode(key.toString(),StandardCharsets.UTF_8).replace("+","%20");f.properties.put("spring.datasource.url","jdbc:postgresql://db.internal/eatwhat?sslmode=verify-full&sslkey="+encoded);
        var capture=f.capture();String name=index(capture).get(key.toString());assertTrue(name.endsWith(".p12"));assertArrayEquals(new byte[]{0,1,2,3,4},Base64.getDecoder().decode(capture.privateFiles().get(name)));
        assertThrows(Exception.class,()->ControllerSourceMaterials.jdbcFiles("jdbc:postgresql://host/db?sslkey="+encoded+"&sslkey="+encoded));
    }
    @Test void oversizeInventoryIsRefusedRatherThanTruncatedOrPartiallyExported()throws Exception{
        var f=new SourceMaterialFixture(root);f.credentials();var entries=new LinkedHashMap<String,Object>();
        for(int i=0;i<4;i++){
            Path key=f.privateDirectory.resolve("key-"+i+".pem");SourceMaterialFixture.write(key,("-----BEGIN PRIVATE KEY-----\n"+"x".repeat(14000)+"\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII));
            entries.put("key"+i,Map.of("label","Key "+i,"authentication","PRIVATE_KEY","file",key.toString()));
        }SourceMaterialFixture.write(f.credentials,f.json.writeValueAsBytes(Map.of("version",1,"credentials",entries)));assertThrows(Exception.class,f::capture);assertEquals(4,entries.size());
    }
    @Test void tokenAndExternalFileWithSameBasenameDoNotOverwriteEachOther()throws Exception{
        var f=new SourceMaterialFixture(root);f.members();Path outside=f.privateDirectory.resolve("authority.token");SourceMaterialFixture.write(outside,"ca-content".getBytes(StandardCharsets.US_ASCII));
        f.properties.put("app.control.node-ca-file",outside.toString());var capture=f.capture();String name=index(capture).get(outside.toString());assertNotEquals("authority.token",name);
        assertEquals("t".repeat(43),new String(Base64.getDecoder().decode(capture.privateFiles().get("authority.token")),StandardCharsets.US_ASCII));
    }
    @Test void explicitPublicTlsCertificatesAndReadOnlyPrivateKeysKeepTheirSourceModes()throws Exception{
        var f=new SourceMaterialFixture(root);Path ca=f.privateDirectory.resolve("public-ca.pem"),key=f.privateDirectory.resolve("client.p12");
        SourceMaterialFixture.write(ca,"certificate-bytes".getBytes(StandardCharsets.US_ASCII));SourceMaterialFixture.write(key,new byte[]{0,1,2});
        Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-r--r--"));Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("r--------"));
        f.properties.put("spring.datasource.url","jdbc:postgresql://db.internal/eatwhat?sslmode=verify-full&sslrootcert="+URLEncoder.encode(ca.toString(),StandardCharsets.UTF_8)+"&sslkey="+URLEncoder.encode(key.toString(),StandardCharsets.UTF_8));
        var capture=f.capture();assertNotNull(index(capture).get(ca.toString()));assertNotNull(index(capture).get(key.toString()));
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"),Files.getPosixFilePermissions(ca));assertEquals(PosixFilePermissions.fromString("r--------"),Files.getPosixFilePermissions(key));
        Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-rw-r--"));assertThrows(Exception.class,f::capture);
    }
}

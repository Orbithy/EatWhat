package you.v50to.eatwhat.control.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import you.v50to.eatwhat.support.SourceMaterialFixture;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Private disposable files; no production credentials or SQL effects. */
class AuthorityAuthRoleSourceTest {
    @TempDir Path root;
    SourceMaterialFixture fixture()throws Exception{
        root=root.toRealPath();var f=new SourceMaterialFixture(Files.createDirectory(root.resolve("control")));
        f.properties.put("spring.datasource.url","jdbc:postgresql://127.0.0.1:25432/eatwhat");
        f.properties.put("spring.datasource.username","fixture_runtime");Files.createDirectory(root.resolve("sites"));return f;
    }
    ControllerSourceSnapshot.Expected expected(SourceMaterialFixture f)throws Exception{
        f.capture();return new ControllerSourceSnapshot.Expected(f.cluster,"campus",f.instance,"a".repeat(32),f.state,1,"fixture-source",
                ControllerSourceSnapshot.sha(Files.readAllBytes(f.config.resolve("application.properties"))),
                ControllerSourceSnapshot.sha(Files.readAllBytes(f.config.resolve("cluster-profiles.json"))),ControllerSourceSnapshot.sha(new byte[32]));
    }
    AuthorityAuthRoleSource.Material discover(SourceMaterialFixture f)throws Exception{return AuthorityAuthRoleSource.discover(f.root,root.resolve("sites"),expected(f));}
    void noSourceMigrator(SourceMaterialFixture f){f.properties.remove("spring.flyway.user");f.properties.remove("spring.flyway.password");}
    Path business(SourceMaterialFixture f,Map<String,String> changes)throws Exception{
        Path directory=Files.createDirectory(root.resolve("sites").resolve(UUID.randomUUID().toString()));Path config=Files.createDirectory(directory.resolve("config"));
        var p=new TreeMap<>(f.properties);p.put("spring.flyway.user","business_migrator");p.put("spring.flyway.password","business-migration-private");
        p.put("spring.datasource.url","jdbc:postgresql://postgres:5432/eatwhat");p.putAll(changes);
        Path file=config.resolve("application.properties");writeProperties(file,p);return file;
    }
    static void writeProperties(Path file,Map<String,String> p)throws Exception{
        var text=new StringBuilder();p.forEach((k,v)->text.append(k).append('=').append(v).append('\n'));
        SourceMaterialFixture.write(file,text.toString().getBytes(StandardCharsets.US_ASCII));
    }
    @Test void own_migrator_takes_precedence_and_public_review_does_not_export_secrets()throws Exception{
        var f=fixture();business(f,Map.of());var material=discover(f);
        assertEquals("SOURCE_CONFIG",material.kind());assertEquals("migrator",material.username());assertEquals(1,material.sourceFiles().size());
        assertEquals(f.properties.get("spring.flyway.password"),material.password());
        assertEquals("AuthorityAuthSourceMaterial[redacted]",material.toString());String review=JsonMapper.builder().build().writeValueAsString(material.review());
        for(String secret:List.of(material.password(),material.username(),f.root.toString(),material.jdbcUrl()))assertFalse(review.contains(secret));
        assertEquals(material.binding(),material.review().get("credentialBinding"));
        var roundTrip=JsonMapper.builder().build().readValue(JsonMapper.builder().build().writeValueAsBytes(material),AuthorityAuthRoleSource.Material.class);
        assertEquals(material.binding(),roundTrip.binding());
    }
    @Test void absent_or_partial_migrator_never_falls_back_to_runtime_account()throws Exception{
        var f=fixture();noSourceMigrator(f);assertThrows(Exception.class,()->discover(f));
        f.properties.put("spring.flyway.user","migrator");business(f,Map.of());assertThrows(Exception.class,()->discover(f));
        f.properties.put("spring.flyway.password","private");f.properties.put("spring.flyway.user","fixture_runtime");assertThrows(Exception.class,()->discover(f));
    }
    @Test void exactly_one_matching_authority_business_uses_source_loopback_endpoint()throws Exception{
        var f=fixture();noSourceMigrator(f);business(f,Map.of("app.site.instance-id",UUID.randomUUID().toString()));Path original=business(f,Map.of());
        var first=discover(f);assertEquals("AUTHORITY_BUSINESS_CONFIG",first.kind());assertEquals("business_migrator",first.username());
        assertEquals(f.properties.get("spring.datasource.url"),first.jdbcUrl());assertEquals(2,first.sourceFiles().size());
        assertTrue(first.sourceFiles().containsKey(original.toString()));business(f,Map.of());assertThrows(Exception.class,()->discover(f));
    }
    @Test void matched_business_runtime_credentials_must_equal_the_actual_source()throws Exception{
        var f=fixture();noSourceMigrator(f);business(f,Map.of("spring.datasource.password","other-runtime-secret"));assertThrows(Exception.class,()->discover(f));
    }
    @Test void source_and_business_changes_invalidate_the_original_bindings()throws Exception{
        var f=fixture();noSourceMigrator(f);Path file=business(f,Map.of());var e=expected(f);var first=AuthorityAuthRoleSource.discover(f.root,root.resolve("sites"),e);
        var p=new TreeMap<>(ControllerSourceSnapshot.parseProperties(Files.readAllBytes(file)));p.put("spring.flyway.password","changed-business-private");writeProperties(file,p);
        var changed=AuthorityAuthRoleSource.discover(f.root,root.resolve("sites"),e);assertNotEquals(first.binding(),changed.binding());
        f.properties.put("spring.datasource.password","changed-source-private");f.capture();assertThrows(Exception.class,()->AuthorityAuthRoleSource.discover(f.root,root.resolve("sites"),e));
    }
    @Test void symlinks_and_loose_migration_file_permissions_are_refused_without_repair()throws Exception{
        var f=fixture();noSourceMigrator(f);Path file=business(f,Map.of());var e=expected(f);
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(Exception.class,()->AuthorityAuthRoleSource.discover(f.root,root.resolve("sites"),e));
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"),Files.getPosixFilePermissions(file));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));Path renamed=file.resolveSibling("original");Files.move(file,renamed);Files.createSymbolicLink(file,renamed);
        assertThrows(Exception.class,()->AuthorityAuthRoleSource.discover(f.root,root.resolve("sites"),e));assertTrue(Files.isSymbolicLink(file));
    }
    @Test void scan_is_bounded_and_non_uuid_directories_are_not_credential_sources()throws Exception{
        var f=fixture();noSourceMigrator(f);Files.createDirectory(root.resolve("sites/unrelated"));
        for(int i=0;i<65;i++)Files.createDirectory(root.resolve("sites").resolve(UUID.randomUUID().toString()));
        assertThrows(Exception.class,()->discover(f));
    }
    @Test void migrator_cannot_redirect_database_or_inject_driver_options()throws Exception{
        var f=fixture();String base="jdbc:postgresql://127.0.0.1:25432/eatwhat";
        for(String url:List.of("jdbc:postgresql://192.0.2.1:25432/eatwhat",base.replace("eatwhat","other_db"),base+"?socketFactory=custom.Factory",
                base+"?user=postgres",base+"?sslmode=disable&sslmode=require",base+"?socketTimeout=0",base+"?connectTimeout=11",base+"#fragment")){
            f.properties.put("spring.flyway.url",url);assertThrows(Exception.class,()->discover(f),url);
        }
        f.properties.put("spring.flyway.url",base+"?connectTimeout=3&socketTimeout=5");assertEquals(f.properties.get("spring.flyway.url"),discover(f).jdbcUrl());
    }
    @Test void explicit_host_migrator_tls_cannot_downgrade_source_transport()throws Exception{
        var f=fixture();f.properties.put("spring.datasource.url",f.properties.get("spring.datasource.url")+"?sslmode=verify-full");
        f.properties.put("spring.flyway.url","jdbc:postgresql://127.0.0.1:25432/eatwhat?sslmode=require");assertThrows(Exception.class,()->discover(f));
        f.properties.put("spring.flyway.url",f.properties.get("spring.datasource.url"));assertTrue(discover(f).jdbcUrl().endsWith("verify-full"));
    }
    @Test void tls_key_privacy_is_bound_by_option_even_for_pkcs12_filename()throws Exception{
        var f=fixture();Path key=f.privateDirectory.resolve("migrator.p12"),ca=f.privateDirectory.resolve("migrator-ca.pem");
        SourceMaterialFixture.write(key,new byte[]{1,2,3});SourceMaterialFixture.write(ca,"certificate".getBytes(StandardCharsets.US_ASCII));
        Files.setPosixFilePermissions(ca,PosixFilePermissions.fromString("rw-r--r--"));
        f.properties.put("spring.flyway.url",f.properties.get("spring.datasource.url")+"?sslmode=verify-full&sslrootcert="+URLEncoder.encode(ca.toString(),StandardCharsets.UTF_8)+"&sslkey="+URLEncoder.encode(key.toString(),StandardCharsets.UTF_8));
        var first=discover(f);assertEquals(Set.of(key.toString()),first.privateTlsFiles());assertEquals(2,first.tlsFiles().size());
        SourceMaterialFixture.write(key,new byte[]{4,5,6});assertNotEquals(first.binding(),discover(f).binding());
        Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(Exception.class,()->discover(f));
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"),Files.getPosixFilePermissions(key));
    }
    @Test void actual_socket_factory_rejects_nonloopback_before_connecting()throws Exception{
        var factory=new AuthorityAuthRoleSource.LoopbackSocketFactory();
        for(String host:List.of("192.0.2.1","0.0.0.0","::"))try(var socket=factory.createSocket()){
            var failure=assertThrows(IOException.class,()->socket.connect(new InetSocketAddress(InetAddress.getByName(host),5432),50));
            assertEquals("AUTHORITY_AUTH_LOOPBACK_REQUIRED",failure.getMessage());assertFalse(socket.isConnected());
        }
        assertThrows(IOException.class,()->factory.createSocket("127.0.0.1",5432,InetAddress.getByName("192.0.2.1"),0));
        try(var socket=factory.createSocket()){assertThrows(IOException.class,()->socket.connect(InetSocketAddress.createUnresolved("localhost",5432),50));}
        try(var server=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var socket=factory.createSocket("127.0.0.1",server.getLocalPort());var accepted=server.accept()){
            assertTrue(socket.isConnected());assertTrue(accepted.getInetAddress().isLoopbackAddress());
        }
    }
}

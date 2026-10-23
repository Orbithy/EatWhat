package you.v50to.eatwhat.control.bootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControllerSourceSnapshotTest {
    @TempDir Path root;
    static final JsonMapper JSON=JsonMapper.builder().build();
    ControllerSourceSnapshot.Expected expected;
    Map<String,String> values;
    byte[] app,profiles,key=new byte[32];
    void write(Path path,byte[] raw)throws Exception{
        Files.write(path,raw);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
    }
    void fixture()throws Exception{
        root=root.toRealPath();
        UUID cluster=UUID.randomUUID(),instance=UUID.randomUUID(),state=UUID.randomUUID();
        Files.createDirectory(root.resolve("state"));Files.setPosixFilePermissions(root.resolve("state"),PosixFilePermissions.fromString("rwx------"));
        Files.createDirectory(root.resolve("config"));
        values=new TreeMap<>(Map.ofEntries(Map.entry("app.site.cluster-id",cluster.toString()),Map.entry("app.site.id","campus"),
            Map.entry("app.site.instance-id",instance.toString()),Map.entry("app.site.mode","CAMPUS"),Map.entry("app.site.allocation-slot","0"),
            Map.entry("app.control.enabled","true"),Map.entry("app.control.node-enabled","true"),Map.entry("app.control.shared-state-required","true"),
            Map.entry("app.control.environment-kind","managed-deployment"),Map.entry("app.control.state-directory",root.resolve("state").toString()),
            Map.entry("app.cluster.profiles-file",root.resolve("config/cluster-profiles.json").toString()),Map.entry("app.control.node-token-file",root.resolve("config/authority.token").toString()),
            Map.entry("app.cluster.enabled","true"),Map.entry("app.cluster.coordinator","true"),Map.entry("app.cluster.single-site-only","false"),
            Map.entry("app.cluster.control-lease.enabled","true"),Map.entry("app.cluster.control-lease.controller-name","campus-control"),
            Map.entry("spring.flyway.user","migrator"),Map.entry("spring.flyway.password","private-migration-secret"),Map.entry("spring.flyway.enabled","true"),
            Map.entry("spring.datasource.password","runtime-private-secret")));
        var text=new StringBuilder();values.forEach((k,v)->text.append(k).append('=').append(v).append('\n'));app=text.toString().getBytes(StandardCharsets.US_ASCII);
        profiles=JSON.writeValueAsBytes(Map.of("campus",Map.of("siteId","campus","instanceId",instance.toString(),"mode","CAMPUS","allocationSlot",0,"tokenFile",values.get("app.control.node-token-file"))));
        write(root.resolve("config/application.properties"),app);write(root.resolve("config/cluster-profiles.json"),profiles);
        write(root.resolve("config/authority.token"),"t".repeat(43).getBytes(StandardCharsets.US_ASCII));write(root.resolve("state/servers.key"),key);
        write(root.resolve("state/controller-state-id"),state.toString().getBytes(StandardCharsets.US_ASCII));
        expected=new ControllerSourceSnapshot.Expected(cluster,"campus",instance,"a".repeat(32),state,2,"campus-control",
                ControllerSourceSnapshot.sha(app),ControllerSourceSnapshot.sha(profiles),ControllerSourceSnapshot.sha(key));
    }
    Map<String,String> parse(String text)throws Exception{return ControllerSourceSnapshot.parseProperties(text.getBytes(StandardCharsets.ISO_8859_1));}
    ControllerSourceSnapshot.Authority authority(){return new ControllerSourceSnapshot.Authority(expected.clusterId(),expected.authoritySiteId(),expected.authorityInstanceId(),expected.authorityDatabaseFingerprint(),expected.stateId(),expected.epoch(),expected.sharedKeySha256());}
    @Test void discoveryDerivesActualManagedFileHashesAndNameWithoutExportingSecrets()throws Exception{
        fixture();var discovered=ControllerSourceSnapshot.discover(root,authority());assertEquals(expected,discovered);
        assertFalse(JSON.writeValueAsString(discovered).contains("secret"));
        byte[] changed=(new String(app,StandardCharsets.US_ASCII)+"app.jwt.key=another-private-secret\n").getBytes(StandardCharsets.US_ASCII);
        write(root.resolve("config/application.properties"),changed);var fresh=ControllerSourceSnapshot.discover(root,authority());
        assertEquals(ControllerSourceSnapshot.sha(changed),fresh.applicationSha256());assertEquals(expected.profilesSha256(),fresh.profilesSha256());
    }
    @Test void discoveryCannotReplaceTheManagersAuthorityOrSharedIdentity()throws Exception{
        fixture();var a=authority();
        for(var wrong:List.of(new ControllerSourceSnapshot.Authority(UUID.randomUUID(),a.authoritySiteId(),a.authorityInstanceId(),a.authorityDatabaseFingerprint(),a.stateId(),a.epoch(),a.sharedKeySha256()),
                new ControllerSourceSnapshot.Authority(a.clusterId(),a.authoritySiteId(),a.authorityInstanceId(),a.authorityDatabaseFingerprint(),UUID.randomUUID(),a.epoch(),a.sharedKeySha256()),
                new ControllerSourceSnapshot.Authority(a.clusterId(),a.authoritySiteId(),a.authorityInstanceId(),a.authorityDatabaseFingerprint(),a.stateId(),a.epoch(),"f".repeat(64))))
            assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.discover(root,wrong));
    }
    @Test void discoveryStillRejectsUnsafeFilesAndAlternateEffectiveConfiguration()throws Exception{
        fixture();Path path=root.resolve("config/application.properties");Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.discover(root,authority()));
        write(path,(new String(app,StandardCharsets.US_ASCII)+"spring.config.import=file:/unreviewed\n").getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.discover(root,authority()));
    }
    @Test void actualPropertyEscapesContinuationUnicodeAndDelimiters()throws Exception{
        var p=parse("# comment\napp.label=\\u9910\\u7897\\uD83C\\uDF5C\napp.token : leading\\ space\\:x\\=y\\\\z\napp.long=first\\\n   second\n");
        assertEquals("餐碗🍜",p.get("app.label"));assertEquals("leading space:x=y\\z",p.get("app.token"));assertEquals("firstsecond",p.get("app.long"));
    }
    @Test void duplicateDecodedKeyAndRelaxedAliasesAreRefused(){
        for(String text:List.of("app.key=a\napp.\\u006bey=b\n","app.control.enabled=true\napp.control-enabled=false\n"))
            assertThrows(IllegalStateException.class,()->parse(text));
    }
    @Test void catalogReleaseBracketsPreserveIdentityInTheActualSpringBinder()throws Exception{
        var values=parse("app.control.releases[Business_A].artifact=/target/business.jar\napp.control.releases[Business_A].sha256="+"a".repeat(64)+"\n"
                +"app.control.releases[rollback-a].artifact=/target/older.jar\napp.control.releases[rollbacka].artifact=/target/another.jar\n");
        var binder=new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values));
        var control=binder.bind("app.control",org.springframework.boot.context.properties.bind.Bindable.of(you.v50to.eatwhat.control.ControlProperties.class)).get();
        assertEquals(Set.of("Business_A","rollback-a","rollbacka"),control.getReleases().keySet());assertEquals(Path.of("/target/business.jar"),control.getReleases().get("Business_A").getArtifact());
        assertEquals(Path.of("/target/older.jar"),control.getReleases().get("rollback-a").getArtifact());assertEquals(Path.of("/target/another.jar"),control.getReleases().get("rollbacka").getArtifact());
        assertThrows(IllegalStateException.class,()->parse("app.control.releases[initial].sha256=a\napp.control.releases.initial.sha256=b\n"));
    }
    @Test void dynamicSourcesPlaceholdersAndMultipleDocumentsAreRefused(){
        for(String text:List.of("spring.config.import=file:/secrets\n","spring.profiles.active=unsafe\n","app.key=${ENV}\n",
                "app.key=a\n#---\napp.other=b\n","app.key=a\n!---\napp.other=b\n"))assertThrows(IllegalStateException.class,()->parse(text));
    }
    @Test void malformedUnicodeAndNoncanonicalKeysAreRefused(){
        for(String text:List.of("app.key=\\uXXXX\n","APP_KEY=x\n","app.key=\\u0000\n"))assertThrows(Exception.class,()->parse(text));
    }
    @Test void captureReturnsRuntimeSecretsPrivatelyButNeverMigrationCredentials()throws Exception{
        fixture();var capture=ControllerSourceSnapshot.capture(root,expected);
        assertEquals("runtime-private-secret",capture.properties().get("spring.datasource.password"));
        assertFalse(capture.properties().containsKey("spring.flyway.password"));assertFalse(capture.properties().containsKey("spring.flyway.user"));
        assertEquals("true",capture.properties().get("spring.flyway.enabled"));
        assertEquals(Set.of("cluster-profiles.json","authority.token"),capture.privateFiles().keySet());
        assertEquals("CapturedControllerSource[redacted]",capture.toString());assertFalse(capture.toString().contains("secret"));
        assertEquals(ControllerSourceSnapshot.sha("t".repeat(43).getBytes(StandardCharsets.US_ASCII)),capture.hashes().get("authority.token"));
    }
    @Test void changedConfigStateKeyAndProfilesInvalidateOriginalSnapshot()throws Exception{
        fixture();write(root.resolve("config/application.properties"),(new String(app,StandardCharsets.US_ASCII)+"app.changed=yes\n").getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.capture(root,expected));
        write(root.resolve("config/application.properties"),app);write(root.resolve("state/servers.key"),new byte[31]);
        assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.capture(root,expected));
        write(root.resolve("state/servers.key"),key);write(root.resolve("config/cluster-profiles.json"),"{}".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.capture(root,expected));
    }
    @Test void firstPhysicalAuthorityMayOwnLocalStateWithoutRequiringNfs()throws Exception{
        fixture();String changed=new String(app,StandardCharsets.US_ASCII).replace("app.control.shared-state-required=true","app.control.shared-state-required=false");
        byte[] raw=changed.getBytes(StandardCharsets.US_ASCII);write(root.resolve("config/application.properties"),raw);
        var e=new ControllerSourceSnapshot.Expected(expected.clusterId(),expected.authoritySiteId(),expected.authorityInstanceId(),expected.authorityDatabaseFingerprint(),
                expected.stateId(),expected.epoch(),expected.controllerName(),ControllerSourceSnapshot.sha(raw),expected.profilesSha256(),expected.sharedKeySha256());
        assertEquals("false",ControllerSourceSnapshot.capture(root,e).properties().get("app.control.shared-state-required"));
    }
    @Test void unsafeFileModeAndSymlinkAreRejected()throws Exception{
        fixture();Path token=root.resolve("config/authority.token");Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.capture(root,expected));
        Files.delete(token);Files.createSymbolicLink(token,root.resolve("state/servers.key"));
        assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.capture(root,expected));
    }
    @Test void foreignTokenPathIsRefusedEvenIfProfilesHashApproved()throws Exception{
        fixture();String changed=new String(profiles,StandardCharsets.US_ASCII).replace(values.get("app.control.node-token-file"),"/etc/shadow");
        byte[] raw=changed.getBytes(StandardCharsets.US_ASCII);write(root.resolve("config/cluster-profiles.json"),raw);
        var e=new ControllerSourceSnapshot.Expected(expected.clusterId(),expected.authoritySiteId(),expected.authorityInstanceId(),expected.authorityDatabaseFingerprint(),
                expected.stateId(),expected.epoch(),expected.controllerName(),expected.applicationSha256(),ControllerSourceSnapshot.sha(raw),expected.sharedKeySha256());
        assertThrows(IllegalStateException.class,()->ControllerSourceSnapshot.capture(root,e));
    }
}

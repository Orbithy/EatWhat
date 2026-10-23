package you.v50to.eatwhat.cluster;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClusterProfilesTest {
    @TempDir Path directory;
    Path token()throws Exception{var path=directory.resolve(UUID.randomUUID()+".token");Files.writeString(path,"synthetic-node-token-"+"x".repeat(48));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));return path;}
    ClusterProperties.Profile profile(String site,int slot,int port)throws Exception{var p=new ClusterProperties.Profile();p.setSiteId(site);p.setInstanceId(UUID.randomUUID());p.setMode(slot==0?"CAMPUS":"EXTERNAL");p.setAllocationSlot(slot);p.setOrigin("http://127.0.0.1:"+port);p.setTokenFile(token());return p;}
    @Test void profilesNeverExposeEndpointsSecretsOrCredentialPaths()throws Exception{var properties=new ClusterProperties();var p=profile("campus",0,18001);properties.setProfiles(Map.of("primary",p));var profiles=new ClusterProfiles(properties);String output=JsonMapper.builder().build().writeValueAsString(profiles.list());assertFalse(output.contains(p.getOrigin()));assertFalse(output.contains(p.getTokenFile().toString()));assertFalse(output.contains(Files.readString(p.getTokenFile())));assertTrue(output.contains("campus"));}
    @Test void hotReloadCannotReplaceRetainedIdentityOrAliasOrigins()throws Exception{var properties=new ClusterProperties();var p=profile("campus",0,18001);properties.setProfiles(Map.of("primary",p));var profiles=new ClusterProfiles(properties);var original=p.member();p.setInstanceId(UUID.randomUUID());assertEquals("RETAINED_PROFILE_IDENTITY_CHANGED",assertThrows(ClusterFailure.class,()->profiles.reload(List.of(original))).getMessage());assertEquals(original,profiles.get("primary").member());var second=profile("external",1,18001);properties.setProfiles(Map.of("primary",p,"second",second));assertEquals("DUPLICATE_CLUSTER_PROFILE",assertThrows(ClusterFailure.class,()->profiles.reload(List.of())).getMessage());}
    @Test void nonPrivateOrSymlinkCredentialFileIsRejected()throws Exception{var properties=new ClusterProperties();var p=profile("campus",0,18001);properties.setProfiles(Map.of("primary",p));Files.setPosixFilePermissions(p.getTokenFile(),PosixFilePermissions.fromString("rw-r--r--"));assertThrows(you.v50to.eatwhat.control.ControlFailure.class,()->new ClusterProfiles(properties));Files.setPosixFilePermissions(p.getTokenFile(),PosixFilePermissions.fromString("rw-------"));var link=directory.resolve("linked.token");Files.createSymbolicLink(link,p.getTokenFile());p.setTokenFile(link);assertThrows(you.v50to.eatwhat.control.ControlFailure.class,()->new ClusterProfiles(properties));}
    @Test void sessionTokenDiagnosticsAreRedactedAndMalformedTokensFail(){String token="x".repeat(32);assertFalse(new ClusterSessions.Token(token).toString().contains(token));assertThrows(ClusterFailure.class,()->new ClusterSessions.Token("short"));assertThrows(ClusterFailure.class,()->new ClusterSessions.Token("x".repeat(300)));}
    @Test void privateInventoryReloadAcceptsAnApiPrefixWithoutPublishingItOrLosingRetainedIdentity()throws Exception{
        var properties=new ClusterProperties();var p=profile("campus",0,18001);p.setApiPrefix("/api/");
        Path inventory=directory.resolve("profiles.json");Files.writeString(inventory,JsonMapper.builder().build().writeValueAsString(Map.of("primary",p)));Files.setPosixFilePermissions(inventory,PosixFilePermissions.fromString("rw-------"));properties.setProfilesFile(inventory);
        var profiles=new ClusterProfiles(properties);assertEquals("http://127.0.0.1:18001/api",profiles.get("primary").baseUri().toString());assertFalse(profiles.list().toString().contains("/api"));
        p.setApiPrefix("/backend/v1");Files.writeString(inventory,JsonMapper.builder().build().writeValueAsString(Map.of("primary",p)));profiles.reload(List.of(p.member()));assertEquals("/backend/v1",profiles.get("primary").apiPrefix());
        p.setApiPrefix("/api/../admin");Files.writeString(inventory,JsonMapper.builder().build().writeValueAsString(Map.of("primary",p)));assertThrows(you.v50to.eatwhat.control.ControlFailure.class,()->profiles.reload(List.of(p.member())));assertEquals("/backend/v1",profiles.get("primary").apiPrefix());
    }
}

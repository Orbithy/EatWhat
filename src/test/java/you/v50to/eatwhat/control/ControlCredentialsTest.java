package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ControlCredentialsTest {
 @TempDir Path root;
 ControlProperties settings=new ControlProperties();
 @BeforeEach void canonicalRoot()throws Exception{root=root.toRealPath();}
 Path inventory(Map<String,Object> entries)throws Exception{
  Path f=root.resolve("credentials.json");Files.writeString(f,JsonMapper.builder().build().writeValueAsString(Map.of("version",1,"credentials",entries)));Files.setPosixFilePermissions(f,PosixFilePermissions.fromString("rw-------"));settings.getServers().setCredentialsFile(f.toRealPath());return f;
 }
 Map<String,Object> entry(Path key){return new LinkedHashMap<>(Map.of("label","Fixture key","authentication","PRIVATE_KEY","file",key.toString()));}
 Path key()throws Exception{Path f=root.resolve("private.key");Files.writeString(f,"-----BEGIN OPENSSH PRIVATE KEY-----\nsynthetic-only\n-----END OPENSSH PRIVATE KEY-----\n");Files.setPosixFilePermissions(f,PosixFilePermissions.fromString("rw-------"));return f;}
 @Test void metadataReadsOnlyInventoryAndNeverRequiresOrDisclosesKeyFile()throws Exception{
  inventory(Map.of("fixture",entry(root.resolve("absent.key"))));var catalog=new ControlCredentials(settings);
  assertEquals(List.of(Map.of("id","fixture","label","Fixture key","authentication","PRIVATE_KEY")),catalog.metadata());
  assertEquals("SSH_CREDENTIAL_REFERENCE_UNAVAILABLE",assertThrows(ControlFailure.class,()->catalog.resolve("fixture","PRIVATE_KEY")).code);
 }
 @Test void keyIsReadOnExplicitUseOnlyWithPrivatePermissionsAndMatchingAuthentication()throws Exception{
  Path key=key();inventory(Map.of("fixture",entry(key)));var catalog=new ControlCredentials(settings);
  assertEquals(Files.readString(key),catalog.resolve("fixture","PRIVATE_KEY"));
  assertEquals("INVALID_SSH_CREDENTIAL_REFERENCE",assertThrows(ControlFailure.class,()->catalog.resolve("fixture","PASSWORD")).code);
  for(String id:List.of("unknown","/etc/passwd","../fixture",""))assertThrows(ControlFailure.class,()->catalog.resolve(id,"PRIVATE_KEY"));
  Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(ControlFailure.class,()->catalog.resolve("fixture","PRIVATE_KEY"));
 }
 @Test void unsafeInventoryAndPathsCannotBeSelected()throws Exception{
  Path key=key();var item=entry(key);Path f=inventory(Map.of("fixture",item));Files.setPosixFilePermissions(f,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(ControlFailure.class,()->new ControlCredentials(settings));
  inventory(Map.of("fixture",item));Path link=root.resolve("alias.json");Files.createSymbolicLink(link,f);settings.getServers().setCredentialsFile(link);assertThrows(ControlFailure.class,()->new ControlCredentials(settings));
  Path keyLink=root.resolve("key-link");Files.createSymbolicLink(keyLink,key);inventory(Map.of("fixture",entry(keyLink)));assertThrows(ControlFailure.class,()->new ControlCredentials(settings));
  item.put("file","relative.key");inventory(Map.of("fixture",item));assertThrows(ControlFailure.class,()->new ControlCredentials(settings));
  item=entry(key);item.put("credential","forbidden-inline");inventory(Map.of("fixture",item));assertThrows(ControlFailure.class,()->new ControlCredentials(settings));
  inventory(Map.of("../fixture",entry(key)));assertThrows(ControlFailure.class,()->new ControlCredentials(settings));
  inventory(Map.of("fixture",entry(key)));Files.writeString(f,"{\"version\":1,\"version\":1,\"credentials\":{}}");assertThrows(ControlFailure.class,()->new ControlCredentials(settings));
 }
 @Test void replacementSymlinkIsRejectedOnUseAndUnconfiguredCatalogIsEmpty()throws Exception{
  assertTrue(new ControlCredentials(settings).metadata().isEmpty());Path key=key();inventory(Map.of("fixture",entry(key)));var catalog=new ControlCredentials(settings);Path moved=root.resolve("moved.key");Files.move(key,moved);Files.createSymbolicLink(key,moved);assertThrows(ControlFailure.class,()->catalog.resolve("fixture","PRIVATE_KEY"));
 }
}

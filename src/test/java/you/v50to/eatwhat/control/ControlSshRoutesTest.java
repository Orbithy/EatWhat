package you.v50to.eatwhat.control;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlSshRoutesTest {
 @TempDir Path root;
 ControlProperties settings=new ControlProperties();UUID id=UUID.randomUUID();
 String key(){byte[] type="ssh-ed25519".getBytes();return "ssh-ed25519 "+Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(4+type.length+4+32).putInt(type.length).put(type).putInt(32).put(new byte[32]).array());}
 Map<String,Object> target(){return new LinkedHashMap<>(Map.of("id",id.toString(),"host","127.0.0.1","port",22,"hostKey",key()));}
 void inventory(Object routes)throws Exception{Path file=root.resolve("routes.json");Files.writeString(file,JsonMapper.builder().build().writeValueAsString(Map.of("version",1,"routes",routes)));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));settings.getServers().setSshRoutesFile(file.toRealPath());}
 Map<String,Object> route(){return new LinkedHashMap<>(Map.of("host","10.253.74.1","port",22,"logicalHost","127.0.0.1","logicalPort",22,"hostKey",key()));}
 @Test void remoteControllerMustExplicitlyBindEveryLoopbackEntryButOriginalControllerRemainsCompatible(){
  assertEquals("127.0.0.1",new ControlSshRoutes(settings).resolve(target()).host());settings.setSharedStateRequired(true);
  var routes=new ControlSshRoutes(settings);assertEquals("SSH_LOOPBACK_ROUTE_REQUIRED",assertThrows(ControlFailure.class,()->routes.resolve(target())).code);
  var remote=target();remote.put("host","server.example.invalid");assertEquals("server.example.invalid",routes.resolve(remote).host());
 }
 @Test void exactUuidLogicalAddressPortAndOriginalPinAreRequiredAndLoadedOnce()throws Exception{
  inventory(Map.of(id.toString(),route()));settings.setSharedStateRequired(true);var routes=new ControlSshRoutes(settings);var target=target();
  assertEquals(new ControlSshRoutes.Endpoint("10.253.74.1",22,true),routes.resolve(target));
  for(String field:List.of("host","port","hostKey")){
   var changed=new LinkedHashMap<>(target);changed.put(field,field.equals("port")?2222:field.equals("host")?"localhost":key().replace("AAAAA", "AAAAB"));
   assertThrows(ControlFailure.class,()->routes.resolve(changed));
  }
  var changed=new LinkedHashMap<>(target);changed.put("id",UUID.randomUUID().toString());assertThrows(ControlFailure.class,()->routes.resolve(changed));
  inventory(Map.of());assertEquals("10.253.74.1",routes.resolve(target).host());assertThrows(ControlFailure.class,()->new ControlSshRoutes(settings).resolve(target));
 }
 @Test void publicSymlinkUnknownFieldsAndNonloopbackLogicalOverridesRefuseAtStartup()throws Exception{
  inventory(Map.of(id.toString(),route()));Files.setPosixFilePermissions(settings.getServers().getSshRoutesFile(),PosixFilePermissions.fromString("rw-r--r--"));assertThrows(ControlFailure.class,()->new ControlSshRoutes(settings));
  inventory(Map.of(id.toString(),route()));Path link=root.resolve("link");Files.createSymbolicLink(link,settings.getServers().getSshRoutesFile());settings.getServers().setSshRoutesFile(link);assertThrows(ControlFailure.class,()->new ControlSshRoutes(settings));
  var route=route();route.put("username","different-user");inventory(Map.of(id.toString(),route));assertThrows(ControlFailure.class,()->new ControlSshRoutes(settings));
  route=route();route.put("logicalHost","remote.example.invalid");inventory(Map.of(id.toString(),route));assertThrows(ControlFailure.class,()->new ControlSshRoutes(settings));
 }
 @Test void opaqueBindingChangesForPhysicalRouteWithoutExposingEndpoint()throws Exception{
  root=root.toRealPath();var store=new ControlServerStore(new ControlAudit(root));
  var server=target();server.put("username","ubuntu");server.put("privilege","SUDO");
  inventory(Map.of(id.toString(),route()));String old=new ControlSsh(store,settings).routeBinding(server);
  var different=route();different.put("host","10.253.74.5");inventory(Map.of(id.toString(),different));
  String changed=new ControlSsh(store,settings).routeBinding(server);assertNotEquals(old,changed);
  assertFalse(old.contains("10.253.74.1"));assertNotEquals(old,new ControlSsh(store,new ControlProperties()).routeBinding(server));
 }
}

package you.v50to.eatwhat.control;

import java.nio.file.*;
import java.util.*;

/** Explicit real-host read-only probe, loaded separately from the runtime JAR. */
public final class SharedControllerRouteProbe {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException();
  var p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
  var settings=new ControlProperties();settings.setStateDirectory(Path.of(p.getProperty("app.control.state-directory")));
  settings.setSharedStateRequired(Boolean.parseBoolean(p.getProperty("app.control.shared-state-required","false")));
  settings.getServers().setSshRoutesFile(Path.of(p.getProperty("app.control.servers.ssh-routes-file")));
  var audit=new ControlAudit(settings.getStateDirectory(),settings.isSharedStateRequired());var store=new ControlServerStore(audit);
  var server=store.server(UUID.fromString(args[1]));var endpoint=new ControlSshRoutes(settings).resolve(server);
  if(!endpoint.routed()||!endpoint.host().equals("10.253.74.1")||endpoint.port()!=22)throw new IllegalStateException();
  var actual=new ControlSsh(store,settings).call(server,ControlServers.resource("server-probe.py"),List.of(),out->{},45);
  if(!"eatwhat-server-probe-v1".equals(actual.get("protocol"))||!Boolean.TRUE.equals(actual.get("root")))throw new IllegalStateException();
  System.out.println(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
      "status","PASS_REAL_ORIGINAL_REGISTERED_SSH","serverId",args[1],"logicalHost",server.get("host"),
      "physicalHost",endpoint.host(),"pinnedOriginalKey",true,"root",actual.get("root"),"memoryMiB",actual.get("memoryMiB"))));
 }
}

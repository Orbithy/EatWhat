package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.*;

/** Explicit physical routing for a shared logical registry. Never changes credentials, user, or host key. */
final class ControlSshRoutes {
    record Endpoint(String host,int port,boolean routed) {}
    private record Route(String host,int port,String logicalHost,int logicalPort,String hostKey) {}
    private final Map<UUID,Route> routes;
    private final boolean remoteState;
    ControlSshRoutes(ControlProperties settings){
        remoteState=settings.isSharedStateRequired()||settings.getIndependent().isEnabled();Path file=settings.getServers().getSshRoutesFile();
        if(file==null){routes=Map.of();return;}
        try{
            var json=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            var root=json.readTree(ControlMemberConfiguration.privateBytes(file,65536));
            if(!root.isObject()||!Set.copyOf(root.propertyNames()).equals(Set.of("version","routes"))
                    ||!root.path("version").isIntegralNumber()||root.path("version").asInt()!=1
                    ||!root.path("routes").isObject()||root.path("routes").size()>64)throw new Exception();
            var parsed=new HashMap<UUID,Route>();
            for(String id:root.path("routes").propertyNames()){
                UUID uuid=UUID.fromString(id);if(!uuid.toString().equals(id))throw new Exception();var r=root.path("routes").path(id);
                if(!r.isObject()||!Set.copyOf(r.propertyNames()).equals(Set.of("host","port","logicalHost","logicalPort","hostKey")))throw new Exception();
                for(String key:List.of("host","logicalHost","hostKey"))if(!r.path(key).isString())throw new Exception();
                for(String key:List.of("port","logicalPort"))if(!r.path(key).isIntegralNumber()||!r.path(key).canConvertToInt())throw new Exception();
                String host=ControlSsh.host(r.path("host").asText()),logical=ControlSsh.host(r.path("logicalHost").asText());
                int port=r.path("port").asInt(),logicalPort=r.path("logicalPort").asInt();ControlSsh.port(port);ControlSsh.port(logicalPort);
                if(!loopback(logical))throw new Exception();
                parsed.put(uuid,new Route(host,port,logical,logicalPort,ControlSsh.key(r.path("hostKey").asText()).get("publicKey")));
            }
            routes=Map.copyOf(parsed);
        }catch(Exception invalid){throw new ControlFailure(503,"PRIVATE_SSH_ROUTES_INVALID");}
    }
    Endpoint resolve(Map<String,Object> server){
        String host=ControlSsh.host(server.get("host").toString());int port=((Number)server.get("port")).intValue();ControlSsh.port(port);
        Route route=null;
        try{if(server.containsKey("id"))route=routes.get(UUID.fromString(server.get("id").toString()));}
        catch(Exception invalid){throw new ControlFailure(409,"SSH_ROUTE_BINDING_CHANGED");}
        if(route==null){if(remoteState&&loopback(host))throw new ControlFailure(409,"SSH_LOOPBACK_ROUTE_REQUIRED");return new Endpoint(host,port,false);}
        if(!host.equals(route.logicalHost())||port!=route.logicalPort()||!ControlSsh.key(server.get("hostKey").toString()).get("publicKey").equals(route.hostKey()))
            throw new ControlFailure(409,"SSH_ROUTE_BINDING_CHANGED");
        return new Endpoint(route.host(),route.port(),true);
    }
    private static boolean loopback(String host){return Set.of("127.0.0.1","localhost","::1").contains(host);}
}

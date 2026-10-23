package you.v50to.eatwhat.control;

import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Real pinned-SSH initial inventory, strictly before any native effect. Later
 * phase observations use each sealed native worker, never rerun empty-target
 * preflight after an approved WG/NFS/Docker installation.
 */
public final class ControlBootstrapPreflights {
    private static final String PROTOCOL="eatwhat-controller-bootstrap-preflight-v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ControlServerStore store;private final ControlServers servers;private final ControlSsh ssh;
    public ControlBootstrapPreflights(ControlServerStore store,ControlServers servers,ControlSsh ssh){this.store=store;this.servers=servers;this.ssh=ssh;}
    public record Captured(UUID serverId,long revision,String routeBinding,Map<String,Object> request,Map<String,Object> result) {
        @Override public String toString(){return "BootstrapPreflight[redacted]";}
    }
    private static void require(boolean ok,String code){if(!ok)throw new ControlFailure(409,code);}
    public Captured capture(UUID serverId,long revision,Map<String,Object> request){
        return collect(serverId,revision,request,true);
    }
    private void authorization(boolean accepting){if(accepting)servers.requireWrite();else servers.requireExecuting();}
    private Captured collect(UUID serverId,long revision,Map<String,Object> request,boolean accepting){
        authorization(accepting);var server=store.server(serverId);ControlServers.expected(server,revision);
        require(ControlSsh.rootExecution(server),"BOOTSTRAP_LINUX_ROOT_REQUIRED");
        require(request!=null&&Set.of("SOURCE","TARGET").contains(request.get("role"))
                &&serverId.toString().equals(request.get("SOURCE".equals(request.get("role"))?"sourceServerId":"targetServerId")),"BOOTSTRAP_INVALID_BINDING");
        String route=ssh.routeBinding(server);
        try {
            byte[] raw=JSON.writeValueAsBytes(request);require(raw.length<=8192,"BOOTSTRAP_INVALID_INPUT");
            var result=ssh.call(server,ControlServers.resource("controller-bootstrap-preflight.py"),List.of("inspect"),out->out.write(raw),45);
            require(PROTOCOL.equals(result.get("protocol"))&&request.get("id").equals(result.get("id"))&&request.get("role").equals(result.get("role"))
                    &&Boolean.TRUE.equals(result.get("ready"))&&Boolean.FALSE.equals(result.get("effectsStarted"))
                    &&result.get("proof") instanceof String proof&&proof.matches("[a-f0-9]{64}")
                    &&result.get("facts") instanceof Map<?,?>&&result.get("plannedEffects") instanceof Map<?,?>,"BOOTSTRAP_INVALID_PREFLIGHT_RESPONSE");
            authorization(accepting);var after=store.server(serverId);ControlServers.expected(after,revision);
            require(route.equals(ssh.routeBinding(after)),"SSH_ROUTE_BINDING_CHANGED");
            return new Captured(serverId,revision,route,Map.copyOf(request),Map.copyOf(result));
        }catch(ControlFailure failure){throw failure;}
        catch(ControlSsh.RemoteRefusal refusal){throw new ControlFailure(409,refusal.code);}
        catch(Exception failure){throw new ControlFailure(503,"BOOTSTRAP_PREFLIGHT_UNAVAILABLE");}
    }
    public void requireInitialUnchanged(Captured captured){
        requireInitialUnchanged(captured,true);
    }
    /** Repeat the same readonly inventory for an already accepted task. Only
     * the admitting phase claim may grant effects; this method grants none. */
    public void requireInitialExecutingUnchanged(Captured captured){
        requireInitialUnchanged(captured,false);
    }
    private void requireInitialUnchanged(Captured captured,boolean accepting){
        var fresh=collect(captured.serverId(),captured.revision(),captured.request(),accepting);
        require(captured.routeBinding().equals(fresh.routeBinding()),"SSH_ROUTE_BINDING_CHANGED");
        require(captured.result().get("proof").equals(fresh.result().get("proof")),"BOOTSTRAP_PLAN_CHANGED");
    }
}

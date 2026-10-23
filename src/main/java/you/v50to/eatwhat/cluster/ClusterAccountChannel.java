package you.v50to.eatwhat.cluster;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.account.transport.AccountProtocol;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.control.*;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Authority fan-out is recipient-scoped; secondary interactive RPC goes only to the fixed authority. */
public final class ClusterAccountChannel implements AccountChannel {
    private final ClusterProperties properties;private final ControlProperties control;private final NodeIdentity local;
    private final ClusterMembershipView membership;private final ClusterRecipientConsent recipients;private final ObjectProvider<ClusterProfiles> profiles;
    private final AccountProtocol protocol=new AccountProtocol();private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient client;
    public ClusterAccountChannel(JdbcTemplate jdbc,ClusterProperties properties,ControlProperties control,SiteProperties site,ObjectProvider<ClusterProfiles> profiles){this.client=you.v50to.eatwhat.config.NodeHttpClients.create(properties.getNodeCaFile());this.properties=properties;this.control=control;this.local=new NodeIdentity(site.getId(),site.getInstanceId());this.membership=new ClusterMembershipView(jdbc);this.recipients=new ClusterRecipientConsent(jdbc);this.profiles=profiles;
        if(!properties.isCoordinator()){authorityBase();new NodeIdentity(properties.getAuthoritySiteId(),properties.getAuthorityInstanceId());}
    }
    @Override public JsonNode call(String operation,JsonNode body){
        if(!properties.isCoordinator())return request(authorityBase(),new NodeIdentity(properties.getAuthoritySiteId(),properties.getAuthorityInstanceId()),ControlAudit.token(control.getNodeTokenFile()),operation,body);
        if(operation.equals("multiwriter-capabilities")||operation.equals("multiwriter-exchange")){
            JsonNode response=json.createObjectNode().put("schemaVersion",2).put("active",true).put("status","APPLIED");
            for(var member:membership.current()){
                if(member.identity().equals(local)||!member.mode().equals("EXTERNAL"))continue;
                if(operation.equals("multiwriter-exchange")){
                    var snapshot=json.treeToValue(body.get("snapshot"),ConsentModels.Snapshot.class);
                    boolean permitted=snapshot.status().equals("WITHDRAWN")?recipients.historicallyAllowed(snapshot.subjectId(),member):recipients.allowed(snapshot.subjectId(),snapshot.epoch(),member);
                    if(!permitted)continue;
                }
                var target=profile(member);response=request(target.baseUri(),member.identity(),ControlAudit.token(target.tokenFile()),operation,body,profileClient(target,client));
                if(operation.equals("multiwriter-capabilities")&&(!response.path("active").asBoolean()||response.path("schemaVersion").asInt()!=2))return response;
                if(operation.equals("multiwriter-exchange")&&!java.util.Set.of("APPLIED","STAGED").contains(response.path("status").asText()))return response;
            }return response;
        }
        if(!operation.equals("project"))throw new AccountUnavailableException();
        var snapshot=json.treeToValue(body,ConsentModels.Snapshot.class);
        for(var member:membership.current()){
            if(member.identity().equals(local)||!member.mode().equals("EXTERNAL"))continue;
            boolean send=snapshot.status().equals("WITHDRAWN")?recipients.historicallyAllowed(snapshot.subjectId(),member):recipients.allowed(snapshot.subjectId(),snapshot.epoch(),member);
            if(!send)continue;var entry=profile(member);
            var result=project(entry,snapshot);if(!Set.of("APPLIED","STALE").contains(result.path("status").asText()))return json.createObjectNode().put("status","RETRYABLE_ERROR");
        }
        return json.createObjectNode().put("status","APPLIED");
    }
    /** Online call to one named member: the authority for a secondary, the member's own profile for the coordinator. */
    public JsonNode callSite(String siteId,String operation,JsonNode body){
        if(!properties.isCoordinator()){
            if(!siteId.equals(properties.getAuthoritySiteId()))throw new AccountUnavailableException();
            return call(operation,body);
        }
        var member=membership.current().stream().filter(m->m.siteId().equals(siteId)&&!m.identity().equals(local)).findFirst().orElseThrow(AccountUnavailableException::new);
        var target=profile(member);
        return request(target.baseUri(),member.identity(),ControlAudit.token(target.tokenFile()),operation,body,profileClient(target,client));
    }
    ClusterProfiles.Entry profile(ClusterMember member){return profiles.getObject().list().stream().filter(p->p.get("siteId").equals(member.siteId())).map(p->profiles.getObject().get((String)p.get("id"))).filter(p->p.member().equals(member)).findFirst().orElseThrow(AccountUnavailableException::new);}
    /** The deployed profile API predates scoped trust. Only an absent method uses its existing client. */
    static HttpClient profileClient(Object profile,HttpClient fallback){
        try{Object selected=profile.getClass().getMethod("client",HttpClient.class).invoke(profile,fallback);if(selected instanceof HttpClient client)return client;throw new AccountUnavailableException();}
        catch(NoSuchMethodException legacyProfile){return fallback;}
        catch(ReflectiveOperationException|SecurityException invalidTrust){throw new AccountUnavailableException();}
    }
    private URI authorityBase(){return ControlService.nodeBase(properties.getAuthorityOrigin(),properties.getAuthorityApiPrefix());}
    JsonNode project(ClusterProfiles.Entry target,ConsentModels.Snapshot snapshot){
        try{return request(target.baseUri(),target.member().identity(),ControlAudit.token(target.tokenFile()),"project",json.valueToTree(snapshot),profileClient(target,client));}
        catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new AccountUnavailableException();}
    }
    JsonNode multiwriter(ClusterProfiles.Entry target,JsonNode batch){return request(target.baseUri(),target.member().identity(),ControlAudit.token(target.tokenFile()),"multiwriter-exchange",batch,profileClient(target,client));}
    private JsonNode request(URI origin,NodeIdentity receiver,String token,String operation,JsonNode body){
        return request(origin,receiver,token,operation,body,client);
    }
    private JsonNode request(URI origin,NodeIdentity receiver,String token,String operation,JsonNode body,HttpClient client){
        var frame=new AccountProtocol.Frame(1,UUID.randomUUID(),local,receiver,Instant.now(),operation,body);
        try{
            var request=HttpRequest.newBuilder(URI.create(origin+"/internal/cluster/v1/account")).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").header("X-Control-Node-Token",token).POST(HttpRequest.BodyPublishers.ofByteArray(protocol.encode(frame))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());try(var input=response.body()){byte[] bytes=input.readNBytes(AccountProtocol.MAX_BODY_BYTES+1);if(bytes.length>AccountProtocol.MAX_BODY_BYTES)throw new AccountUnavailableException();if(response.statusCode()!=200){
                if(operation.startsWith("session-")){String code=json.readTree(bytes).path("code").asText();if(Set.of("CLUSTER_SESSION_INVALID","CLUSTER_SESSION_REVOKED","SESSION_RECIPIENT_CONSENT_REQUIRED","SESSION_ACCOUNT_SYNC_PENDING").contains(code))throw new ClusterFailure(response.statusCode(),code);}
                throw new AccountUnavailableException();}var reply=protocol.decode(bytes);if(!reply.isReplyTo(frame))throw new AccountUnavailableException();return reply.body();}
        }catch(ClusterFailure failure){throw failure;}catch(Exception failure){throw new AccountUnavailableException();}
    }
}

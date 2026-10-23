package you.v50to.eatwhat.cluster;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import you.v50to.eatwhat.account.*;
import you.v50to.eatwhat.account.transport.*;
import you.v50to.eatwhat.control.*;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.transport.NodeIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@RestController
@ConditionalOnProperty(name={"app.cluster.enabled","app.account.enabled"},havingValue="true")
public class ClusterAccountController {
    private final ClusterProperties properties;private final ObjectProvider<ClusterProfiles> profiles;private final ClusterMembershipView membership;
    private final ControlNode authentication;private final AccountNodeDispatcher dispatcher;private final NodeIdentity local;
    private final AccountProtocol protocol=new AccountProtocol();private final AccountReplayWindow replay=new AccountReplayWindow();
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private ObjectProvider<ClusterSessions> sessions;
    public ClusterAccountController(ClusterProperties properties,ObjectProvider<ClusterProfiles> profiles,org.springframework.jdbc.core.JdbcTemplate jdbc,ControlNode authentication,AccountNodeDispatcher dispatcher,SiteProperties site){this.properties=properties;this.profiles=profiles;this.jdbc=jdbc;this.membership=new ClusterMembershipView(jdbc);this.authentication=authentication;this.dispatcher=dispatcher;local=new NodeIdentity(site.getId(),site.getInstanceId());}
    @PostMapping(value="/internal/cluster/v1/account",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> call(@RequestHeader(value="X-Control-Node-Token",required=false) String token,jakarta.servlet.http.HttpServletRequest request)throws java.io.IOException {
        byte[] bytes=request.getInputStream().readNBytes(AccountProtocol.MAX_BODY_BYTES+1);
        var frame=protocol.decode(bytes);if(!frame.receiver().equals(local))throw new ClusterFailure(403,"ACCOUNT_RECEIVER_MISMATCH");
        if(properties.isCoordinator()){
            if(!membership.current(frame.sender()))throw new ClusterFailure(403,"ACCOUNT_SENDER_NOT_ACTIVE");
            var entry=profiles.getObject().list().stream().filter(p->p.get("siteId").equals(frame.sender().siteId())).map(p->profiles.getObject().get((String)p.get("id"))).findFirst().orElseThrow(()->new ClusterFailure(403,"ACCOUNT_SENDER_NOT_ACTIVE"));
            if(!entry.member().identity().equals(frame.sender())||token==null||!MessageDigest.isEqual(ControlAudit.token(entry.tokenFile()).getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw new ClusterFailure(401,"NODE_AUTH_REQUIRED");
        }else{
            authentication.authenticate(token);
            if(!frame.sender().equals(new NodeIdentity(properties.getAuthoritySiteId(),properties.getAuthorityInstanceId())))throw new ClusterFailure(403,"ACCOUNT_AUTHORITY_MISMATCH");
            if(jdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.local_state WHERE singleton AND state='RETIRED'",Long.class)>0)throw new ClusterFailure(403,"ACCOUNT_TARGET_RETIRED");
        }
        if(replay.reserve(frame.requestId(),frame.issuedAt(),Instant.now(),System.nanoTime())!=AccountReplayWindow.Reservation.ACCEPTED)throw new ClusterFailure(409,"ACCOUNT_REQUEST_REPLAY_OR_STALE");
        if(!properties.isCoordinator()&&membership.current().stream().noneMatch(m->m.identity().equals(local))&&frame.operation().startsWith("session-"))throw new ClusterFailure(403,"SESSION_TARGET_NOT_ACTIVE");
        var response=frame.operation().startsWith("session-")?sessions.getObject().handle(frame.sender(),frame.operation(),frame.body()):AccountPeerScope.run(frame.sender(),()->(frame.operation().equals("project")||frame.operation().equals("multiwriter-exchange"))?ClusterBarrier.internal(()->dispatcher.handle(frame.operation(),frame.body())):dispatcher.handle(frame.operation(),frame.body()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(protocol.encode(frame.reply(response)));
    }
}

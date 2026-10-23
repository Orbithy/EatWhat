package you.v50to.eatwhat.cluster;

import tools.jackson.databind.JsonNode;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.service.ChallengePeers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Each challenge (SMS code or e-mail token or send interval) lives on exactly one active member, chosen by a stable hash of the sorted site ids. */
public final class ClusterChallengePeers implements ChallengePeers {
    private final SiteProperties site;private final ClusterMembershipView membership;private final ClusterAccountChannel channel;
    public ClusterChallengePeers(SiteProperties site,ClusterMembershipView membership,ClusterAccountChannel channel){this.site=site;this.membership=membership;this.channel=channel;}
    @Override public String ownerOf(String routingKey){
        var sites=new TreeSet<String>();sites.add(site.getId());for(var member:membership.current())sites.add(member.siteId());
        if(sites.size()<2)return site.getId();
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((routingKey).getBytes(StandardCharsets.UTF_8));
            return List.copyOf(sites).get((int)Long.remainderUnsigned(ByteBuffer.wrap(digest).getLong(),sites.size()));
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @Override public JsonNode call(String siteId,String operation,JsonNode body){return channel.callSite(siteId,operation,body);}
}

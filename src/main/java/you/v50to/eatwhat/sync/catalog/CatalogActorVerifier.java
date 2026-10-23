package you.v50to.eatwhat.sync.catalog;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Account-domain adapter. Evidence is verified against immutable local security facts, never trusted as a role claim. */
public interface CatalogActorVerifier {
    record Evidence(UUID actorGid, Map<String,Object> authorization,java.util.List<UUID> decisionHeads) {
        public Evidence(UUID actorGid,Map<String,Object> authorization){this(actorGid,authorization,java.util.List.of());}
        public Evidence { Objects.requireNonNull(actorGid); authorization=Map.copyOf(authorization);
            decisionHeads=decisionHeads==null?java.util.List.of():java.util.List.copyOf(decisionHeads);
            if(decisionHeads.size()>128||new java.util.HashSet<>(decisionHeads).size()!=decisionHeads.size())throw new IllegalArgumentException("Invalid decision context"); }
    }
    enum Assessment { AUTHORIZED, HISTORICAL_VALID, CONCURRENT_REVOCATION_REVIEW, KNOWN_REVOKED, DEPENDENCY_PENDING }
    Evidence capture(long actorUserId);
    Assessment assess(Evidence evidence);
}

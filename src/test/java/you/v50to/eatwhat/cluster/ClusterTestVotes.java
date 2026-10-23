package you.v50to.eatwhat.cluster;

import java.util.UUID;

/** Tests exercise coordinator-only votes without making a public voting API. */
public final class ClusterTestVotes {
    public static void membership(ClusterStore store,UUID change,ClusterMember member,String phase){store.vote(change,member,phase,"a".repeat(64));}
    public static void gc(ClusterGcStore store,UUID operation,ClusterMember member,String phase){store.vote(operation,member,phase);}
}

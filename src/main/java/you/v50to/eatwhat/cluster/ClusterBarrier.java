package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.function.Supplier;

/** Membership preparation and ordinary writes serialize on the local row until the business transaction commits. */
public final class ClusterBarrier {
    private static final ThreadLocal<Integer> INTERNAL=ThreadLocal.withInitial(()->0);
    private final JdbcTemplate jdbc;
    private Boolean schema;
    private Boolean bound;
    public ClusterBarrier(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void requireWritable(){
        if(INTERNAL.get()>0)return;
        if(schema==null)schema=Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('eatwhat_cluster.local_state') IS NOT NULL",Boolean.class));
        if(!schema)return;
        // Allocation is immutable and installed by Flyway before this runtime starts. Ordinary dual-site
        // deployments must not pay an extra database round trip for every existing domain transaction.
        if(bound==null)bound=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_identity.allocation WHERE singleton)",Boolean.class));
        if(!bound)return;
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new ClusterFailure(409,"CLUSTER_TRANSACTION_REQUIRED");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT eatwhat_cluster.writes_allowed()",Boolean.class)))throw new ClusterFailure(423,"CLUSTER_WRITE_BARRIER");
    }
    /** Trusted in-process protocol code only. No HTTP field, header or SQL setting can enable this scope. */
    static <T> T internal(Supplier<T> work){int previous=INTERNAL.get();INTERNAL.set(previous+1);try{return work.get();}finally{if(previous==0)INTERNAL.remove();else INTERNAL.set(previous);}}
}

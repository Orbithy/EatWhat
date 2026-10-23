package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.sync.SyncTransactions;
import java.util.function.Supplier;

final class ClusterTransactions {
    private final SyncTransactions tx;
    private final ClusterControlLease lease;
    private final JdbcTemplate authorityJdbc;
    ClusterTransactions(SyncTransactions tx){this(tx,null,null);}
    ClusterTransactions(SyncTransactions tx,ClusterControlLease lease,JdbcTemplate authorityJdbc){this.tx=tx;this.lease=lease;this.authorityJdbc=authorityJdbc;if(lease!=null)lease.requireSameDataSource(authorityJdbc);}
    <T> T required(Supplier<T> work){return ClusterBarrier.internal(()->tx.required(()->{
        if(lease!=null)lease.requireHeldInTransaction();
        else if(authorityJdbc!=null&&authorityJdbc.queryForObject("SELECT count(*) FROM eatwhat_cluster.control_lease WHERE singleton",Long.class)>0)
            throw new ClusterFailure(503,"CONTROL_LEASE_REQUIRED");
        return work.get();
    }));}
    void requireSameDataSource(JdbcTemplate jdbc){tx.requireSameDataSource(jdbc);}
}

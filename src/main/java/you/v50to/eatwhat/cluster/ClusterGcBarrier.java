package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** Persisted opt-in survives a disabled scheduler: an installation can never fall back to bilateral/local deletion. */
public final class ClusterGcBarrier {
    private ClusterGcBarrier(){}
    public static boolean managed(JdbcTemplate jdbc){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.local_state WHERE singleton)",Boolean.class));}
    public static boolean required(JdbcTemplate jdbc,UUID file,int revision){return managed(jdbc)&&!privateLocal(jdbc,file,revision);}
    /** An own canonical upload that has never published metadata cannot have an authorized remote holder. */
    public static boolean privateLocal(JdbcTemplate jdbc,UUID file,int revision){return managed(jdbc)&&Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM eatwhat_file.file_object o JOIN eatwhat_file.upload_intent i USING(logical_key)
          JOIN eatwhat_identity.entity_identity e ON e.entity_type='users' AND e.numeric_id=i.owner_id
          JOIN eatwhat_identity.allocation a ON a.singleton AND a.site_id=e.origin_site
          WHERE o.file_id=? AND o.canonical_revision=? AND i.status='COMPLETE' AND i.owner_gid=o.owner_gid)
        AND NOT EXISTS(SELECT 1 FROM eatwhat_file.file_sync_dirty WHERE file_id=? AND revision=? AND metadata_epoch>=1)
        AND NOT EXISTS(SELECT 1 FROM eatwhat_sync.outbox WHERE aggregate_type='file.metadata'
          AND envelope->'payloadJson'->'file'->>'fileId'=? AND (envelope->'payloadJson'->'file'->>'revision')::integer=?)
        AND NOT EXISTS(SELECT 1 FROM eatwhat_file.file_replica WHERE file_id=? AND revision=? AND site<>'LOCAL')
        AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.gc_fence WHERE file_id=? AND revision=?)
        """,Boolean.class,file,revision,file,revision,file.toString(),revision,file,revision,file,revision));}
    public static boolean committed(JdbcTemplate jdbc,UUID file,int revision){return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM eatwhat_cluster.gc_fence f JOIN eatwhat_cluster.local_state s
          ON s.singleton AND s.epoch=f.epoch AND s.state='ACTIVE' AND s.pending_change IS NULL
          WHERE f.file_id=? AND f.revision=? AND f.state IN ('COMMITTED','DELETED'))
        """,Boolean.class,file,revision));}
}

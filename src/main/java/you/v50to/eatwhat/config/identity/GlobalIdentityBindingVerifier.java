package you.v50to.eatwhat.config.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import you.v50to.eatwhat.config.SiteProperties;

/** Runs even when Flyway is disabled: an activated database can never be silently downgraded. */
public final class GlobalIdentityBindingVerifier implements ApplicationRunner, Ordered {
    private final SiteProperties site;
    private final JdbcTemplate jdbc;
    private final Boolean clusterEnabled;
    private final boolean enrollmentPending;

    public GlobalIdentityBindingVerifier(SiteProperties site, JdbcTemplate jdbc) {
        this.site = site;
        this.jdbc = jdbc;
        this.clusterEnabled=null;
        this.enrollmentPending=false;
    }
    public GlobalIdentityBindingVerifier(SiteProperties site,JdbcTemplate jdbc,boolean clusterEnabled){this(site,jdbc,clusterEnabled,false);}
    public GlobalIdentityBindingVerifier(SiteProperties site,JdbcTemplate jdbc,boolean clusterEnabled,boolean enrollmentPending){this.site=site;this.jdbc=jdbc;this.clusterEnabled=clusterEnabled;this.enrollmentPending=enrollmentPending;}

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments args) {
        if(clusterEnabled!=null&&site.getClusterId()!=null&&!clusterEnabled){
            if(!enrollmentPending||!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS(SELECT 1 FROM eatwhat_cluster.local_state)
                   AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.local_change)
                   AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.state)
                   AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.member)
                   AND NOT EXISTS(SELECT 1 FROM eatwhat_cluster.change)
                """,Boolean.class)))throw new IllegalStateException("A bound multi-site installation cannot disable its cluster runtime");
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            boolean exists;
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                           WHERE n.nspname='eatwhat_identity' AND c.relname='node_state' AND c.relkind='r')
                         """)) {
                rows.next();
                exists = rows.getBoolean(1);
            }
            if (!exists) {
                if (site.getMode() != SiteProperties.Mode.LEGACY) {
                    throw new IllegalStateException("Explicit site requires an activated global identity schema");
                }
                return null;
            }
            var state = GlobalIdentityMigrationCallback.readState(connection);
            if (state == null) {
                if (site.getMode() != SiteProperties.Mode.LEGACY) {
                    throw new IllegalStateException("Explicit site global identity activation is incomplete");
                }
            } else {
                GlobalIdentityMigrationCallback.requireMatchingBinding(site, state);
                GlobalIdentityMigrationCallback.verifyAllocationBinding(connection,site);
            }
            return null;
        });
    }
}

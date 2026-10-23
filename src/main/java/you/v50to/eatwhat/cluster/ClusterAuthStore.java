package you.v50to.eatwhat.cluster;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import you.v50to.eatwhat.config.SiteProperties;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URI;
import java.util.UUID;
import java.util.function.Supplier;

/** A separate, HA PostgreSQL endpoint holding the authority's committed auth state. */
public final class ClusterAuthStore implements AutoCloseable {
    private final HikariDataSource source;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UUID clusterId;
    private final String authoritySite;
    private final UUID authorityInstance;

    public ClusterAuthStore(ClusterProperties settings,SiteProperties site){
        var config=settings.getAuthStore();
        if(settings.isCoordinator()||!config.isEnabled())throw new ClusterFailure(503,"AUTH_STORE_SECONDARY_ONLY");
        String url=config.getJdbcUrl(),username=config.getUsername();Path file=config.getPasswordFile();
        if(url==null||!url.startsWith("jdbc:postgresql://")||url.contains("@")||url.toLowerCase().contains("password=")
                ||username==null||!username.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||file==null)
            throw new ClusterFailure(503,"PRIVATE_AUTH_STORE_CONFIG_REQUIRED");
        try{
            URI target=URI.create(url.substring(5));String host=target.getHost();
            String query=target.getRawQuery()==null?"":target.getRawQuery();
            long verified=java.util.Arrays.stream(query.split("&")).filter("sslmode=verify-full"::equals).count();
            long sslModes=java.util.Arrays.stream(query.split("&")).filter(part->part.startsWith("sslmode=")).count();
            if(host==null||target.getUserInfo()!=null||target.getFragment()!=null||target.getPath()==null||target.getPath().length()<2
                    ||(!host.equals("127.0.0.1")&&!host.equals("::1")&&!host.equals("localhost")&&(verified!=1||sslModes!=1)))throw new IllegalArgumentException();
            if(!file.isAbsolute()||Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>4096
                    ||!Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")))throw new IllegalArgumentException();
            var hikari=new HikariConfig();hikari.setJdbcUrl(url);hikari.setUsername(username);hikari.setPassword(Files.readString(file).strip());
            hikari.setMaximumPoolSize(4);hikari.setMinimumIdle(0);hikari.setConnectionTimeout(3000);hikari.setValidationTimeout(2000);
            hikari.setInitializationFailTimeout(-1);hikari.setPoolName("cluster-auth-store");
            source=new HikariDataSource(hikari);
        }catch(Exception failure){throw new ClusterFailure(503,"PRIVATE_AUTH_STORE_CONFIG_REQUIRED");}
        jdbc=new JdbcTemplate(source);transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        clusterId=site.getClusterId();authoritySite=settings.getAuthoritySiteId();authorityInstance=settings.getAuthorityInstanceId();
    }
    public JdbcTemplate jdbc(){return jdbc;}
    public <T> T required(Supplier<T> work){return transactions.execute(status->work.get());}
    /** Never treat a readable but unrelated or stale site database as the authentication authority. */
    public void verify(){
        var rows=jdbc.queryForList("SELECT cluster_id,coordinator_site,coordinator_instance FROM eatwhat_cluster.state WHERE singleton");
        if(rows.size()!=1||!clusterId.equals(rows.getFirst().get("cluster_id"))||!authoritySite.equals(rows.getFirst().get("coordinator_site"))
                ||!authorityInstance.equals(rows.getFirst().get("coordinator_instance")))throw new ClusterFailure(503,"AUTH_STORE_BINDING_MISMATCH");
    }
    @Override public void close(){source.close();}
}

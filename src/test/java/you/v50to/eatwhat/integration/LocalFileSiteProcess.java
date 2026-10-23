package you.v50to.eatwhat.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import you.v50to.eatwhat.config.FileReplicationProperties;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.service.storage.replication.*;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

/** Test-classpath protocol node. No business HTTP API, production configuration or scheduler is started. */
public final class LocalFileSiteProcess implements AutoCloseable {
    static final String PREFIX = "EATWHAT_FILE_DRILL=";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FileEventChannelIT.Side side;
    private final LiveStorageDrillSupport.OwnedStore local, peer;
    private final FileGcService gc;
    private final NodeHttpsServer server;
    private final SyncHttpClient client;
    private final CampusSyncWorker network;
    private final FileReplicationWorker copier;
    private final Set<String> owned;
    private final long user;

    private LocalFileSiteProcess(Properties p) throws Exception {
        String url = LiveStorageDrillSupport.required(p, "db.url");
        if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/events_[a-f0-9]+\\?.*")) {
            throw new IllegalArgumentException("Only the harness's ephemeral loopback database is accepted");
        }
        var site = new SiteProperties();
        site.setId(p.getProperty("site.id"));
        site.setInstanceId(UUID.fromString(p.getProperty("site.instance")));
        site.setMode(SiteProperties.Mode.valueOf(p.getProperty("site.mode")));
        var other = new NodeIdentity(p.getProperty("peer.id"), UUID.fromString(p.getProperty("peer.instance")));
        String username = p.getProperty("db.runtime-user");
        var source = new DriverManagerDataSource(url, username, IsolatedContainers.PASSWORD);
        var sql = new JdbcTemplate(source);
        var transactions = new DataSourceTransactionManager(source);
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, IsolatedContainers.USER, IsolatedContainers.PASSWORD));
        var db = new EventFoundationTestSupport.Database("local-drill", url, admin, sql, source, transactions,
                new SyncTransactions(sql, transactions), site, username);
        side = new FileEventChannelIT.Side(db, other);
        user = Long.parseLong(p.getProperty("test.user"));
        owned = Set.copyOf(Files.readAllLines(Path.of(p.getProperty("owned.keys"))));
        var stores = LiveStorageDrillSupport.read(Path.of(p.getProperty("storage.config")));
        LiveStorageDrillSupport.validate(stores, Boolean.parseBoolean(p.getProperty("local.contract")));
        boolean campus = site.getMode() == SiteProperties.Mode.CAMPUS;
        local = new LiveStorageDrillSupport.OwnedStore(LiveStorageDrillSupport.open(stores, campus ? "rustfs" : "r2"), owned);
        peer = campus ? new LiveStorageDrillSupport.OwnedStore(LiveStorageDrillSupport.open(stores, "r2"), owned) : null;
        gc = new FileGcService(sql, transactions, FileCoordinatedGcIT.settings(), site, () -> local);
        gc.setGcProtocol(side.gcProtocol);
        var tls = NodeTls.create(Path.of(p.getProperty("tls.key-store")), TransportTlsFixture.PASSWORD.toCharArray(),
                Path.of(p.getProperty("tls.trust-store")), TransportTlsFixture.PASSWORD.toCharArray(),
                Set.of(p.getProperty("tls.peer-pin")));
        if (campus) {
            client = new SyncHttpClient(URI.create(p.getProperty("peer.origin")), tls, Duration.ofSeconds(5));
            network = new CampusSyncWorker(side.node, other, FileEventChannelIT.delivery(side), side.inbox,
                    new EventCodec(), client, Duration.ofSeconds(5),
                    new RetryPolicy(Duration.ofMillis(20), Duration.ofMillis(100), () -> .5),
                    new TransportMetrics(), side.policy);
            copier = new FileReplicationWorker(new FileTransferJobStore(sql, new FilePublicationService(sql)),
                    new JdbcReplicationConsentGate(sql, side.consent), local, peer, new FileReplicationProperties());
            server = null;
        } else {
            server = new NodeHttpsServer(new InetSocketAddress("127.0.0.1", 0), tls,
                    new NodeEndpoint(side.node, other, side.inbox, FileEventChannelIT.delivery(side),
                            new EventCodec(), Duration.ofSeconds(5), Duration.ofMillis(20), side.policy), Duration.ofSeconds(10));
            server.start();
            client = null; network = null; copier = null;
        }
    }

    private Map<String, Object> command(JsonNode request) {
        String command = request.path("command").asText();
        if (command.equals("publish")) { side.worker.runOnce(); return Map.of(); }
        if (command.equals("drain")) { side.drain(); return Map.of(); }
        if (command.equals("network") && network != null) { network.runOnce(); return Map.of(); }
        if (command.equals("replicate") && copier != null) { return Map.of("processed", copier.runOnce()); }
        if (command.equals("lose-delete-response")) { local.loseNextDeleteResponse = true; return Map.of(); }
        if (command.equals("lose-delete-and-probe")) {
            local.loseNextDeleteResponse = true; local.blockConfirmationOnLostDelete = true; return Map.of();
        }
        if (command.equals("counters")) { return Map.of("local_delete_calls", local.deletes.get()); }
        String key = request.path("key").asText();
        if (!owned.contains(key)) throw new IllegalArgumentException("ACCEPTANCE_KEY_NOT_OWNED");
        UUID file = UUID.fromString(key.substring("verified/".length()));
        String alias = side.sql().queryForObject("SELECT logical_key FROM eatwhat_file.file_object WHERE file_id=?", String.class, file);
        switch (command) {
            case "enqueue" -> {
                if (copier == null) throw new IllegalArgumentException("Only CAMPUS may enqueue byte copies");
                var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
                beans.addBean("consent", side.consent);
                side.db.transactions().independent(() -> {
                    new FileReplicationEnqueuer(side.sql(), beans.getBeanProvider(you.v50to.eatwhat.account.ConsentStore.class))
                            .onRevisionVerified(file, 1, key);
                    return null;
                });
            }
            case "reference" -> side.sql().update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(?,'test','acceptance','PRIVATE')", alias);
            case "release", "restore" -> side.sql().update("INSERT INTO eatwhat_file.file_reference_event(logical_key,ref_kind,ref_id,action,reason) VALUES(?,'test','acceptance',?,'ADMIN')", alias, command.equals("release") ? "RELEASE" : "RESTORE");
            case "avatar" -> side.sql().update("UPDATE users SET avatar=? WHERE id=?", alias, user);
            case "new-reference" -> side.sql().update("INSERT INTO eatwhat_file.file_reference(logical_key,ref_kind,ref_id,visibility) VALUES(?,'test','late','PRIVATE')", alias);
            case "gc" -> {
                side.sql().update("UPDATE eatwhat_file.file_gc_candidate SET eligible_after=NULL WHERE file_id=? AND state IN ('BLOCKED','DELETING')", file);
                var result = gc.runCycle();
                return Map.of("deleted", result.deleted(), "errors", result.errors(), "skipped", result.skipped(), "blocked", result.blockReasons());
            }
            default -> throw new IllegalArgumentException("Unknown drill command");
        }
        return Map.of();
    }

    private static void emit(Map<String, ?> result) {
        System.out.println(PREFIX + JSON.writeValueAsString(result));
        System.out.flush();
    }

    public static void main(String[] args) {
        try (var node = new LocalFileSiteProcess(LiveStorageDrillSupport.read(Path.of(args[0])));
             var input = new BufferedReader(new InputStreamReader(System.in))) {
            emit(Map.of("ok", true, "ready", true, "pid", ProcessHandle.current().pid(),
                    "mode", node.side.db.site().getMode().name(), "port", node.server == null ? 0 : node.server.port()));
            String line;
            while ((line = input.readLine()) != null) {
                try {
                    var result = new LinkedHashMap<String, Object>(node.command(JSON.readTree(line)));
                    result.put("ok", true); emit(result);
                } catch (Exception failure) {
                    String state = "";
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        if (cause instanceof SQLException sql) { state = Objects.toString(sql.getSQLState(), ""); break; }
                    }
                    emit(Map.of("ok", false, "error", failure.getClass().getSimpleName(), "sqlstate", state));
                }
            }
        } catch (Exception failure) {
            emit(Map.of("ok", false, "startup", true, "error", failure.getClass().getSimpleName()));
            System.exit(1);
        }
    }

    @Override public void close() {
        if (client != null) client.close();
        if (server != null) server.close();
        if (peer != null) peer.close();
        local.close();
    }
}

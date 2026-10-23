package you.v50to.eatwhat.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import you.v50to.eatwhat.config.SiteProperties;
import you.v50to.eatwhat.sync.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Child JVM for force-kill tests; credentials and payload are synthetic and test-owned. */
public final class EventCrashWorker {
    private EventCrashWorker() { }

    public static void main(String[] args) throws Exception {
        String action = args[0];
        var dataSource = new DriverManagerDataSource(args[1], args[2], IsolatedContainers.PASSWORD);
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new SyncTransactions(jdbc, new DataSourceTransactionManager(dataSource));
        var site = new SiteProperties();
        site.setMode(SiteProperties.Mode.CAMPUS);
        site.setId(args[3]);
        site.setInstanceId(UUID.fromString(args[4]));
        var codec = new EventCodec();
        SyncEvent event = codec.decode(Files.readString(Path.of(args[5])));
        Path signal = Path.of(args[6]);
        var outbox = new OutboxService(jdbc, transactions, codec, site);
        if (action.equals("outbox-uncommitted")) {
            transactions.required(() -> {
                create(jdbc, outbox, event);
                signalAndBlock(signal); // No commit: parent kills this process while its DB transaction is open.
                return null;
            });
        } else if (action.equals("outbox-committed")) {
            transactions.required(() -> { create(jdbc, outbox, event); return null; });
            signalAndBlock(signal);
        } else if (action.equals("delivery-claimed")) {
            var claims = new DeliveryService(jdbc, transactions, codec).claim("external", 1, Duration.ofSeconds(1));
            if (claims.size() != 1) throw new IllegalStateException("Expected one persisted delivery lease");
            signalAndBlock(signal);
        } else if (action.equals("inbox-applied")) {
            var inbox = new InboxService(jdbc, transactions, codec, new EventHandlerRegistry(List.of(counterHandler(jdbc))));
            inbox.receive(event, event.originSite());
            var leases = inbox.claim(1, Duration.ofSeconds(10));
            if (leases.size() != 1 || inbox.process(leases.getFirst()).status() != InboxService.InboxStatus.APPLIED) {
                throw new IllegalStateException("Expected a committed application");
            }
            signalAndBlock(signal); // Parent models lost ACK by killing before any delivery acknowledgement.
        } else throw new IllegalArgumentException("Unknown crash checkpoint");
    }

    static EventHandler counterHandler(JdbcTemplate jdbc) {
        return new EventHandler() {
            @Override public String aggregateType() { return "test_counter"; }
            @Override public String eventType() { return "counter.created"; }
            @Override public int schemaVersion() { return 1; }
            @Override public boolean dependenciesSatisfied(SyncEvent event) { return event.dependencies().isEmpty(); }
            @Override public void apply(SyncEvent event) {
                jdbc.update("""
                        INSERT INTO public.business_counter(gid,numeric_id,value) VALUES (?,?,1)
                        ON CONFLICT(gid) DO UPDATE SET value=business_counter.value+1
                        """, event.aggregateId(), event.numericId());
            }
        };
    }

    private static void create(JdbcTemplate jdbc, OutboxService outbox, SyncEvent event) {
        jdbc.update("INSERT INTO public.business_counter(gid,numeric_id,value) VALUES (?,?,1)", event.aggregateId(), event.numericId());
        outbox.append(event, List.of("external"));
    }

    private static void signalAndBlock(Path signal) {
        try {
            Files.writeString(signal, "READY");
            new java.util.concurrent.CountDownLatch(1).await();
        } catch (Exception interrupted) {
            throw new IllegalStateException("Crash checkpoint interrupted", interrupted);
        }
    }
}

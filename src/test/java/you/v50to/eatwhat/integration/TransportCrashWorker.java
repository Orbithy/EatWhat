package you.v50to.eatwhat.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import you.v50to.eatwhat.sync.*;
import you.v50to.eatwhat.sync.transport.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Test-only outbound process: real HTTPS and database commits precede each force-kill checkpoint. */
public final class TransportCrashWorker {
    private TransportCrashWorker() { }

    public static void main(String[] args) throws Exception {
        String checkpoint = args[0];
        var source = new DriverManagerDataSource(args[1], args[2], IsolatedContainers.PASSWORD);
        var jdbc = new JdbcTemplate(source);
        var transactions = new SyncTransactions(jdbc, new DataSourceTransactionManager(source));
        var local = new NodeIdentity(args[3], UUID.fromString(args[4]));
        var peer = new NodeIdentity(args[5], UUID.fromString(args[6]));
        var tls = NodeTls.create(Path.of(args[8]), TransportTlsFixture.PASSWORD.toCharArray(),
                Path.of(args[9]), TransportTlsFixture.PASSWORD.toCharArray(), Set.of(args[10]));
        var network = new SyncHttpClient(URI.create(args[7]), tls, Duration.ofSeconds(3));
        Path signal = Path.of(args[11]);
        TransportClient fault = (path, request) -> {
            if (checkpoint.equals("pull-received-before-ack") && path.endsWith("/ack") && !request.acks().isEmpty()) {
                signalAndBlock(signal);
            }
            var response = network.exchange(path, request);
            if ((checkpoint.equals("push-response") && path.endsWith("/push") && !request.events().isEmpty())
                    || (checkpoint.equals("ack-response") && path.endsWith("/ack") && !request.acks().isEmpty())) {
                signalAndBlock(signal);
            }
            return response;
        };
        EventCodec codec = new EventCodec();
        var inbox = new InboxService(jdbc, transactions, codec, new EventHandlerRegistry(List.of(counterHandler(jdbc))));
        var worker = new CampusSyncWorker(local, peer, new DeliveryService(jdbc, transactions, codec), inbox,
                codec, fault, Duration.ofSeconds(2), new RetryPolicy(Duration.ofMillis(20), Duration.ofMillis(100), () -> 0.5),
                new TransportMetrics());
        try {
            worker.runOnce();
            throw new IllegalStateException("Transport worker did not reach the requested test checkpoint");
        } finally {
            network.close();
        }
    }

    static EventHandler counterHandler(JdbcTemplate jdbc) {
        return new EventHandler() {
            @Override public String aggregateType() { return SyntheticEventPolicy.AGGREGATE_TYPE; }
            @Override public String eventType() { return SyntheticEventPolicy.EVENT_TYPE; }
            @Override public int schemaVersion() { return 1; }
            @Override public boolean dependenciesSatisfied(SyncEvent event) { return event.dependencies().isEmpty(); }
            @Override public void apply(SyncEvent event) {
                int delta = JsonMapper.builder().build().readTree(event.payloadJson()).get("delta").intValue();
                jdbc.update("""
                        INSERT INTO public.business_counter(gid,numeric_id,value) VALUES (?,?,?)
                        ON CONFLICT(gid) DO UPDATE SET value=business_counter.value+EXCLUDED.value
                        """, event.aggregateId(), event.numericId(), delta);
            }
        };
    }

    private static void signalAndBlock(Path signal) {
        try {
            Files.writeString(signal, "COMMITTED_CHECKPOINT");
            new CountDownLatch(1).await();
        } catch (Exception failure) {
            throw new IllegalStateException("Synthetic transport checkpoint interrupted");
        }
    }
}

package you.v50to.eatwhat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SiteConfigurationTest {
    private static final String R2_ORIGIN = "https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com";
    private static final String SYNTHETIC_SECRET = "synthetic-secret-must-not-appear-in-errors";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new SiteConfigurationValidator())
            .withUserConfiguration(BindingConfiguration.class);

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SiteProperties.class, S3StorageProperties.class})
    static class BindingConfiguration { }

    @Test
    void absentModePreservesLegacyBindingAndLazyStorageValidation() {
        runner.withPropertyValues("app.storage.s3.endpoint=legacy-value-not-validated-at-startup")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SiteProperties.class).getMode()).isEqualTo(SiteProperties.Mode.LEGACY);
                    assertThat(context.getBean(S3StorageProperties.class).getProvider()).isEqualTo(S3StorageProperties.Provider.GENERIC);
                    assertThat(context.getBean(S3StorageProperties.class).getEndpoint()).isEqualTo("legacy-value-not-validated-at-startup");
                });
    }

    @ParameterizedTest
    @CsvSource({"campus,rustfs", "external,r2"})
    void explicitSitesBindWithoutContactingAnyDependency(String mode, String provider) {
        var values = valid(mode, provider);
        values.put("spring.flyway.url", "jdbc:postgresql://127.0.0.1:1/site?socketTimeout=10");
        configured(values).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SiteProperties.class).getId()).isEqualTo("test-site");
            assertThat(context.getBean(SiteProperties.class).getMode().name()).isEqualToIgnoringCase(mode);
            assertThat(context.getBean(S3StorageProperties.class).getProvider().name()).isEqualToIgnoringCase(provider);
        });
    }

    @ParameterizedTest
    @CsvSource({"campus,r2", "external,rustfs", "legacy,rustfs", "legacy,r2", "campus,generic", "external,generic"})
    void wrongSiteProviderPairsFailBeforeBeansCanOpenConnections(String mode, String provider) {
        AtomicBoolean connectionAttempted = new AtomicBoolean();
        configured(valid(mode, provider)).withBean("connectionProbe", Object.class, () -> {
            connectionAttempted.set(true);
            return new Object();
        }).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.site.mode / app.storage.s3.provider");
            assertThat(connectionAttempted).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.site.mode", "app.storage.s3.provider"})
    void unknownEnumsAreRejected(String key) {
        var values = valid("campus", "rustfs");
        values.put(key, "unrecognized");
        configured(values).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.site.mode", "app.site.instance-id", "app.storage.s3.provider", "app.storage.s3.sign-put-ttl-seconds", "app.storage.s3.path-style"})
    void bindingErrorsDoNotEchoRejectedValuesOrTheirCauseChain(String key) {
        var values = valid("campus", "rustfs");
        values.put(key, SYNTHETIC_SECRET);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(trace(context.getStartupFailure())).contains(key).doesNotContain(SYNTHETIC_SECRET);
        });
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void invalidRequiredSettingsFailWithPropertyNameAndNoValues(String key, String value) {
        var values = valid("campus", "rustfs");
        values.put(key, value);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            String trace = trace(context.getStartupFailure());
            assertThat(trace).contains(key.replace("[0]", "")).doesNotContain(SYNTHETIC_SECRET);
        });
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("app.site.id", ""),
                Arguments.of("app.site.id", "Wrong_ID"),
                Arguments.of("app.site.instance-id", ""),
                Arguments.of("app.site.instance-id", "00000000-0000-0000-0000-000000000000"),
                Arguments.of("spring.flyway.enabled", "false"),
                Arguments.of("spring.datasource.url", "jdbc:mysql://127.0.0.1/site"),
                Arguments.of("spring.datasource.url", ""),
                Arguments.of("spring.flyway.url", "jdbc:postgresql://127.0.0.1:1/different-site"),
                Arguments.of("app.storage.s3.endpoint", ""),
                Arguments.of("app.storage.s3.endpoint", "http://user:" + SYNTHETIC_SECRET + "@127.0.0.1:9000"),
                Arguments.of("app.storage.s3.endpoint", "http://127.0.0.1:9000?secret=" + SYNTHETIC_SECRET),
                Arguments.of("app.storage.s3.endpoint", "http://127.0.0.1:9000/#" + SYNTHETIC_SECRET),
                Arguments.of("app.storage.s3.endpoint", "http://[" + SYNTHETIC_SECRET),
                Arguments.of("app.storage.s3.endpoint", "file:///tmp/storage"),
                Arguments.of("app.storage.s3.endpoint", "http://127.0.0.1:9000/bucket"),
                Arguments.of("app.storage.s3.endpoint", R2_ORIGIN),
                Arguments.of("app.storage.s3.region", ""),
                Arguments.of("app.storage.s3.bucket", ""),
                Arguments.of("app.storage.s3.access-key", ""),
                Arguments.of("app.storage.s3.secret-key", ""),
                Arguments.of("app.storage.s3.secret-key", "${MISSING_SYNTHETIC_SECRET}"),
                Arguments.of("app.storage.s3.sign-get-ttl-seconds", "0"),
                Arguments.of("app.storage.s3.sign-get-ttl-seconds", "604801"),
                Arguments.of("app.storage.s3.sign-put-ttl-seconds", "-1"),
                Arguments.of("app.storage.s3.sign-put-ttl-seconds", "604801"),
                Arguments.of("app.storage.s3.max-file-size-bytes", "0"),
                Arguments.of("app.storage.s3.allowed-content-types[0]", " "),
                Arguments.of("app.storage.s3.allowed-read-prefixes[0]", " "),
                Arguments.of("app.storage.s3.public-read-prefixes[0]", " "),
                Arguments.of("app.storage.s3.public-base-url", "https://user:" + SYNTHETIC_SECRET + "@cdn.example.invalid"),
                Arguments.of("app.storage.s3.public-base-url", "https://cdn.example.invalid/bucket/../other"),
                Arguments.of("app.storage.s3.public-base-url", "https://cdn.example.invalid/bucket/%2e%2e/other"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".eu", ".us", ".fedramp"})
    void r2JurisdictionApiOriginsRemainSupported(String jurisdiction) {
        var values = valid("external", "r2");
        values.put("app.storage.s3.endpoint", "https://0123456789abcdef0123456789abcdef" + jurisdiction + ".r2.cloudflarestorage.com/");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com", "https://cdn.example.invalid",
            "https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com.attacker.invalid",
            "https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com:9000"})
    void r2RequiresItsHttpsApiOrigin(String origin) {
        var values = valid("external", "r2");
        values.put("app.storage.s3.endpoint", origin);
        configured(values).run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.s3.endpoint"));
    }

    @Test
    void r2UsesExplicitProjectRegionConvention() {
        var values = valid("external", "r2");
        values.put("app.storage.s3.region", "us-east-1");
        configured(values).run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.s3.region"));
    }

    @Test
    void publicPrefixesRequireAnExplicitObjectRootAndNeverFallBackToLegacyCdn() {
        var values = valid("external", "r2");
        values.put("app.storage.s3.public-read-prefixes[0]", "avatar/");
        values.put("app.storage.s3.cdn-endpoint", "https://legacy-cdn.example.invalid");
        configured(values).run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.s3.public-base-url"));
        values.put("app.storage.s3.public-base-url", "https://public.example.invalid/optional-bucket/");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void fileReplicationWorkerOnExternalSiteIsRejected() {
        var values = valid("external", "r2");
        values.put("app.storage.files.replication.enabled", "true");
        values.put("app.storage.files.replication.worker-enabled", "true");
        values.put("app.storage.files.replication.peer.endpoint", "http://127.0.0.1:9000");
        values.put("app.storage.files.replication.peer.bucket", "peer");
        values.put("app.storage.files.replication.peer.access-key", "key");
        values.put("app.storage.files.replication.peer.secret-key", SYNTHETIC_SECRET);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.replication.worker-enabled");
            assertThat(context.getStartupFailure()).hasMessageContaining("CAMPUS");
        });
    }

    @Test
    void fileReplicationWorkerRequiresEnabledTrue() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.replication.enabled", "false");
        values.put("app.storage.files.replication.worker-enabled", "true");
        values.put("app.storage.files.replication.peer.endpoint", "http://127.0.0.1:9000");
        values.put("app.storage.files.replication.peer.bucket", "peer");
        values.put("app.storage.files.replication.peer.access-key", "key");
        values.put("app.storage.files.replication.peer.secret-key", SYNTHETIC_SECRET);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("worker-enabled")
                    .hasMessageContaining("enabled=true");
        });
    }

    @Test
    void externalFileChannelAndCoordinatedGcRequireOnlyLocalStorageCredentials() {
        var values=valid("external","r2");
        values.put("app.storage.files.replication.enabled","true");values.put("app.storage.files.replication.worker-enabled","false");
        values.put("app.sync.files.enabled","true");values.put("app.storage.files.gc.enabled","true");
        values.put("app.storage.files.gc.inventory-enabled","true");values.put("app.storage.files.gc.coordinated-enabled","true");
        configured(values).run(context->assertThat(context).hasNotFailed());
    }

    @Test
    void fileReplicationEnabledRequiresPeerEndpoint() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.replication.enabled", "true");
        values.put("app.storage.files.replication.worker-enabled", "false");
        values.put("app.storage.files.replication.peer.bucket", "peer");
        values.put("app.storage.files.replication.peer.access-key", "key");
        values.put("app.storage.files.replication.peer.secret-key", SYNTHETIC_SECRET);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.replication.peer.endpoint");
        });
    }

    @Test
    void fileReplicationValidCampusConfigWithWorkerAccepted() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.replication.enabled", "true");
        values.put("app.storage.files.replication.worker-enabled", "true");
        values.put("app.storage.files.replication.lease-seconds", "120");
        values.put("app.storage.files.replication.batch", "8");
        values.put("app.storage.files.replication.poll-interval", "5000ms");
        values.put("app.storage.files.replication.peer.endpoint", "http://127.0.0.1:9000");
        values.put("app.storage.files.replication.peer.bucket", "peer");
        values.put("app.storage.files.replication.peer.access-key", "key");
        values.put("app.storage.files.replication.peer.secret-key", SYNTHETIC_SECRET);
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void fileReplicationAllDefaultsAcceptedInLegacyMode() {
        var values = valid("legacy", "generic");
        // Don't set any replication properties; use all defaults.
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void fileInventoryDefaultsAreAccepted() {
        configured(valid("legacy", "generic")).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void fileInventoryWithDefaultSafetyWindowIsAcceptedWithoutReplication() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.gc.inventory-enabled", "true");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void fileInventorySafetyWindowBelowFifteenMinutesIsRejected() {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.gc.inventory-enabled", "true");
        values.put("app.storage.files.gc.safety-window", "10m");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.safety-window")
                    .hasMessageContaining("15 minutes");
        });
    }

    @Test
    void fileInventorySafetyWindowBelowIntentTtlIsRejected() {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.gc.inventory-enabled", "true");
        values.put("app.storage.files.gc.safety-window", "20m");
        values.put("app.storage.files.intent-ttl-seconds", "3600");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.safety-window")
                    .hasMessageContaining("3600");
        });
    }

    @Test
    void fileInventorySafetyWindowBelowPresignTtlIsRejected() {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.gc.inventory-enabled", "true");
        values.put("app.storage.files.gc.safety-window", "20m");
        values.put("app.storage.s3.sign-get-ttl-seconds", "7200");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.safety-window");
        });
    }

    @Test
    void fileInventoryPageSizeOutOfRangeIsRejected() {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.gc.page-size", "1001");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.page-size");
        });
    }

    @Test
    void fileInventoryIntervalBelowOneMinuteIsRejected() {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.gc.interval", "30s");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.interval");
        });
    }

    // ---- physical GC executor settings (stage 07C) ----

    /** The complete, valid execute configuration on a CAMPUS site. */
    private static Map<String, String> validExecute() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.gc.inventory-enabled", "true");
        values.put("app.storage.files.gc.enabled", "true");
        values.put("app.storage.files.gc.dry-run", "false");
        values.put("app.storage.files.gc.execute-enabled", "true");
        values.put("app.storage.files.gc.execute-classes", "STAGING,ORPHAN_CANDIDATE");
        values.put("app.storage.files.gc.execute-ack", "drill-2026-10-14");
        return values;
    }

    @Test
    void coordinatedGcRequiresFileChannelAndGcMasterSwitch() {
        var values=valid("campus","rustfs");values.put("app.storage.files.gc.coordinated-enabled","true");
        configured(values).run(c->assertThat(c.getStartupFailure()).hasMessageContaining("app.sync.files.enabled"));
        values.put("app.sync.files.enabled","true");
        configured(values).run(c->assertThat(c.getStartupFailure()).hasMessageContaining("gc.enabled"));
    }

    @Test
    void externalCoordinatedExecuteAllowsOnlyUnreferencedRevisionClass() {
        var values=validExecute();values.putAll(valid("external","r2"));
        values.put("app.storage.files.gc.coordinated-enabled","true");values.put("app.sync.files.enabled","true");
        values.put("app.storage.files.gc.execute-classes","UNREFERENCED_REVISION");
        configured(values).run(c->assertThat(c).hasNotFailed());
        values.put("app.storage.files.gc.execute-classes","UNREFERENCED_REVISION,STAGING");
        configured(values).run(c->assertThat(c.getStartupFailure()).hasMessageContaining("EXTERNAL coordinated GC"));
    }

    @Test
    void fileGcAllDefaultsAreAcceptedInEverySiteMode() {
        configured(valid("legacy", "generic")).run(context -> assertThat(context).hasNotFailed());
        configured(valid("campus", "rustfs")).run(context -> assertThat(context).hasNotFailed());
        configured(valid("external", "r2")).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void fileGcCompleteValidExecuteCombinationIsAccepted() {
        var values = validExecute();
        values.put("app.storage.files.gc.execute-classes", "STAGING,ORPHAN_CANDIDATE,UNREFERENCED_REVISION");
        values.put("app.storage.files.gc.retention", "7d");
        values.put("app.storage.files.gc.max-deletes-per-run", "1000");
        values.put("app.storage.files.gc.claim-lease", "15m");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"6d", "1h", "0s", "-1d"})
    void fileGcRetentionBelowSevenDaysIsRejectedEvenWithExecuteOff(String retention) {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.gc.retention", retention);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.retention");
        });
    }

    @Test
    void fileGcExecuteRequiresInventoryEnabled() {
        var values = validExecute();
        values.put("app.storage.files.gc.inventory-enabled", "false");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.enabled")
                    .hasMessageContaining("inventory-enabled");
        });
    }

    @Test
    void fileGcExecuteRequiresTheGcSwitch() {
        var values = validExecute();
        values.remove("app.storage.files.gc.enabled");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.execute-enabled")
                    .hasMessageContaining("app.storage.files.gc.enabled");
        });
    }

    @Test
    void fileGcSwitchRequiresInventoryAndIsAcceptedWithIt() {
        var without = valid("campus", "rustfs");
        without.put("app.storage.files.gc.enabled", "true");
        configured(without).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.enabled")
                    .hasMessageContaining("inventory-enabled");
        });
        var with = valid("campus", "rustfs");
        with.put("app.storage.files.gc.enabled", "true");
        with.put("app.storage.files.gc.inventory-enabled", "true");
        configured(with).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void fileGcInventoryAloneIsAcceptedAndDoesNotImplyTheGc() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.gc.inventory-enabled", "true");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void fileGcExecuteRequiresDryRunOff() {
        var values = validExecute();
        values.put("app.storage.files.gc.dry-run", "true");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.dry-run");
        });
    }

    @ParameterizedTest
    @CsvSource({"legacy,generic", "external,r2"})
    void fileGcExecuteRequiresCampusSite(String mode, String provider) {
        var values = validExecute();
        var site = valid(mode, provider);
        values.putAll(site);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.execute-enabled")
                    .hasMessageContaining("CAMPUS");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void fileGcExecuteRequiresANonBlankAck(String ack) {
        var values = validExecute();
        values.put("app.storage.files.gc.execute-ack", ack);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.execute-ack");
        });
    }

    @Test
    void fileGcExecuteRejectsAnUnresolvedAckPlaceholder() {
        var values = validExecute();
        values.put("app.storage.files.gc.execute-ack", "${DRILL_ID}");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.execute-ack");
        });
    }

    @Test
    void fileGcExecuteRequiresAtLeastOneClass() {
        var values = validExecute();
        values.remove("app.storage.files.gc.execute-classes");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.execute-classes");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAGING,BOGUS", "staging", "ALL", "UNREFERENCED"})
    void fileGcExecuteClassesMustBeKnownValues(String classes) {
        var values = validExecute();
        values.put("app.storage.files.gc.execute-classes", classes);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.execute-classes");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1001", "-5"})
    void fileGcMaxDeletesPerRunOutOfRangeIsRejected(String max) {
        var values = validExecute();
        values.put("app.storage.files.gc.max-deletes-per-run", max);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.max-deletes-per-run");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"59s", "16m", "0s"})
    void fileGcClaimLeaseOutOfRangeIsRejected(String lease) {
        var values = validExecute();
        values.put("app.storage.files.gc.claim-lease", lease);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.claim-lease");
        });
    }

    @Test
    void fileGcIntervalBelowOneMinuteIsRejected() {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.gc.gc-interval", "30s");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.gc-interval");
        });
    }

    @Test
    void fileGcErrorsNeverEchoTheAckValue() {
        var values = validExecute();
        values.put("app.storage.files.gc.execute-ack", "synthetic-drill-secret-ack");
        values.put("app.storage.files.gc.max-deletes-per-run", "0");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(trace(context.getStartupFailure())).doesNotContain("synthetic-drill-secret-ack");
        });
    }

    // ---- multipart copy, stale-multipart sweep and per-user presign limits (stage 07E) ----

    @Test
    void multipartDefaultsAreAcceptedEverywhereAndTheBoundaryValuesToo() {
        configured(valid("legacy", "generic")).run(context -> assertThat(context).hasNotFailed());
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.replication.part-size", "5242880");
        values.put("app.storage.files.replication.multipart-threshold", "5242880");
        values.put("app.storage.files.replication.temp-reserve-bytes", "0");
        values.put("app.storage.files.replication.max-bytes-per-second", "0");
        configured(values).run(context -> assertThat(context).hasNotFailed());
        var large = valid("campus", "rustfs");
        large.put("app.storage.files.replication.part-size", "1073741824");
        large.put("app.storage.files.replication.multipart-threshold", "1073741824");
        configured(large).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5242879", "0", "-1", "1073741825"})
    void multipartPartSizeOutsideFiveMiBToOneGiBIsRejected(String partSize) {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.replication.part-size", partSize);
        values.put("app.storage.files.replication.multipart-threshold", "2147483648");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.replication.part-size");
        });
    }

    @Test
    void multipartThresholdBelowThePartSizeIsRejected() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.replication.part-size", "8388608");
        values.put("app.storage.files.replication.multipart-threshold", "8388607");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.replication.multipart-threshold");
        });
    }

    @Test
    void maxFileSizeOverTenThousandPartsIsRejectedAndTheExactLimitIsAccepted() {
        long part = 5L * 1024 * 1024;
        var tooBig = valid("campus", "rustfs");
        tooBig.put("app.storage.files.replication.part-size", String.valueOf(part));
        tooBig.put("app.storage.files.replication.multipart-threshold", String.valueOf(part));
        tooBig.put("app.storage.s3.max-file-size-bytes", String.valueOf(part * 10_000 + 1));
        configured(tooBig).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.replication.part-size");
        });
        var exact = valid("campus", "rustfs");
        exact.put("app.storage.files.replication.part-size", String.valueOf(part));
        exact.put("app.storage.files.replication.multipart-threshold", String.valueOf(part));
        exact.put("app.storage.s3.max-file-size-bytes", String.valueOf(part * 10_000));
        configured(exact).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void negativeTempReserveAndNegativeRateAreRejected() {
        var reserve = valid("legacy", "generic");
        reserve.put("app.storage.files.replication.temp-reserve-bytes", "-1");
        configured(reserve).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.replication.temp-reserve-bytes");
        });
        var rate = valid("legacy", "generic");
        rate.put("app.storage.files.replication.max-bytes-per-second", "-1");
        configured(rate).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.replication.max-bytes-per-second");
        });
    }

    @Test
    void multipartDefaultsMatchTheDocumentedValues() {
        FileReplicationProperties defaults = new FileReplicationProperties();
        assertThat(defaults.getMultipartThreshold()).isEqualTo(16L * 1024 * 1024);
        assertThat(defaults.getPartSize()).isEqualTo(8L * 1024 * 1024);
        assertThat(defaults.getTempReserveBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(defaults.getMaxBytesPerSecond()).isZero();
        assertThat(new S3StorageProperties().getMaxFileSizeBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(new FileGcProperties().getMultipartTtl()).isEqualTo(java.time.Duration.ofHours(24));
        FileLifecycleProperties lifecycle = new FileLifecycleProperties();
        assertThat(lifecycle.getMaxOpenIntentsPerUser()).isZero();
        assertThat(lifecycle.getMaxBytesPerHourPerUser()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"59m", "0s", "30m"})
    void fileGcMultipartTtlBelowOneHourIsRejected(String ttl) {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.gc.multipart-ttl", ttl);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.multipart-ttl");
        });
    }

    @Test
    void fileGcMultipartTtlOfOneHourAndTheStaleMultipartExecuteClassAreAccepted() {
        var values = validExecute();
        values.put("app.storage.files.gc.multipart-ttl", "1h");
        values.put("app.storage.files.gc.execute-classes", "STALE_MULTIPART");
        configured(values).run(context -> assertThat(context).hasNotFailed());
        values.put("app.storage.files.gc.execute-classes", "STAGING,STALE_MULTIPART");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale_multipart", "MULTIPART", "STALE_MULTIPART,BOGUS"})
    void staleMultipartExecuteClassMustBeSpelledExactly(String classes) {
        var values = validExecute();
        values.put("app.storage.files.gc.execute-classes", classes);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.execute-classes");
        });
    }

    @Test
    void presignLimitsAcceptZeroAndPositiveValuesButNotNegativeOnes() {
        var ok = valid("legacy", "generic");
        ok.put("app.storage.files.max-open-intents-per-user", "5");
        ok.put("app.storage.files.max-bytes-per-hour-per-user", "104857600");
        configured(ok).run(context -> assertThat(context).hasNotFailed());
        var open = valid("legacy", "generic");
        open.put("app.storage.files.max-open-intents-per-user", "-1");
        configured(open).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.max-open-intents-per-user");
        });
        var bytes = valid("legacy", "generic");
        bytes.put("app.storage.files.max-bytes-per-hour-per-user", "-1");
        configured(bytes).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.max-bytes-per-hour-per-user");
        });
    }

    // ---- CDN invalidation worker settings (stage 07D) ----

    private static Map<String, String> validWebhook() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.cdn-invalidation.enabled", "true");
        values.put("app.storage.files.cdn-invalidation.provider", "WEBHOOK");
        values.put("app.storage.files.cdn-invalidation.webhook-url", "https://hooks.example.test/cdn/invalidate");
        values.put("app.storage.files.cdn-invalidation.webhook-secret", "synthetic-cdn-secret-must-not-appear");
        return values;
    }

    @Test
    void cdnInvalidationDefaultsAreAcceptedEverywhereAndNoopWhenEnabledNeedsNothingElse() {
        configured(valid("legacy", "generic")).run(context -> assertThat(context).hasNotFailed());
        configured(valid("external", "r2")).run(context -> assertThat(context).hasNotFailed());
        var noop = valid("campus", "rustfs");
        noop.put("app.storage.files.cdn-invalidation.enabled", "true");
        configured(noop).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void cdnInvalidationFullWebhookConfigurationIsAccepted() {
        var values = validWebhook();
        values.put("app.storage.files.cdn-invalidation.timeout", "5s");
        values.put("app.storage.files.cdn-invalidation.lease", "1m");
        values.put("app.storage.files.cdn-invalidation.batch", "100");
        values.put("app.storage.files.cdn-invalidation.retry-base", "1s");
        values.put("app.storage.files.cdn-invalidation.retry-max", "10m");
        values.put("app.storage.files.cdn-invalidation.jitter", "0.5");
        values.put("app.storage.files.cdn-invalidation.poll-interval", "100ms");
        values.put("app.storage.files.cdn-invalidation.alert-after-attempts", "1");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://hooks.example.test/x", "https://user:pw@hooks.example.test/x", "https://hooks.example.test/x?token=abc",
            "https://hooks.example.test/x#frag", "ftp://hooks.example.test/x", "not a url"})
    void cdnWebhookUrlMustBeHttpsWithoutCredentialsQueryOrFragment(String url) {
        var values = validWebhook();
        values.put("app.storage.files.cdn-invalidation.webhook-url", url);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.cdn-invalidation.webhook-url");
            assertThat(trace(context.getStartupFailure())).doesNotContain("pw@").doesNotContain("token=abc");
        });
    }

    @Test
    void cdnWebhookUrlIsRequiredWhenTheWebhookProviderIsEnabled() {
        var values = validWebhook();
        values.remove("app.storage.files.cdn-invalidation.webhook-url");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.cdn-invalidation.webhook-url");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "${CDN_SECRET}", "short-secret", "123456789012345"})
    void cdnWebhookSecretMustBePresentAndResolvedAndIsNeverEchoed(String secret) {
        var values = validWebhook();
        values.put("app.storage.files.cdn-invalidation.webhook-secret", secret);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.cdn-invalidation.webhook-secret");
            assertThat(trace(context.getStartupFailure())).doesNotContain("CDN_SECRET");
        });
        var missing = validWebhook();
        missing.remove("app.storage.files.cdn-invalidation.webhook-secret");
        configured(missing).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void cdnSecretOfExactlySixteenCharactersIsAcceptedAndNeverEchoedWhenTooShort() {
        var ok = validWebhook();
        ok.put("app.storage.files.cdn-invalidation.webhook-secret", "1234567890123456");
        configured(ok).run(context -> assertThat(context).hasNotFailed());
        var tooShort = validWebhook();
        tooShort.put("app.storage.files.cdn-invalidation.webhook-secret", "tooshort-secret");
        configured(tooShort).run(context -> {
            assertThat(context).hasFailed();
            assertThat(trace(context.getStartupFailure())).doesNotContain("tooshort-secret");
        });
    }

    @Test
    void cdnNoopProviderIsRejectedWhileAPublicBaseUrlIsConfiguredUnlessNoCdnIsAssumed() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.cdn-invalidation.enabled", "true");
        values.put("app.storage.s3.public-base-url", "https://cdn.example.test/pub");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.cdn-invalidation.provider")
                    .hasMessageContaining("assume-no-cdn");
        });
        values.put("app.storage.files.cdn-invalidation.assume-no-cdn", "true");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void cdnNoopProviderIsRejectedWhileCdnOutputIsEnabledUnlessNoCdnIsAssumed() {
        var values = valid("campus", "rustfs");
        values.put("app.storage.files.cdn-invalidation.enabled", "true");
        values.put("app.storage.files.replication.cdn-output-enabled", "true");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.cdn-invalidation.provider");
        });
        values.put("app.storage.files.cdn-invalidation.assume-no-cdn", "true");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void cdnNoopRuleDoesNotApplyToWebhookOrToADisabledWorker() {
        var webhook = validWebhook();
        webhook.put("app.storage.s3.public-base-url", "https://cdn.example.test/pub");
        configured(webhook).run(context -> assertThat(context).hasNotFailed());
        var disabled = valid("campus", "rustfs");
        disabled.put("app.storage.s3.public-base-url", "https://cdn.example.test/pub");
        configured(disabled).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void gcStuckAlertThresholdMustBePositive() {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.gc.stuck-alert-after-attempts", "0");
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.gc.stuck-alert-after-attempts");
        });
    }

    @Test
    void cdnWebhookRulesApplyOnlyWhenTheWorkerIsEnabled() {
        var values = validWebhook();
        values.put("app.storage.files.cdn-invalidation.enabled", "false");
        values.remove("app.storage.files.cdn-invalidation.webhook-secret");
        configured(values).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void cdnInvalidationProviderMustBeKnown() {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.cdn-invalidation.provider", "CLOUDWHATEVER");
        configured(values).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @CsvSource({"timeout,500ms", "timeout,31s", "timeout,3m", "retry-max,25h", "batch,0", "batch,101", "lease,29s", "lease,16m", "lease,5s",
            "retry-base,0s", "retry-max,1s", "jitter,0.6", "jitter,-0.1", "poll-interval,50ms", "alert-after-attempts,0"})
    void cdnInvalidationRangesAreEnforced(String key, String value) {
        var values = valid("legacy", "generic");
        values.put("app.storage.files.cdn-invalidation." + key, value);
        configured(values).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("app.storage.files.cdn-invalidation.");
        });
    }

    @Test
    void legacyStartupDoesNotAddARedisProbe() {
        var redis = mock(StringRedisTemplate.class);
        new SiteDependencyVerifier(new SiteProperties(), redis).run(null);
        verifyNoInteractions(redis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAMPUS", "EXTERNAL"})
    void explicitSiteRequiresLocalRedisAndCanStartAfterItsRecovery(String mode) {
        var site = new SiteProperties();
        site.setMode(SiteProperties.Mode.valueOf(mode));
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisCallback<String>>any()))
                .thenThrow(new IllegalStateException(SYNTHETIC_SECRET)).thenReturn("PONG");
        var verifier = new SiteDependencyVerifier(site, redis);
        assertThatThrownBy(() -> verifier.run(null)).hasMessageContaining("spring.data.redis")
                .satisfies(error -> assertThat(trace(error)).doesNotContain(SYNTHETIC_SECRET));
        verifier.run(null);
        verify(redis, times(2)).execute(org.mockito.ArgumentMatchers.<RedisCallback<String>>any());
    }

    private ApplicationContextRunner configured(Map<String, String> values) {
        return runner.withPropertyValues(values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    private static Map<String, String> valid(String mode, String provider) {
        var values = new LinkedHashMap<String, String>();
        values.put("app.site.mode", mode);
        values.put("app.site.id", "test-site");
        values.put("app.site.instance-id", "01234567-89ab-4def-8123-456789abcdef");
        values.put("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/site?connectTimeout=1");
        values.put("app.storage.s3.provider", provider);
        values.put("app.storage.s3.endpoint", provider.equals("r2") ? R2_ORIGIN : "http://127.0.0.1:1");
        values.put("app.storage.s3.region", provider.equals("r2") ? "auto" : "us-east-1");
        values.put("app.storage.s3.bucket", "synthetic-site");
        values.put("app.storage.s3.access-key", "synthetic-access");
        values.put("app.storage.s3.secret-key", SYNTHETIC_SECRET);
        return values;
    }

    private static String trace(Throwable failure) {
        var output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }
}

package you.v50to.eatwhat.service.storage.replication;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FileTransferJobStore.backoff exponential backoff helper.
 */
class BackoffTest {

    @Test
    void attempts1ReturnsApproximatelyBase() {
        Duration base = Duration.ofSeconds(5);
        Duration max = Duration.ofMinutes(30);
        double jitter = 0.0; // No jitter for determinism

        Duration result = FileTransferJobStore.backoff(1, base, max, jitter);
        assertEquals(Duration.ofSeconds(5), result);
    }

    @Test
    void attempts2DoublesBase() {
        Duration base = Duration.ofSeconds(5);
        Duration max = Duration.ofMinutes(30);
        double jitter = 0.0;

        Duration result = FileTransferJobStore.backoff(2, base, max, jitter);
        assertEquals(Duration.ofSeconds(10), result);
    }

    @Test
    void exponentialGrowth() {
        Duration base = Duration.ofSeconds(1);
        Duration max = Duration.ofMinutes(60);
        double jitter = 0.0;

        // 2^0=1, 2^1=2, 2^2=4, 2^3=8, 2^4=16 seconds
        assertEquals(Duration.ofSeconds(1), FileTransferJobStore.backoff(1, base, max, jitter));
        assertEquals(Duration.ofSeconds(2), FileTransferJobStore.backoff(2, base, max, jitter));
        assertEquals(Duration.ofSeconds(4), FileTransferJobStore.backoff(3, base, max, jitter));
        assertEquals(Duration.ofSeconds(8), FileTransferJobStore.backoff(4, base, max, jitter));
        assertEquals(Duration.ofSeconds(16), FileTransferJobStore.backoff(5, base, max, jitter));
    }

    @Test
    void respectsMaximumCap() {
        Duration base = Duration.ofSeconds(5);
        Duration max = Duration.ofSeconds(30);
        double jitter = 0.0;

        // 5 * 2^3 = 40 seconds, but capped at 30
        Duration result = FileTransferJobStore.backoff(4, base, max, jitter);
        assertEquals(max, result);
    }

    @Test
    void jitterWithinBounds() {
        Duration base = Duration.ofSeconds(10);
        Duration max = Duration.ofMinutes(30);
        double jitterFraction = 0.2;

        // With 20% jitter on 10 seconds: ±2 seconds, so 8-12 seconds
        for (int i = 0; i < 50; i++) {
            Duration result = FileTransferJobStore.backoff(1, base, max, jitterFraction);
            assertTrue(result.compareTo(Duration.ofSeconds(8)) >= 0,
                    "Result " + result + " should be >= 8 seconds");
            assertTrue(result.compareTo(Duration.ofSeconds(12)) <= 0,
                    "Result " + result + " should be <= 12 seconds");
        }
    }

    @Test
    void jitterNeverNegative() {
        Duration base = Duration.ofSeconds(1);
        Duration max = Duration.ofMinutes(30);
        double jitterFraction = 0.5;

        for (int i = 0; i < 100; i++) {
            Duration result = FileTransferJobStore.backoff(1, base, max, jitterFraction);
            assertFalse(result.isNegative(), "Backoff should never be negative");
        }
    }

    @Test
    void zeroJitterIsDeterministic() {
        Duration base = Duration.ofSeconds(5);
        Duration max = Duration.ofMinutes(30);
        double jitter = 0.0;

        Duration r1 = FileTransferJobStore.backoff(3, base, max, jitter);
        Duration r2 = FileTransferJobStore.backoff(3, base, max, jitter);
        assertEquals(r1, r2);
    }

    @Test
    void rejectInvalidAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> FileTransferJobStore.backoff(0, Duration.ofSeconds(5), Duration.ofMinutes(30), 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> FileTransferJobStore.backoff(-1, Duration.ofSeconds(5), Duration.ofMinutes(30), 0.0));
    }

    @Test
    void rejectNullBase() {
        assertThrows(NullPointerException.class,
                () -> FileTransferJobStore.backoff(1, null, Duration.ofMinutes(30), 0.0));
    }

    @Test
    void rejectNullMax() {
        assertThrows(NullPointerException.class,
                () -> FileTransferJobStore.backoff(1, Duration.ofSeconds(5), null, 0.0));
    }

    @Test
    void rejectOutOfRangeJitter() {
        assertThrows(IllegalArgumentException.class,
                () -> FileTransferJobStore.backoff(1, Duration.ofSeconds(5), Duration.ofMinutes(30), -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> FileTransferJobStore.backoff(1, Duration.ofSeconds(5), Duration.ofMinutes(30), 1.1));
    }

    @Test
    void largeAttemptNumbersRespectMax() {
        Duration base = Duration.ofSeconds(1);
        Duration max = Duration.ofSeconds(60);
        double jitter = 0.0;

        // Large attempts should still be capped
        Duration result = FileTransferJobStore.backoff(100, base, max, jitter);
        assertEquals(max, result);
    }
}

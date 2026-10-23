package you.v50to.eatwhat.service.storage.replication;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic: a fake clock that only moves when the fake sleeper "sleeps". */
class ByteRateLimiterTest {
    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(1_000L);
    private final AtomicLong totalSleep = new AtomicLong();

    private ByteRateLimiter limiter(long bytesPerSecond) {
        return new ByteRateLimiter(bytesPerSecond, now::get, nanos -> {
            totalSleep.addAndGet(nanos);
            now.addAndGet(nanos);
        });
    }

    @Test
    void unlimitedNeverWaits() {
        ByteRateLimiter unlimited = ByteRateLimiter.unlimited();
        assertTrue(unlimited.isUnlimited());
        unlimited.acquire(Long.MAX_VALUE);
        assertEquals(0, totalSleep.get());
        ByteRateLimiter zero = limiter(0);
        zero.acquire(1_000_000_000L);
        assertEquals(0, totalSleep.get());
    }

    @Test
    void negativeRateIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ByteRateLimiter(-1));
    }

    @Test
    void theInitialBucketHoldsOneSecondOfBurst() {
        ByteRateLimiter limiter = limiter(1000);
        limiter.acquire(1000);
        assertEquals(0, totalSleep.get(), "a full bucket passes one second of bytes at once");
        limiter.acquire(500);
        assertEquals(SECOND / 2, totalSleep.get(), "the next 500 bytes wait half a second at 1000 B/s");
    }

    @Test
    void aRequestLargerThanTheBucketGoesIntoDebtAndWaitsForIt() {
        ByteRateLimiter limiter = limiter(1000);
        limiter.acquire(5000); // 1000 available, 4000 of debt -> 4 s
        assertEquals(4 * SECOND, totalSleep.get());
    }

    @Test
    void sustainedThroughputNeverExceedsTheRate() {
        ByteRateLimiter limiter = limiter(10_000);
        long start = now.get();
        long total = 0;
        for (int i = 0; i < 100; i++) {
            limiter.acquire(1_000);
            total += 1_000;
        }
        double seconds = (now.get() - start) / (double) SECOND;
        // 100_000 bytes with a 10_000 byte burst: the other 90_000 bytes need 9 s.
        assertEquals(9.0, seconds, 0.001);
        assertTrue(total / (seconds + 1.0) <= 10_000 * 1.0001, "average including the burst second stays within the rate");
    }

    @Test
    void idleTimeRefillsOnlyUpToOneSecondOfTokens() {
        ByteRateLimiter limiter = limiter(1000);
        limiter.acquire(1000);
        now.addAndGet(60 * SECOND); // a long pause must not bank a minute of tokens
        limiter.acquire(1000);
        assertEquals(0, totalSleep.get());
        limiter.acquire(1000);
        assertEquals(SECOND, totalSleep.get(), "only one second of burst is ever available");
    }

    @Test
    void reserveReportsTheWaitWithoutSleeping() {
        ByteRateLimiter limiter = limiter(2000);
        assertEquals(0, limiter.reserve(2000));
        assertEquals(SECOND, limiter.reserve(2000));
        assertEquals(0, totalSleep.get(), "reserve() only computes");
    }

    @Test
    void zeroAndNegativeAcquiresAreFree() {
        ByteRateLimiter limiter = limiter(1000);
        limiter.acquire(0);
        limiter.acquire(-5);
        limiter.acquire(1000);
        assertEquals(0, totalSleep.get());
    }

    @Test
    void anInterruptRestoresTheFlagAndFailsTheTransfer() {
        ByteRateLimiter limiter = new ByteRateLimiter(1000, now::get, nanos -> { throw new InterruptedException(); });
        limiter.acquire(1000);
        java.io.UncheckedIOException failure = assertThrows(java.io.UncheckedIOException.class, () -> limiter.acquire(1000));
        assertInstanceOf(java.io.InterruptedIOException.class, failure.getCause());
        assertInstanceOf(InterruptedException.class, failure.getCause().getCause());
        assertTrue(Thread.interrupted(), "the interrupt flag is restored (and cleared here for the next test)");
    }
}

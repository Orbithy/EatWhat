package you.v50to.eatwhat.service.storage.replication;

import java.util.function.LongSupplier;

/**
 * Token-bucket throttle for object-store transfers (bytes per second; 0 means unlimited).
 *
 * <p>The bucket holds one second of tokens and starts full, so short bursts pass at once. A request larger than the
 * bucket (for example a whole multipart part) is allowed: the bucket goes into debt and the caller sleeps until the
 * debt is repaid, so the long-run average never exceeds the configured rate. The clock and the sleeper are
 * injectable so the unit tests are deterministic.
 */
public final class ByteRateLimiter {
    /** Sleeps the calling thread; replaced in tests by a fake that only advances a fake clock. */
    @FunctionalInterface
    public interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private static final ByteRateLimiter UNLIMITED = new ByteRateLimiter(0L);

    private final long bytesPerSecond;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private double tokens;
    private long lastNanos;

    public ByteRateLimiter(long bytesPerSecond) {
        this(bytesPerSecond, System::nanoTime, nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos));
    }

    public ByteRateLimiter(long bytesPerSecond, LongSupplier nanoTime, Sleeper sleeper) {
        if (bytesPerSecond < 0) {
            throw new IllegalArgumentException("bytesPerSecond must be >= 0");
        }
        this.bytesPerSecond = bytesPerSecond;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = java.util.Objects.requireNonNull(sleeper, "sleeper");
        this.tokens = bytesPerSecond;
        this.lastNanos = nanoTime.getAsLong();
    }

    /** The shared no-op limiter. */
    public static ByteRateLimiter unlimited() {
        return UNLIMITED;
    }

    public boolean isUnlimited() {
        return bytesPerSecond == 0;
    }

    public long bytesPerSecond() {
        return bytesPerSecond;
    }

    /**
     * Take {@code bytes} tokens, blocking until the rate allows them. An interrupt restores the interrupt flag and
     * fails the transfer with an {@link java.io.UncheckedIOException} wrapping an {@link java.io.InterruptedIOException}
     * (the replication worker treats that as a transient IO error; it is deliberately not an IllegalStateException,
     * which the worker reads as "the destination object appeared").
     */
    public void acquire(long bytes) {
        if (bytesPerSecond == 0 || bytes <= 0) {
            return;
        }
        long waitNanos = reserve(bytes);
        if (waitNanos > 0) {
            try {
                sleeper.sleepNanos(waitNanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                java.io.InterruptedIOException cause = new java.io.InterruptedIOException("Interrupted while throttling an object-store transfer");
                cause.initCause(interrupted);
                throw new java.io.UncheckedIOException(cause);
            }
        }
    }

    /** Charge the bucket and return how long the caller must wait (0 when the tokens were available). */
    synchronized long reserve(long bytes) {
        long now = nanoTime.getAsLong();
        double refill = (now - lastNanos) * (bytesPerSecond / 1_000_000_000d);
        tokens = Math.min((double) bytesPerSecond, tokens + Math.max(0d, refill));
        lastNanos = now;
        tokens -= bytes;
        if (tokens >= 0) {
            return 0L;
        }
        return (long) Math.ceil(-tokens / bytesPerSecond * 1_000_000_000d);
    }
}

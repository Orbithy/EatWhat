package you.v50to.eatwhat.sync.transport;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** A bounded exponential delay with jitter; delivery attempts and availability live in PostgreSQL. */
public final class RetryPolicy {
    private final long baseMillis;
    private final long maxMillis;
    private final DoubleSupplier random;

    public RetryPolicy(Duration base, Duration maximum) {
        this(base, maximum, () -> ThreadLocalRandom.current().nextDouble());
    }

    public RetryPolicy(Duration base, Duration maximum, DoubleSupplier random) {
        Objects.requireNonNull(base); Objects.requireNonNull(maximum);
        if (base.isNegative() || base.toMillis() < 1 || maximum.compareTo(base) < 0
                || maximum.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid bounded retry intervals");
        }
        baseMillis = base.toMillis(); maxMillis = maximum.toMillis();
        this.random = Objects.requireNonNull(random);
    }

    public Duration delay(int attempts) {
        long exponential = baseMillis;
        for (int n = 1; n < Math.max(1, attempts) && exponential < maxMillis; n++) {
            exponential = Math.min(maxMillis, exponential > maxMillis / 2 ? maxMillis : exponential * 2);
        }
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample >= 1) {
            throw new IllegalStateException("Invalid retry jitter source");
        }
        return Duration.ofMillis(Math.max(1, Math.min(maxMillis, (long) (exponential * (0.5 + sample)))));
    }
}

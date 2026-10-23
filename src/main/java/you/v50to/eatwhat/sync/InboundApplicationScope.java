package you.v50to.eatwhat.sync;

import java.util.function.Supplier;

/** Synchronous, same-thread database replay only; prevents producing an equivalent outbound event. */
public final class InboundApplicationScope {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    private InboundApplicationScope() { }
    public static boolean isActive() { return Boolean.TRUE.equals(ACTIVE.get()); }
    public static <T> T run(Supplier<T> action) {
        if (isActive()) throw new IllegalStateException("Nested inbound application is not supported");
        ACTIVE.set(true);
        try { return action.get(); }
        finally { ACTIVE.remove(); }
    }
}

package you.v50to.eatwhat.account.transport;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded request IDs only: no operation, identifier, proof, hash or credential is retained. */
public final class AccountReplayWindow {
    static final int CAPACITY = 4096;
    private static final long RETENTION_NANOS = 61_000_000_000L;
    public enum Reservation { ACCEPTED, STALE, REPLAY, FULL }
    private record Seen(long monotonicTime, Instant validUntil) { }
    private final Map<UUID, Seen> seen = new HashMap<>();

    public synchronized Reservation reserve(UUID id, Instant issuedAt, Instant now, long monotonicNow) {
        if (issuedAt.isBefore(now.minusSeconds(30)) || issuedAt.isAfter(now.plusSeconds(30))) return Reservation.STALE;
        seen.entrySet().removeIf(entry -> monotonicNow - entry.getValue().monotonicTime() >= RETENTION_NANOS
                && now.isAfter(entry.getValue().validUntil()));
        if (seen.containsKey(id)) return Reservation.REPLAY;
        if (seen.size() >= CAPACITY) return Reservation.FULL;
        seen.put(id, new Seen(monotonicNow, issuedAt.plusSeconds(30)));
        return Reservation.ACCEPTED;
    }
}

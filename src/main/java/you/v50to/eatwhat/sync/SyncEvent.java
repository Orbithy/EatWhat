package you.v50to.eatwhat.sync;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Internal event contract, deliberately separate from existing HTTP DTOs and numeric identities. */
public record SyncEvent(UUID eventId, String originSite, UUID originInstanceId,
                        String aggregateType, UUID aggregateId, long numericId,
                        String eventType, int schemaVersion, VersionVector baseVersion,
                        VersionVector nextVersion, List<Dependency> dependencies,
                        UUID actorId, String commandKey, UUID correlationId, String payloadJson) {
    public SyncEvent {
        requireUuid(eventId, "eventId");
        requireName(originSite, "originSite", 64);
        requireUuid(originInstanceId, "originInstanceId");
        requireName(aggregateType, "aggregateType", 64);
        requireUuid(aggregateId, "aggregateId");
        if (!validNumericIdentity(aggregateType, numericId)) {
            throw new IllegalArgumentException("numericId does not match the aggregate identity kind");
        }
        requireName(eventType, "eventType", 96);
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        Objects.requireNonNull(baseVersion, "baseVersion");
        if (!baseVersion.increment(originSite).equals(nextVersion)) {
            throw new IllegalArgumentException("nextVersion must increment only the origin by one");
        }
        Objects.requireNonNull(dependencies, "dependencies");
        if (dependencies.size() > 100 || dependencies.stream().anyMatch(Objects::isNull)
                || new HashSet<>(dependencies).size() != dependencies.size()) {
            throw new IllegalArgumentException("Invalid event dependencies");
        }
        dependencies = dependencies.stream().sorted(Comparator.comparing(Dependency::aggregateType)
                .thenComparing(dependency -> dependency.aggregateId().toString())).toList();
        if (actorId != null) requireUuid(actorId, "actorId");
        requireName(commandKey, "commandKey", 128);
        requireUuid(correlationId, "correlationId");
        Objects.requireNonNull(payloadJson, "payloadJson");
    }

    /** Zero is the explicit UUID-only marker, restricted to the four internal file families. */
    public static boolean validNumericIdentity(String type, long id) {
        boolean uuidOnly = java.util.Set.of("file.metadata", "file.references", "file.receipt", "file.replica", "file.gc").contains(type);
        return uuidOnly ? id == 0 : id > 0 && id <= VersionVector.MAX_COUNTER;
    }

    public record Dependency(String aggregateType, UUID aggregateId) {
        public Dependency {
            requireName(aggregateType, "dependency type", 64);
            requireUuid(aggregateId, "dependency identity");
        }
    }

    public static void requireName(String value, String label, int limit) {
        if (value == null || value.isEmpty() || value.length() > limit
                || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]*")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    public static void requireUuid(UUID value, String label) {
        if (value == null || value.equals(new UUID(0, 0))) throw new IllegalArgumentException("Invalid " + label);
    }
}

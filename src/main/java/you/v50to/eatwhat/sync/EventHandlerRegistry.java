package you.v50to.eatwhat.sync;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable explicit registrations. The production configuration supplies an empty list in PR-03B. */
public final class EventHandlerRegistry {
    private record Key(String aggregateType, String eventType, int schemaVersion) {}

    private final Map<Key, EventHandler> handlers;

    public EventHandlerRegistry(List<EventHandler> handlers) {
        Map<Key, EventHandler> registered = new HashMap<>();
        for (EventHandler handler : List.copyOf(handlers)) {
            Objects.requireNonNull(handler, "handler");
            if (handler.aggregateType() == null || handler.aggregateType().isBlank()
                    || handler.eventType() == null || handler.eventType().isBlank() || handler.schemaVersion() <= 0) {
                throw new IllegalArgumentException("Invalid event handler registration");
            }
            var key = new Key(handler.aggregateType(), handler.eventType(), handler.schemaVersion());
            if (registered.putIfAbsent(key, handler) != null) {
                throw new IllegalArgumentException("Duplicate event handler registration");
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public Optional<EventHandler> find(SyncEvent event) {
        Objects.requireNonNull(event, "event");
        return Optional.ofNullable(handlers.get(new Key(event.aggregateType(), event.eventType(), event.schemaVersion())));
    }
}

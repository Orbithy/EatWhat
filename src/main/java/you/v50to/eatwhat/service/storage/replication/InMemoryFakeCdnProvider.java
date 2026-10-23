package you.v50to.eatwhat.service.storage.replication;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Test provider: records every request and returns scripted results (default: Accepted). */
public final class InMemoryFakeCdnProvider implements CdnInvalidationProvider {
    public record Received(List<String> paths, Reason reason, UUID taskId) { }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Deque<Result> script = new ArrayDeque<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile Result fallback;
    private volatile RuntimeException failure;

    /** The next submissions return these results in order. */
    public synchronized void enqueue(Result... results) {
        script.addAll(List.of(results));
    }

    /** Result used once the script is empty (null means Accepted with a generated id). */
    public void alwaysReturn(Result result) {
        this.fallback = result;
    }

    /** Make every submission throw (the worker treats that as Retryable). */
    public void alwaysThrow(RuntimeException failure) {
        this.failure = failure;
    }

    public List<Received> received() {
        return new ArrayList<>(received);
    }

    @Override
    public Result submit(List<String> cdnPaths, Reason reason, UUID taskId) {
        received.add(new Received(List.copyOf(cdnPaths), reason, taskId));
        RuntimeException toThrow = failure;
        if (toThrow != null) throw toThrow;
        synchronized (this) {
            Result next = script.poll();
            if (next != null) return next;
        }
        Result result = fallback;
        return result != null ? result : new Accepted("fake-request-" + counter.incrementAndGet());
    }
}

package mrnavastar.invsync.sync;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** Login I/O is independent of ticks. An abandoned reservation expires without applying data. */
public final class Admissions implements AutoCloseable {
    private final PlayerStore store;
    private final BindingCodes bindings;
    private final Map<UUID, CompletableFuture<PlayerStore.Session>> requests = new HashMap<>();
    private final ExecutorService database = Executors.newFixedThreadPool(4,
            Thread.ofPlatform().daemon().name("InvSync-login-", 0).factory());
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("InvSync-login-expiry").factory());
    private boolean closed;

    public Admissions(PlayerStore store, BindingCodes bindings) {
        this.store = store;
        this.bindings = bindings;
    }

    public synchronized CompletableFuture<PlayerStore.Session> prepare(UUID id) {
        var existing = requests.get(id);
        if (existing != null) return existing;
        if (closed || requests.size() >= 128) return CompletableFuture.completedFuture(null);
        var future = CompletableFuture.supplyAsync(() -> {
            try { return store.acquire(id, bindings); }
            catch (Exception e) { throw new CompletionException(e); }
        }, database);
        requests.put(id, future);
        future.whenComplete((session, error) -> {
            synchronized (this) {
                if (!closed) expiry.schedule(() -> discard(id, future), 30, TimeUnit.SECONDS);
            }
        });
        return future;
    }

    public synchronized PlayerStore.Session take(UUID id) throws Exception {
        var future = requests.get(id);
        if (future == null || !future.isDone()) return null;
        requests.remove(id);
        return future.get();
    }

    synchronized void discard(UUID id, CompletableFuture<PlayerStore.Session> future) {
        if (!requests.remove(id, future)) return;
        future.thenAccept(session -> {
            if (session != null) try { session.close(); }
            catch (Exception e) { System.getLogger("InvSync").log(System.Logger.Level.ERROR, "Reservation cleanup", e); }
        });
    }

    @Override public synchronized void close() {
        closed = true;
        Map.copyOf(requests).forEach(this::discard);
        database.shutdown();
        expiry.shutdownNow();
    }
}

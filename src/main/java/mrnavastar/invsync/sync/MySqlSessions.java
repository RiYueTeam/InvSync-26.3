package mrnavastar.invsync.sync;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import static java.nio.file.StandardCopyOption.*;
import mrnavastar.invsync.sync.PlayerStore.Snapshot;

/** A token stays in MySQL until the final snapshot and release commit together. */
final class MySqlSessions {
    private enum Phase { CLAIMING, ACTIVE, READY, RELEASING }
    private record Entry(UUID login, UUID role, UUID token, Phase phase, Snapshot data, Boolean refreshMapping) {
        Entry with(Phase next, Snapshot snapshot) { return new Entry(login, role, token, next, snapshot, refreshMapping); }
    }
    private final Gson gson = new Gson();
    private final Path dir;
    private final PlayerStore store;
    private final ConcurrentMap<UUID, Slot> slots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r ->
            Thread.ofPlatform().daemon().name("InvSync-handoff").unstarted(r));

    MySqlSessions(PlayerStore store, Path dir) throws SQLException, IOException {
        this.store = store;
        this.dir = Files.createDirectories(dir);
        store.schema("sessions", "CREATE TABLE IF NOT EXISTS InvSync_sessions (id CHAR(36) PRIMARY KEY, token CHAR(36)) ENGINE=InnoDB");
        try (var files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                var entry = gson.fromJson(Files.readString(file), Entry.class);
                var slot = new Slot();
                slot.entry = entry;
                slots.put(entry.login(), slot);
            }
        }
        worker.scheduleWithFixedDelay(() -> slots.forEach((login, slot) -> slot.retry()), 1, 2, TimeUnit.SECONDS);
    }

    static void lockRole(Connection c, UUID role) throws SQLException {
        try (var lock = c.prepareStatement(
                "INSERT INTO InvSync_sessions(id) VALUES (?) ON DUPLICATE KEY UPDATE id=id")) {
            lock.setString(1, role.toString());
            lock.executeUpdate();
        }
    }

    void stop() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    PlayerStore.Session acquire(UUID login, BindingCodes bindings) throws IOException, SQLException {
        Slot slot = slots.computeIfAbsent(login, ignored -> new Slot());
        synchronized (slot) {
            if (slot.online || slot.finalSnapshot != null) return null;
            if (slot.entry != null) {
                boolean resume = Boolean.FALSE.equals(slot.entry.refreshMapping())
                        || (slot.entry.refreshMapping() == null && bindings == null);
                if (resume && (slot.entry.phase() == Phase.READY || slot.entry.phase() == Phase.ACTIVE)) {
                    slot.write(slot.entry.with(Phase.ACTIVE, slot.entry.data()));
                    slot.online = true;
                    return slot;
                }
                try (var c = store.connect()) { slot.publish(c); }
            }

            try (Connection c = store.connect()) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                c.setAutoCommit(false);
                // Serialize mapping changes with admission for this login UUID.
                lockRole(c, login);
                UUID role = bindings == null ? login : bindings.saveId(c, login);
                if (!role.equals(login)) lockRole(c, role);
                if (bindings != null && bindings.hasOccupiedAlias(c, role)) {
                    c.rollback();
                    return null;
                }
                try (var claim = c.prepareStatement("UPDATE InvSync_sessions SET token=? WHERE id=? AND token IS NULL")) {
                    var token = UUID.randomUUID();
                    claim.setString(1, token.toString());
                    claim.setString(2, role.toString());
                    if (claim.executeUpdate() == 0) { c.rollback(); return null; }
                    Snapshot snapshot = new PlayerStore.JdbcSession(c, null, role.toString(), true).load();
                    slot.write(new Entry(login, role, token, Phase.CLAIMING, snapshot, false));
                    c.commit();
                    slot.write(slot.entry.with(Phase.ACTIVE, snapshot));
                    slot.online = true;
                    return slot;
                }
            }
        }
    }

    void requireMappingRefresh(UUID login) throws IOException {
        Slot slot = slots.get(login);
        if (slot == null) throw new IOException("Missing active session");
        synchronized (slot) {
            Entry e = slot.entry;
            if (!slot.online || e == null) throw new IOException("Binding requires an active session");
            slot.write(new Entry(login, e.role(), e.token(), e.phase(), e.data(), true));
        }
    }

    private final class Slot implements PlayerStore.Session {
        Entry entry;
        boolean online;

        void write(Entry next) throws IOException {
            Path temp = dir.resolve(next.login() + ".tmp");
            Files.writeString(temp, gson.toJson(next));
            Path target = dir.resolve(next.login() + ".json");
            for (int attempt = 0; ; attempt++) {
                try {
                    Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING);
                    break;
                } catch (AccessDeniedException e) {
                    // Windows file scanners can temporarily prevent replacing a closed file.
                    if (attempt >= 5) throw e;
                    try { Thread.sleep(20L * (attempt + 1)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted replacing " + target, interrupted);
                    }
                }
            }
            entry = next;
        }

        public synchronized String id() { return entry.role().toString(); }
        public synchronized Snapshot load() { return entry.data(); }
        public synchronized void save(Snapshot data) throws IOException {
            write(entry.with(Phase.ACTIVE, merge(data)));
        }
        private Snapshot merge(Snapshot data) {
            Snapshot old = entry.data();
            return new Snapshot(data.playerData() == null ? old.playerData() : data.playerData(),
                    data.advancements() == null ? old.advancements() : data.advancements(),
                    data.stats() == null ? old.stats() : data.stats());
        }

        private Snapshot finalSnapshot;
        public synchronized void finish(Snapshot data) {
            finalSnapshot = merge(data);
            online = false;
            persistFinal();
            worker.execute(this::retry);
        }
        public synchronized void finish() { finish(entry.data()); }

        private boolean persistFinal() {
            if (finalSnapshot == null) return true;
            try {
                write(entry.with(Phase.READY, finalSnapshot));
                finalSnapshot = null;
                return true;
            } catch (IOException e) {
                if (!reported) System.getLogger("InvSync").log(System.Logger.Level.WARNING,
                        "Final snapshot awaiting local disk for " + entry.login(), e);
                reported = true;
                return false;
            }
        }
        public void close() throws IOException { finish(); }

        private boolean reported;
        private boolean pending() {
            return entry != null && !online && entry.phase() != Phase.ACTIVE;
        }
        void retry() {
            UUID token;
            synchronized (this) {
                if (!persistFinal() || !pending()) return;
                token = entry.token();
            }
            // A failed connection attempt must not hold the original server's login thread.
            try (var c = store.connect()) {
                synchronized (this) {
                    if (pending() && entry.token().equals(token)) publish(c);
                }
            } catch (SQLException | IOException e) {
                synchronized (this) {
                    if (pending() && !reported) {
                        System.getLogger("InvSync").log(System.Logger.Level.WARNING,
                                "Handoff pending for " + entry.login(), e);
                        reported = true;
                    }
                }
            }
        }

        // Persist the ambiguous state before sending the release to MySQL.
        void publish(Connection c) throws SQLException, IOException {
            c.setAutoCommit(false);
            try (var release = c.prepareStatement("UPDATE InvSync_sessions SET token=NULL WHERE id=? AND token=?")) {
                release.setString(1, id());
                release.setString(2, entry.token().toString());
                if (entry.phase() != Phase.CLAIMING) write(entry.with(Phase.RELEASING, entry.data()));
                if (release.executeUpdate() == 1 && entry.phase() != Phase.CLAIMING)
                    new PlayerStore.JdbcSession(c, null, id(), true).save(entry.data());
                c.commit();
            }
            Files.delete(dir.resolve(entry.login() + ".json"));
            entry = null;
            reported = false;
        }
    }
}

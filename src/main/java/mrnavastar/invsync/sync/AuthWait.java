package mrnavastar.invsync.sync;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/** Keep a verified connection in vanilla configuration while its shared session is opening. */
public final class AuthWait {
    private record Wait(UUID login, CompletableFuture<PlayerStore.Session> result, boolean returning, long start) {}
    private final Map<Connection, Wait> waiting = new IdentityHashMap<>();

    public static Connection connection(ServerPlayer player) {
        for (var c : player.level().getServer().getConnection().getConnections())
            if (c.getPacketListener() == player.connection) return c;
        return null;
    }

    public boolean begin(ServerPlayer player, Admissions admissions) {
        Connection c = connection(player);
        if (c == null) return false;
        if (waiting.containsKey(c)) return true;
        var id = player.getUUID();
        waiting.put(c, new Wait(id, admissions.prepare(id), false, System.nanoTime()));
        player.connection.switchToConfig();
        return true;
    }

    public void tick(Admissions admissions, java.util.function.Consumer<ServerConfigurationPacketListenerImpl> resume) {
        var it = waiting.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Connection c = e.getKey();
            Wait w = e.getValue();
            if (!c.isConnected() || System.nanoTime() - w.start() > 30_000_000_000L) {
                it.remove();
                admissions.discard(w.login(), w.result());
                if (c.isConnected()) c.disconnect(Component.literal("等待共享存档超时，请重新登录。"));
            } else if (!w.returning() && w.result().isDone()
                    && c.getPacketListener() instanceof ServerConfigurationPacketListenerImpl config) {
                if (w.result().isCompletedExceptionally() || w.result().getNow(null) == null) {
                    it.remove();
                    admissions.discard(w.login(), w.result());
                    config.disconnect(Component.literal("共享存档暂不可用，请稍后重新登录。"));
                } else {
                    e.setValue(new Wait(w.login(), w.result(), true, w.start()));
                    resume.accept(config);
                }
            }
        }
    }

    public boolean returning(Connection c, UUID login) {
        Wait w = waiting.get(c);
        if (w == null || !w.returning() || !w.login().equals(login)) return false;
        waiting.remove(c);
        return true;
    }
}

package mrnavastar.invsync.sync;

import com.google.gson.JsonParser;
import mrnavastar.invsync.Config;
import mrnavastar.invsync.InvSync;
import mrnavastar.invsync.interfaces.IPlayerAdvancementTracker;
import mrnavastar.invsync.interfaces.IServerStatHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SyncManager {
    // InvSync 2.3.3 stripped DataVersion from Minecraft 1.20.1 player data.
    public static final int LEGACY_DATA_VERSION = 3465;

    private final Config config;
    private final PlayerStore store;
    private final List<String> localFields;
    private final Map<UUID, PlayerStore.Session> sessions = new HashMap<>();

    public SyncManager(Config config, PlayerStore store) throws IOException {
        this.config = config;
        this.store = store;
        try (var stream = SyncManager.class.getResourceAsStream("/assets/invsync/blacklist");
             var reader = new java.io.BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            localFields = reader.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        }
    }

    public boolean join(ServerPlayer player) {
        UUID id = player.getUUID();
        if (sessions.containsKey(id)) return false;

        PlayerStore.Session session = null;
        try {
            session = store.acquire(id);
            if (session == null) {
                InvSync.LOGGER.warn("Player {} still has an active InvSync session", id);
                return false;
            }
            load(player, session.load());
            sessions.put(id, session);
            return true;
        } catch (Exception e) {
            InvSync.LOGGER.error("Could not load InvSync data for {}", id, e);
            close(session);
            return false;
        }
    }

    private static CompoundTag playerTag(ServerPlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(output);
        return output.buildResult();
    }

    private void load(ServerPlayer player, PlayerStore.Snapshot snapshot) throws Exception {
        var server = player.level().getServer();
        if (config.SYNC_ADVANCEMENTS && snapshot.advancements() != null) {
            ((IPlayerAdvancementTracker) player.getAdvancements()).writeAdvancementData(
                    server, JsonParser.parseString(snapshot.advancements()));
        }
        if (config.SYNC_STATS && snapshot.stats() != null) {
            ((IServerStatHandler) player.getStats()).writeStatData(server, snapshot.stats());
        }
        if (config.SYNC_PLAYER_DATA && snapshot.playerData() != null) {
            CompoundTag data = TagParser.parseCompoundFully(snapshot.playerData());
            data = DataFixTypes.PLAYER.updateToCurrentVersion(
                    server.getFixerUpper(), data, data.getIntOr("DataVersion", LEGACY_DATA_VERSION));
            CompoundTag local = playerTag(player);
            for (String field : localFields) {
                var value = local.get(field);
                if (value == null) {
                    data.remove(field);
                } else {
                    data.put(field, value);
                }
            }
            player.load(TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), data));
        }
    }

    public void save(ServerPlayer player) {
        UUID id = player.getUUID();
        PlayerStore.Session session = sessions.get(id);
        if (session == null) return;

        try {
            String playerData = null;
            if (config.SYNC_PLAYER_DATA) {
                CompoundTag data = playerTag(player);
                localFields.forEach(data::remove);
                playerData = NbtUtils.addCurrentDataVersion(data).toString();
            }
            String advancements = config.SYNC_ADVANCEMENTS
                    ? ((IPlayerAdvancementTracker) player.getAdvancements()).readAdvancementData().toString() : null;
            String stats = config.SYNC_STATS ? ((IServerStatHandler) player.getStats()).readStatData() : null;
            session.save(new PlayerStore.Snapshot(playerData, advancements, stats));
        } catch (Exception e) {
            InvSync.LOGGER.error("Could not save InvSync data for {}", id, e);
        }
    }

    public void leave(ServerPlayer player) {
        close(sessions.remove(player.getUUID()));
    }

    private static void close(PlayerStore.Session session) {
        if (session == null) return;
        try {
            session.close();
        } catch (Exception e) {
            InvSync.LOGGER.error("Could not close InvSync session for {}", session.id(), e);
        }
    }
}

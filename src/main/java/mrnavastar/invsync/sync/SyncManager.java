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
import java.sql.SQLException;

public final class SyncManager {
    // InvSync 2.3.3 stripped DataVersion from Minecraft 1.20.1 player data.
    public static final int LEGACY_DATA_VERSION = 3465;

    private final Admissions admissions;
    private final AuthWait authWait = new AuthWait();
    public void tickAdmissions() { authWait.tick(admissions, net.minecraft.server.network.ServerConfigurationPacketListenerImpl::returnToWorld); }
    private final Map<UUID, ServerPlayer> pendingLeaves = new HashMap<>();
    private final Config config;
    private final PlayerStore store;
    private final BindingCodes bindings;
    private final List<String> localFields;
    private final Map<UUID, PlayerStore.Session> sessions = new HashMap<>();

    public SyncManager(Config config, PlayerStore store) throws IOException, SQLException {
        this.config = config;
        this.store = store;
        bindings = config.REQUIRE_AUTH_CALLBACK ? new BindingCodes(store) : null;
        admissions = new Admissions(store, bindings);
        try (var stream = SyncManager.class.getResourceAsStream("/assets/invsync/blacklist");
             var reader = new java.io.BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            localFields = reader.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        }
    }

    public void stop() { retryLeaves(); admissions.close(); store.stop(); }
    public boolean prepareBeforeJoin() { return store.isMySql() && bindings == null; }
    public java.util.concurrent.CompletableFuture<PlayerStore.Session> prepare(UUID id) { return admissions.prepare(id); }
    public void retryLeaves() {
        for (var player : List.copyOf(pendingLeaves.values())) leave(player);
    }

    public boolean join(ServerPlayer player, net.minecraft.network.Connection connection) {
        if (bindings != null && !authWait.returning(connection, player.getUUID())) return true;
        return openSession(player);
    }

    public boolean authenticated(ServerPlayer player) {
        if (bindings == null) return false;
        if (sessions.containsKey(player.getUUID())) return !pendingLeaves.containsKey(player.getUUID());
        if (store.isMySql()) return authWait.begin(player, admissions);
        if (!openSession(player)) return false;
        player.inventoryMenu.sendAllDataToRemote();
        player.setExperienceLevels(player.experienceLevel);
        return true;
    }

    public boolean callbackEnabled() {
        return bindings != null;
    }

    public String bindingCommand(ServerPlayer player, String[] args) throws SQLException {
        if (bindings == null || pendingLeaves.containsKey(player.getUUID()) || !sessions.containsKey(player.getUUID())) return "请先完成登录认证。";
        return bindings.command(new BindingCodes.Role(config.SERVER_ID, player.getUUID(),
                player.getGameProfile().name()), args);
    }

    private boolean openSession(ServerPlayer player) {
        UUID id = player.getUUID();
        if (sessions.containsKey(id)) return false;

        PlayerStore.Session session = null;
        try {
            session = store.isMySql() ? admissions.take(id) : store.acquire(id, bindings);
            if (session == null) {
                InvSync.LOGGER.warn("Player {} still has an active InvSync session", id);
                return false;
            }
            LocalPlayerArchive.capture(player.level().getServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT), id);
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
            CompoundTag data = parsePlayerData(player, snapshot.playerData());
            SharedPlayerData.keepSupportedEnderItems(data, player.getEnderChestInventory().getContainerSize());
            CompoundTag local = playerTag(player);
            if (data.getBooleanOr(SharedPlayerData.PARTIAL, false)) {
                SharedPlayerData.copyFields(data, local);
                player.load(TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), local));
                return;
            }
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

    public boolean save(ServerPlayer player) { return save(player, false); }

    private boolean save(ServerPlayer player, boolean leaving) {
        UUID id = player.getUUID();
        PlayerStore.Session session = sessions.get(id);
        if (session == null || pendingLeaves.getOrDefault(id, player) != player) return false;

        try {
            String playerData = null;
            if (config.SYNC_PLAYER_DATA) {
                CompoundTag data = playerTag(player);
                String previous = session.load().playerData();
                if (previous != null) {
                    var previousEnderItems = parsePlayerData(player, previous).getListOrEmpty(SharedPlayerData.ENDER_ITEMS);
                    SharedPlayerData.preserveUnsupportedEnderItems(
                            data, previousEnderItems, player.getEnderChestInventory().getContainerSize());
                }
                localFields.forEach(data::remove);
                playerData = NbtUtils.addCurrentDataVersion(data).toString();
            }
            String advancements = config.SYNC_ADVANCEMENTS
                    ? ((IPlayerAdvancementTracker) player.getAdvancements()).readAdvancementData().toString() : null;
            String stats = config.SYNC_STATS ? ((IServerStatHandler) player.getStats()).readStatData() : null;
            var snapshot = new PlayerStore.Snapshot(playerData, advancements, stats);
            if (leaving) session.finish(snapshot);
            else session.save(snapshot);
            return true;
        } catch (Exception e) {
            InvSync.LOGGER.error("Could not save InvSync data for {}", id, e);
            return false;
        }
    }

    private static CompoundTag parsePlayerData(ServerPlayer player, String serialized) throws Exception {
        CompoundTag data = TagParser.parseCompoundFully(serialized);
        return DataFixTypes.PLAYER.updateToCurrentVersion(player.level().getServer().getFixerUpper(),
                data, data.getIntOr("DataVersion", LEGACY_DATA_VERSION));
    }

    public void leave(ServerPlayer player) {
        if (bindings != null) bindings.leave(player.getUUID());
        UUID login = player.getUUID();
        if (!sessions.containsKey(login) || pendingLeaves.getOrDefault(login, player) != player) return;
        if (save(player, true)) {
            sessions.remove(login);
            pendingLeaves.remove(login);
        } else pendingLeaves.put(login, player);
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

package mrnavastar.invsync.spigot;

import com.google.gson.JsonParser;
import mrnavastar.invsync.sync.PlayerStore;
import mrnavastar.invsync.sync.SharedPlayerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class InvSyncSpigot extends JavaPlugin implements Listener {
    private final Map<UUID, PlayerStore.Session> sessions = new HashMap<>();
    private PlayerStore store;
    private AdvancementAccess advancements;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        try {
            saveDefaultConfig();
            var config = getConfig();
            var settings = new PlayerStore.Settings("MYSQL", config.getString("mysql.database"), null,
                    config.getString("mysql.host"), Integer.toString(config.getInt("mysql.port")),
                    config.getString("mysql.username"), config.getString("mysql.password"),
                    new PlayerStore.TlsSettings(config.getString("mysql.tls.mode", "PREFERRED"),
                            config.getString("mysql.tls.trust-store", ""),
                            config.getString("mysql.tls.trust-store-type", "PKCS12"),
                            config.getString("mysql.tls.trust-store-password", "")));
            advancements = new AdvancementAccess();
            store = new PlayerStore(settings);
            getServer().getScheduler().runTaskTimer(this, this::saveOnlinePlayers, 6000L, 6000L);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not initialize InvSync. Joins are blocked until it is configured.", e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerStore.Session session = null;
        try {
            if (store == null || sessions.containsKey(player.getUniqueId())) {
                reject(event);
                return;
            }
            session = store.acquire(player.getUniqueId());
            if (session == null) {
                reject(event);
                return;
            }
            load(handle(player), session.load());
            player.updateInventory();
            sessions.put(player.getUniqueId(), session);
        } catch (Exception e) {
            close(session);
            getLogger().log(Level.SEVERE, "Could not load InvSync data for " + player.getUniqueId(), e);
            reject(event);
        }
    }

    private void reject(PlayerJoinEvent event) {
        event.setJoinMessage(null);
        event.getPlayer().kickPlayer("InvSync could not load your data. Please reconnect.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerStore.Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            save(player, session);
            close(session);
        }
    }

    @Override
    public void onDisable() {
        saveOnlinePlayers();
        sessions.values().forEach(this::close);
        sessions.clear();
    }

    private void saveOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            PlayerStore.Session session = sessions.get(player.getUniqueId());
            if (session != null) save(player, session);
        }
    }

    private void load(ServerPlayer player, PlayerStore.Snapshot snapshot) throws Exception {
        var server = player.level().getServer();
        CompoundTag saved = parsePlayerData(player, snapshot.playerData());
        if (saved != null) {
            validateInventory(player, saved);
        }
        if (snapshot.advancements() != null) {
            advancements.load(player.getAdvancements(), server.getAdvancements(),
                    JsonParser.parseString(snapshot.advancements()));
        }
        if (saved != null) {
            CompoundTag local = playerTag(player);
            SharedPlayerData.copyFields(saved, local);
            player.load(TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), local));
            player.setExperienceLevels(player.experienceLevel);
        }
    }

    private void save(Player player, PlayerStore.Session session) {
        try {
            ServerPlayer handle = handle(player);
            var previous = session.load();
            CompoundTag data = parsePlayerData(handle, previous.playerData());
            if (data == null) {
                data = new CompoundTag();
                data.putBoolean(SharedPlayerData.PARTIAL, true);
            }
            SharedPlayerData.copyFields(playerTag(handle), data);
            if (data.get(SharedPlayerData.ENDER_ITEMS) == null) {
                data.put(SharedPlayerData.ENDER_ITEMS, new ListTag());
            }
            String advancementData = advancements.save(handle.getAdvancements(),
                    handle.level().getServer().getAdvancements(),
                    previous.advancements() == null ? null : JsonParser.parseString(previous.advancements()));
            session.save(new PlayerStore.Snapshot(NbtUtils.addCurrentDataVersion(data).toString(), advancementData, null));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not save InvSync data for " + player.getUniqueId(), e);
        }
    }

    private static ServerPlayer handle(Player player) throws ReflectiveOperationException {
        return (ServerPlayer) player.getClass().getMethod("getHandle").invoke(player);
    }

    private static CompoundTag playerTag(ServerPlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(output);
        return output.buildResult();
    }

    private static CompoundTag parsePlayerData(ServerPlayer player, String value) throws Exception {
        if (value == null) return null;
        CompoundTag data = TagParser.parseCompoundFully(value);
        return DataFixTypes.PLAYER.updateToCurrentVersion(player.level().getServer().getFixerUpper(),
                data, data.getIntOr("DataVersion", 3465));
    }

    private static void validateInventory(ServerPlayer player, CompoundTag data) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
        var inventory = data.get("Inventory");
        if (inventory != null) ItemStackWithSlot.CODEC.listOf().parse(ops, inventory).getOrThrow();
        var enderItems = data.get(SharedPlayerData.ENDER_ITEMS);
        if (enderItems != null) ItemStackWithSlot.CODEC.listOf().parse(ops, enderItems).getOrThrow();
        var equipment = data.get("equipment");
        if (equipment != null) EntityEquipment.CODEC.parse(ops, equipment).getOrThrow();
    }

    private void close(PlayerStore.Session session) {
        if (session == null) return;
        try {
            session.close();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not close InvSync session for " + session.id(), e);
        }
    }
}

package mrnavastar.invsync.spigot;

import com.google.gson.JsonParser;
import mrnavastar.invsync.sync.PlayerStore;
import mrnavastar.invsync.sync.LocalPlayerArchive;
import mrnavastar.invsync.sync.BindingCodes;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import mrnavastar.invsync.sync.SharedPlayerData;
import net.minecraft.nbt.CompoundTag;
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
    private BindingCodes bindings;

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
            PlayerStore candidate = new PlayerStore(settings);
            if (config.getBoolean("require-auth-callback")) bindings = new BindingCodes(candidate);
            store = candidate;
            getServer().getScheduler().runTaskTimer(this, this::saveOnlinePlayers, 6000L, 6000L);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not initialize InvSync. Joins are blocked until it is configured.", e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (store == null || (bindings == null && !openSession(player))) reject(event);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("help"))) {
            sender.sendMessage(BindingCodes.HELP);
            return true;
        }
        if (bindings == null || store == null) {
            sender.sendMessage("InvSync 绑定功能未启用。");
            return true;
        }
        if (args.length == 2 && args[0].equals("authenticated")) {
            if (sender != getServer().getConsoleSender()) {
                sender.sendMessage("仅限服务器控制台。");
                return true;
            }
            Player player = getServer().getPlayerExact(args[1]);
            if (player == null) sender.sendMessage("玩家未在线。");
            else if (!sessions.containsKey(player.getUniqueId()) && !openSession(player))
                player.kickPlayer("共享存档加载失败，请重新登录。");
            return true;
        }
        if (!(sender instanceof Player player) || !sessions.containsKey(player.getUniqueId())) {
            sender.sendMessage("请先完成登录认证。");
            return true;
        }
        try {
            sender.sendMessage(bindings.command(new BindingCodes.Role(getConfig().getString("server-id", "spigot"),
                    player.getUniqueId(), player.getName()), args));
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "InvSync binding request failed", e);
            sender.sendMessage("绑定请求失败，请核对账号是否已经绑定。");
        }
        return true;
    }

    private boolean openSession(Player player) {
        PlayerStore.Session session = null;
        try {
            if (store == null || sessions.containsKey(player.getUniqueId())) {
                return false;
            }
            UUID id = player.getUniqueId();
            session = store.acquire(id, bindings);
            if (session == null) {
                return false;
            }
            ServerPlayer handle = handle(player);
            LocalPlayerArchive.capture(handle.level().getServer().getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT), player.getUniqueId());
            load(handle, session.load());
            player.updateInventory();
            sessions.put(player.getUniqueId(), session);
            return true;
        } catch (Exception e) {
            close(session);
            getLogger().log(Level.SEVERE, "Could not load InvSync data for " + player.getUniqueId(), e);
            return false;
        }
    }

    private void reject(PlayerJoinEvent event) {
        event.setJoinMessage(null);
        event.getPlayer().kickPlayer("InvSync could not load your data. Please reconnect.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (bindings != null) bindings.leave(player.getUniqueId());
        PlayerStore.Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            finish(player, session);
        }
    }

    @Override
    public void onDisable() {
        for (Player player : getServer().getOnlinePlayers()) {
            var session = sessions.get(player.getUniqueId());
            if (session != null) finish(player, session);
        }
        if (store != null) store.stop();
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
            SharedPlayerData.keepSupportedEnderItems(saved, player.getEnderChestInventory().getContainerSize());
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

    private void finish(Player player, PlayerStore.Session session) {
        if (save(player, session)) {
            try { session.finish(); } catch (Exception e) { getLogger().log(Level.SEVERE, "Handoff failed", e); }
        }
    }

    private boolean save(Player player, PlayerStore.Session session) {
        try {
            ServerPlayer handle = handle(player);
            var previous = session.load();
            CompoundTag data = parsePlayerData(handle, previous.playerData());
            if (data == null) {
                data = new CompoundTag();
                data.putBoolean(SharedPlayerData.PARTIAL, true);
            }
            var previousEnderItems = data.getListOrEmpty(SharedPlayerData.ENDER_ITEMS);
            SharedPlayerData.copyFields(playerTag(handle), data);
            SharedPlayerData.preserveUnsupportedEnderItems(
                    data, previousEnderItems, handle.getEnderChestInventory().getContainerSize());
            String advancementData = advancements.save(handle.getAdvancements(),
                    handle.level().getServer().getAdvancements(),
                    previous.advancements() == null ? null : JsonParser.parseString(previous.advancements()));
            session.save(new PlayerStore.Snapshot(NbtUtils.addCurrentDataVersion(data).toString(), advancementData, null));
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not save InvSync data for " + player.getUniqueId(), e);
            return false;
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

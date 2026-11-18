package mrnavastar.invsync.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import mrnavastar.invsync.interfaces.IPlayerAdvancementTracker;
import mrnavastar.invsync.sync.SyncManager;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.util.datafix.DataFixTypes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementTrackerMixin implements IPlayerAdvancementTracker {
    @Unique
    private static final Codec<Map<Identifier, AdvancementProgress>> invsync$codec =
            Codec.unboundedMap(Identifier.CODEC, AdvancementProgress.CODEC);

    @Shadow public abstract void clearTriggers();
    @Shadow @Final private Map<AdvancementHolder, AdvancementProgress> progress;
    @Shadow @Final private Set<AdvancementHolder> visible;
    @Shadow @Final private Set<AdvancementHolder> progressChanged;
    @Shadow @Final private Set<AdvancementNode> rootsToUpdate;
    @Shadow private boolean isFirstPacket;
    @Shadow private AdvancementHolder lastSelectedTab;

    @Invoker("startProgress")
    protected abstract void invsync$startProgress(AdvancementHolder advancement, AdvancementProgress progress);

    @Invoker("markForVisibilityUpdate")
    protected abstract void invsync$markForVisibilityUpdate(AdvancementHolder advancement);

    @Invoker("registerListeners")
    protected abstract void invsync$registerListeners(ServerAdvancementManager manager);

    @Override
    public void writeAdvancementData(MinecraftServer server, JsonElement data) {
        JsonObject versioned = data.getAsJsonObject().deepCopy();
        if (!versioned.has("DataVersion")) {
            versioned.addProperty("DataVersion", SyncManager.LEGACY_DATA_VERSION);
        }
        var codec = DataFixTypes.ADVANCEMENTS.wrapCodec(invsync$codec, server.getFixerUpper(),
                SyncManager.LEGACY_DATA_VERSION);
        var storedProgress = codec.parse(JsonOps.INSTANCE, versioned).getOrThrow();

        clearTriggers();
        progress.clear();
        visible.clear();
        progressChanged.clear();
        rootsToUpdate.clear();
        isFirstPacket = true;
        lastSelectedTab = null;

        ServerAdvancementManager manager = server.getAdvancements();
        storedProgress.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(entry -> {
            AdvancementHolder advancement = manager.get(entry.getKey());
            if (advancement != null) {
                invsync$startProgress(advancement, entry.getValue());
                progressChanged.add(advancement);
                invsync$markForVisibilityUpdate(advancement);
            }
        });
        invsync$registerListeners(manager);
    }

    @Override
    public JsonElement readAdvancementData() {
        Map<Identifier, AdvancementProgress> storedProgress = new HashMap<>();
        progress.forEach((advancement, value) -> {
            if (value.hasProgress()) storedProgress.put(advancement.id(), value);
        });
        JsonObject data = invsync$codec.encodeStart(JsonOps.INSTANCE, storedProgress).getOrThrow().getAsJsonObject();
        data.addProperty("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        return data;
    }
}

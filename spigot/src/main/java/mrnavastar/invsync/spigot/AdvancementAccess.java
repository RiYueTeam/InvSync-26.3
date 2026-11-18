package mrnavastar.invsync.spigot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

final class AdvancementAccess {
    private final Field codec;
    private final Method asData;
    private final Method applyFrom;
    private final Method registerListeners;
    private final Field progress;
    private final List<Field> updateSets;
    private final Field firstPacket;
    private final Field selectedTab;

    AdvancementAccess() throws ReflectiveOperationException {
        codec = field("codec");
        asData = method("asData");
        applyFrom = method("applyFrom", ServerAdvancementManager.class, asData.getReturnType());
        registerListeners = method("registerListeners", ServerAdvancementManager.class);
        progress = field("progress");
        updateSets = List.of(field("visible"), field("progressChanged"), field("rootsToUpdate"));
        firstPacket = field("isFirstPacket");
        selectedTab = field("lastSelectedTab");
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = PlayerAdvancements.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = PlayerAdvancements.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings("unchecked")
    private Codec<Object> codec(PlayerAdvancements tracker) throws IllegalAccessException {
        return (Codec<Object>) codec.get(tracker);
    }

    private Object decode(PlayerAdvancements tracker, JsonElement data) throws IllegalAccessException {
        JsonObject versioned = data.getAsJsonObject().deepCopy();
        if (!versioned.has("DataVersion")) versioned.addProperty("DataVersion", 3465);
        return codec(tracker).parse(JsonOps.INSTANCE, versioned).getOrThrow();
    }

    void load(PlayerAdvancements tracker, ServerAdvancementManager manager, JsonElement data)
            throws ReflectiveOperationException {
        Object decoded = decode(tracker, data);
        tracker.clearTriggers();
        ((Map<?, ?>) progress.get(tracker)).clear();
        for (Field field : updateSets) {
            ((Collection<?>) field.get(tracker)).clear();
        }
        firstPacket.setBoolean(tracker, true);
        selectedTab.set(tracker, null);
        applyFrom.invoke(tracker, manager, decoded);
        registerListeners.invoke(tracker, manager);
    }

    String save(PlayerAdvancements tracker, ServerAdvancementManager manager, JsonElement previous)
            throws ReflectiveOperationException {
        Codec<Object> trackerCodec = codec(tracker);
        JsonObject data = trackerCodec.encodeStart(JsonOps.INSTANCE, asData.invoke(tracker))
                .getOrThrow().getAsJsonObject();
        if (previous != null) {
            JsonObject normalized = trackerCodec.encodeStart(JsonOps.INSTANCE, decode(tracker, previous))
                    .getOrThrow().getAsJsonObject();
            for (var entry : normalized.entrySet()) {
                if (entry.getKey().equals("DataVersion")) continue;
                Identifier id = Identifier.parse(entry.getKey());
                if (manager.get(id) == null) data.add(entry.getKey(), entry.getValue());
            }
        }
        return data.toString();
    }
}

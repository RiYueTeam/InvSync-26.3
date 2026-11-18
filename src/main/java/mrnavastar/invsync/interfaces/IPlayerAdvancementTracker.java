package mrnavastar.invsync.interfaces;

import com.google.gson.JsonElement;
import net.minecraft.server.MinecraftServer;

public interface IPlayerAdvancementTracker {
    void writeAdvancementData(MinecraftServer server, JsonElement data);

    JsonElement readAdvancementData();
}

package mrnavastar.invsync.interfaces;

import net.minecraft.server.MinecraftServer;

public interface IServerStatHandler {
    void writeStatData(MinecraftServer server, String data);

    String readStatData();
}

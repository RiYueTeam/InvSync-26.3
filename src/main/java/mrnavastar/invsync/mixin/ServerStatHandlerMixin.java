package mrnavastar.invsync.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.datafixers.DataFixer;
import mrnavastar.invsync.interfaces.IServerStatHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.StatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerStatsCounter.class)
public abstract class ServerStatHandlerMixin extends StatsCounter implements IServerStatHandler {
    @Shadow protected abstract JsonElement toJson();
    @Shadow public abstract void parse(DataFixer dataFixer, JsonElement data);
    @Shadow public abstract void markAllDirty();

    @Override
    public void writeStatData(MinecraftServer server, String data) {
        JsonElement parsed = JsonParser.parseString(data);
        stats.clear();
        parse(server.getFixerUpper(), parsed);
        markAllDirty();
    }

    @Override
    public String readStatData() {
        return toJson().toString();
    }
}

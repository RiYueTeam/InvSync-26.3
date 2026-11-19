package mrnavastar.invsync.mixin;

import mrnavastar.invsync.InvSync;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class ServerShutdownMixin {
    @Inject(method = "stopServer", at = @At("RETURN"))
    private void invsync$stop(CallbackInfo ci) {
        if (InvSync.sync != null) InvSync.sync.stop();
    }
}

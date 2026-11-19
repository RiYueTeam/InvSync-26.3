package mrnavastar.invsync.mixin;

import mrnavastar.invsync.InvSync;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ConfigurationLoginMixin {
    @Unique private java.util.concurrent.CompletableFuture<?> invsync$pending;

    @Inject(method = "startConfiguration", at = @At("HEAD"), cancellable = true)
    private void invsync$prepare(CallbackInfo ci) {
        if (!InvSync.sync.prepareBeforeJoin()) return;
        if (invsync$pending == null) {
            var listener = (ServerConfigurationPacketListenerImpl)(Object)this;
            invsync$pending = InvSync.sync.prepare(listener.getOwner().id());
        }
        if (!invsync$pending.isDone()) ci.cancel();
        else invsync$pending = null;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void invsync$resume(CallbackInfo ci) {
        if (invsync$pending != null && invsync$pending.isDone())
            ((ServerConfigurationPacketListenerImpl)(Object)this).startConfiguration();
    }
}

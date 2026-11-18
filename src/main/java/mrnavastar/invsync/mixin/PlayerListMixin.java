package mrnavastar.invsync.mixin;

import mrnavastar.invsync.InvSync;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void invsync$load(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        if (!InvSync.sync.join(player)) {
            connection.disconnect(Component.literal("InvSync could not load your data. Please reconnect."));
            ci.cancel();
        }
    }

    @Inject(method = "save", at = @At("HEAD"))
    private void invsync$save(ServerPlayer player, CallbackInfo ci) {
        InvSync.sync.save(player);
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void invsync$release(ServerPlayer player, CallbackInfo ci) {
        InvSync.sync.leave(player);
    }
}

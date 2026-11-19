package mrnavastar.invsync.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import mrnavastar.invsync.InvSync;
import mrnavastar.invsync.sync.BindingCodes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class BindingCommandsMixin {
    @Shadow public abstract CommandDispatcher<CommandSourceStack> getDispatcher();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void invsync$commands(CallbackInfo ci) {
        com.mojang.brigadier.Command<CommandSourceStack> help = ctx -> {
            ctx.getSource().sendSystemMessage(Component.literal(BindingCodes.HELP));
            return 1;
        };
        var root = Commands.literal("invsync").executes(help);
        root.then(Commands.literal("help").executes(help));
        root.then(Commands.literal("authenticated").requires(source ->
                ((CommandSourceAccessor) source).invsync$source() == source.getServer()
                        && source.getEntity() == null)
                .then(Commands.argument("player", StringArgumentType.word()).executes(ctx -> {
                    var source = ctx.getSource();
                    var player = source.getServer().getPlayerList().getPlayerByName(
                            StringArgumentType.getString(ctx, "player"));
                    if (player == null || !InvSync.sync.callbackEnabled()) return 0;
                    if (InvSync.sync.authenticated(player)) return 1;
                    player.connection.disconnect(Component.literal("共享存档加载失败，请重新登录。"));
                    return 0;
                })));
        root.then(Commands.argument("action", StringArgumentType.greedyString()).executes(ctx -> {
            var player = ctx.getSource().getPlayerOrException();
            try {
                String reply = InvSync.sync.bindingCommand(player,
                        StringArgumentType.getString(ctx, "action").trim().split("\\s+"));
                player.sendSystemMessage(Component.literal(reply));
                return 1;
            } catch (Exception e) {
                InvSync.LOGGER.warn("Binding request failed for {}", player.getUUID(), e);
                player.sendSystemMessage(Component.literal("绑定请求失败，请核对账号是否已经绑定。"));
                return 0;
            }
        }));
        getDispatcher().register(root);
    }
}

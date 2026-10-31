package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;

/** Dev only: runs the bot's server side tasks on the integrated server thread, at the end of a server tick. */
@Mixin(MinecraftServer.class)
public abstract class BotMinecraftServerMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void vkbot$serverTick(CallbackInfo ci) {
        TestBot.onServerTick((MinecraftServer) (Object) this);
    }
}

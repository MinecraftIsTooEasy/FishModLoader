package net.xiaoyu233.fml.vulkan.mixin.test;

import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.test.AutoScript;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test harness: runs the server side steps queued by {@link AutoScript} on the server thread (start of every tick). The queue is
 * empty (one volatile read) unless {@code fml.vulkan.autoScript} is used.
 */
@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {
    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void fmlVk$runAutoScriptTasks(CallbackInfo ci) {
        AutoScript.runServerTasks();
    }
}

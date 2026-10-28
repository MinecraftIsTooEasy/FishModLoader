package vkbench.mixin;

import vkbench.Bench;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only: runs the bench's server side tasks (dimension transfer, world set-up) on the integrated server thread. */
@Mixin(net.minecraft.server.MinecraftServer.class)
public abstract class BenchMinecraftServerMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void vkbench$serverTick(CallbackInfo ci) {
        Bench.onServerTick();
    }
}

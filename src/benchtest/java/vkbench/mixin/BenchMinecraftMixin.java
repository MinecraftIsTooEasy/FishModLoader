package vkbench.mixin;

import net.minecraft.Minecraft;
import vkbench.Bench;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev only: frame boundary and tick hooks of the bench mod. Only anchors that exist on every build of the comparison (OpenGL baseline, phase-1 emulation, native
 * renderer): the head of {@code runGameLoop}, the {@code screenshotListener()} call (Appendix D keeps it) and the end of {@code runTick}.
 */
@Mixin(Minecraft.class)
public abstract class BenchMinecraftMixin {
    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void vkbench$frameStart(CallbackInfo ci) {
        Bench.onFrameStart();
    }

    @Inject(method = "runGameLoop", at = @At(value = "INVOKE", target = "Lnet/minecraft/Minecraft;screenshotListener()V"))
    private void vkbench$frameEnd(CallbackInfo ci) {
        Bench.onFrameEnd((Minecraft) (Object) this);
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void vkbench$tick(CallbackInfo ci) {
        Bench.onClientTick((Minecraft) (Object) this);
    }
}

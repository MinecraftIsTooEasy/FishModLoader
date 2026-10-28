package vkbench.mixin;

import net.minecraft.MouseHelper;
import vkbench.Bench;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only, bench mode: the bench never grabs the mouse, never warps the cursor and real mouse movement never rotates its camera. */
@Mixin(MouseHelper.class)
public abstract class BenchMouseHelperMixin {
    @Shadow
    public int deltaX;
    @Shadow
    public int deltaY;

    @Inject(method = "grabMouseCursor", at = @At("HEAD"), cancellable = true)
    private void vkbench$noGrab(CallbackInfo ci) {
        if (Bench.ENABLED) {
            this.deltaX = 0;
            this.deltaY = 0;
            ci.cancel();
        }
    }

    @Inject(method = "ungrabMouseCursor", at = @At("HEAD"), cancellable = true)
    private void vkbench$noWarp(CallbackInfo ci) {
        if (Bench.ENABLED) {
            ci.cancel();
        }
    }

    @Inject(method = "mouseXYChange", at = @At("HEAD"), cancellable = true)
    private void vkbench$noMouse(CallbackInfo ci) {
        if (Bench.ENABLED) {
            this.deltaX = 0;
            this.deltaY = 0;
            ci.cancel();
        }
    }
}

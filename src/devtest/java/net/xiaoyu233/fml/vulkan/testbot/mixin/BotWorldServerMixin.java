package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.WorldServer;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev only, frozen-world mode (7.2): the integrated server stops the passage of time and every random / ambiance block tick
 * (MITE's {@code WorldServer.tickBlocksAndAmbiance}) and the scheduled block updates ({@code tickUpdates}); time of day is set explicitly by the scene script.
 */
@Mixin(WorldServer.class)
public abstract class BotWorldServerMixin {
    @Inject(method = "tickBlocksAndAmbiance", at = @At("HEAD"), cancellable = true)
    private void vkbot$noRandomTicks(CallbackInfo ci) {
        if (Frozen.worldFrozen()) {
            ci.cancel();
        }
    }

    /** Scheduled block updates (flowing water / lava, falling sand, redstone, saplings ...) keep re-meshing sections for ever: frozen mode does not run them. */
    @Inject(method = "tickUpdates", at = @At("HEAD"), cancellable = true)
    private void vkbot$noScheduledTicks(boolean flag, CallbackInfoReturnable<Boolean> cir) {
        if (Frozen.worldFrozen()) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }

    /** Chunks that were not ticked for a while "fast forward" their random block ticks (leaf decay, growth, ...) when they become active: not in frozen-world mode. */
    @Inject(method = "tickBlocksInFastForward()V", at = @At("HEAD"), cancellable = true)
    private void vkbot$noFastForward(CallbackInfo ci) {
        if (Frozen.worldFrozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldTimeProgress", at = @At("HEAD"), cancellable = true)
    private void vkbot$noTimeProgress(CallbackInfoReturnable<Boolean> cir) {
        if (Frozen.worldFrozen()) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}

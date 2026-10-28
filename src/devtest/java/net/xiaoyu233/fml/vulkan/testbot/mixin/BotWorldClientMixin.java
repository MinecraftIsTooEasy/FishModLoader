package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only, frozen-world mode (7.2): no client side ambiance tick and no weather drift (the scene script fixes the rain strength). */
@Mixin(WorldClient.class)
public abstract class BotWorldClientMixin {
    @Inject(method = "tickBlocksAndAmbiance", at = @At("HEAD"), cancellable = true)
    private void vkbot$noAmbiance(CallbackInfo ci) {
        if (Frozen.worldFrozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateWeather", at = @At("HEAD"), cancellable = true)
    private void vkbot$lockedWeather(CallbackInfo ci) {
        if (Frozen.weatherLocked()) {
            ci.cancel();
        }
    }
}

package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.TextureMap;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only, frozen-world mode (7.2): atlas animation (water, lava, fire, portal, ...) stands still while the atlas is frozen (capture route) or the client tick is paused. */
@Mixin(TextureMap.class)
public abstract class BotTextureMapMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void vkbot$pausedAnimation(CallbackInfo ci) {
        if (Frozen.atlasFrozen()) {
            ci.cancel();
        }
    }
}

package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.Timer;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only, frozen-world mode: a capture window renders with one fixed partial tick (the tick accumulator is left alone so the bot's tick hook keeps running). */
@Mixin(Timer.class)
public abstract class BotTimerMixin {
    @Shadow
    public float renderPartialTicks;

    @Inject(method = "updateTimer", at = @At("RETURN"))
    private void vkbot$fixedPartialTicks(CallbackInfo ci) {
        if (Frozen.captureWindowOpen()) {
            this.renderPartialTicks = Frozen.forcedPartialTicks();
        }
    }
}

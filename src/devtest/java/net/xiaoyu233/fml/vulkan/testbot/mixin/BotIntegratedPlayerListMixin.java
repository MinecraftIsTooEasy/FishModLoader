package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.IntegratedPlayerList;
import net.minecraft.ServerConfigurationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev only: MITE's integrated server hard codes a view distance of 10 chunks (IntegratedPlayerList), so a client render distance of 16 chunks would
 * never receive more than 10. {@code -Pfml.vulkan.testbot.viewDistance=16} (runClient -Ptestbot_view=16) raises it so the render distance 16 perf runs
 * really load and mesh 16 chunks around the player. Unset (0) = vanilla behaviour.
 */
@Mixin(IntegratedPlayerList.class)
public abstract class BotIntegratedPlayerListMixin extends ServerConfigurationManager {
    protected BotIntegratedPlayerListMixin() {
        super(null);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vkbot$viewDistance(CallbackInfo ci) {
        int v = Integer.getInteger("fml.vulkan.testbot.viewDistance", 0);
        if (v > 0) {
            this.viewDistance = v;
        }
    }
}

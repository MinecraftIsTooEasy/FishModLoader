package net.xiaoyu233.fml.vulkan.mixin.opt.fps;

import net.minecraft.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WP-26 (OPT): timing probe of {@code Minecraft.downtimeProcessing}. Inspection (MITE {@code Minecraft.java:800}) shows the
 * method's only work is synchronous and gameplay-adjacent: it temporarily uses the viewer's position for a cloud recompile and
 * runs {@code World.checkLightingOfRandomBlockInView} inside MITE's 16-block budget. Nothing is safely relocatable after present,
 * so this mixin changes no behaviour. It only accumulates the wall time of each call and logs the average every 600 calls at DEBUG
 * on {@code FML-Opt-Fps}. Gated by {@code -Dfml.vulkan.opt.fps=false}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftDowntimeFpsMixin {
    private static final Logger FPS_LOGGER = LogManager.getLogger("FML-Opt-Fps");
    private static final int REPORT_EVERY = 600;

    private static long fishmodloader$startNanos;
    private static long fishmodloader$totalNanos;
    private static int fishmodloader$calls;

    @Inject(method = "downtimeProcessing", at = @At("HEAD"), require = 0)
    private void fishmodloader$downtimeStart(long milliseconds_of_downtime, CallbackInfo ci) {
        fishmodloader$startNanos = System.nanoTime();
    }

    @Inject(method = "downtimeProcessing", at = @At("RETURN"), require = 0)
    private void fishmodloader$downtimeEnd(long milliseconds_of_downtime, CallbackInfo ci) {
        fishmodloader$totalNanos += System.nanoTime() - fishmodloader$startNanos;
        if (++fishmodloader$calls >= REPORT_EVERY) {
            if (FPS_LOGGER.isDebugEnabled()) {
                FPS_LOGGER.debug("downtimeProcessing: avg {} ms over {} calls",
                        String.format("%.3f", fishmodloader$totalNanos / 1.0e6 / fishmodloader$calls), fishmodloader$calls);
            }
            fishmodloader$totalNanos = 0L;
            fishmodloader$calls = 0;
        }
    }
}

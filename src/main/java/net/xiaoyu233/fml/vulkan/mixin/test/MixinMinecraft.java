package net.xiaoyu233.fml.vulkan.mixin.test;

import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.test.AutoScript;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test harness: shuts the game down cleanly after N presented frames.
 * Activated by {@code -Dfml.vulkan.exitAfterFrames=N} (0 / unset = never). The option is read once (through
 * {@link VulkanConfig#exitAfterFrames()}, which trims and validates it); the frame count is {@link RenderCore#frameNumber()}, the
 * number of presented frames including those of the loading screens. When the backend is not running (nothing is presented) the
 * game loop iterations are counted instead, so the harness cannot hang a broken run forever.
 * <p>
 * The injector has {@code require = 1}: when a MITE revision renames {@code runGameLoop}, Mixin logs a "Critical injection failure"
 * banner instead of silently skipping the hook (an unattended run would then never exit). The mixin config is not required, so
 * the game itself still starts.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Unique
    private static boolean fmlVk$configRead;
    @Unique
    private static long fmlVk$exitAfterFrames;
    @Unique
    private static long fmlVk$loopIterations;
    @Unique
    private static boolean fmlVk$exitRequested;

    @Inject(method = "runGameLoop", at = @At("TAIL"), require = 1)
    private void fmlVk$onRunGameLoopEnd(CallbackInfo ci) {
        if (fmlVk$exitRequested) {
            return;
        }
        AutoScript.tick((Minecraft) (Object) this);
        if (!fmlVk$configRead) {
            fmlVk$configRead = true;
            fmlVk$exitAfterFrames = VulkanConfig.exitAfterFrames();
        }
        if (fmlVk$exitAfterFrames <= 0L) {
            return;
        }

        long frames = RenderCore.isInitialized() ? RenderCore.frameNumber() : ++fmlVk$loopIterations;
        if (frames >= fmlVk$exitAfterFrames) {
            fmlVk$exitRequested = true;
            VkLog.LOGGER.info("fml.vulkan.exitAfterFrames={} reached ({} frames), shutting down", fmlVk$exitAfterFrames, frames);
            ((Minecraft) (Object) this).shutdown();
        }
    }
}

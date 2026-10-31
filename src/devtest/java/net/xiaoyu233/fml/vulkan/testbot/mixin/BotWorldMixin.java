package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.Entity;
import net.minecraft.World;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev only, frozen-world mode (7.2): scene entities pinned by {@link Frozen#freezeEntity} are not ticked (AI, movement, animation counters), client and server. */
@Mixin(World.class)
public abstract class BotWorldMixin {
    /** Frozen-world mode: the random light re-check of blocks in view (Math.random raycasts) never runs, so no section is re-meshed behind a capture's back. */
    @Inject(method = "checkLightingOfRandomBlockInView", at = @At("HEAD"), cancellable = true)
    private void vkbot$noRandomLightChecks(boolean minimal, CallbackInfo ci) {
        if (Frozen.worldFrozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateEntityWithOptionalForce", at = @At("HEAD"), cancellable = true)
    private void vkbot$frozenEntity(Entity entity, boolean force, CallbackInfo ci) {
        if (Frozen.isFrozenEntity(entity)) {
            ci.cancel();
        }
    }
}

package net.xiaoyu233.fml.vulkan.mixin.opt.quality;

import net.xiaoyu233.fml.vulkan.opt.QualitySwitches;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.Entity;
import net.minecraft.Render;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * WP-18 (ENT-07): entity shadow switch ({@code fml.vulkan.quality.entityShadows}). Wraps only the {@code renderShadow} call inside
 * {@link Render#doRenderShadowAndFire} (not overwritten by any native mixin); fire rendering in the same method is untouched.
 * With the default {@code true} the call always runs, so the output is unchanged.
 */
@Mixin(Render.class)
public abstract class RenderShadowMixin {
    @WrapWithCondition(
            method = "doRenderShadowAndFire(Lnet/minecraft/Entity;DDDFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/Render;renderShadow(Lnet/minecraft/Entity;DDDFF)V")
    )
    private boolean fml$entityShadows(Render self, Entity entity, double x, double y, double z, float shadowOpacity, float partialTicks) {
        return QualitySwitches.shadowsEnabled();
    }
}

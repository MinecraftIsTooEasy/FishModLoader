package net.xiaoyu233.fml.vulkan.mixin.opt.quality;

import net.xiaoyu233.fml.vulkan.opt.QualitySwitches;
import net.minecraft.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * WP-18 (ENT-07): entity render distance scale ({@code fml.vulkan.quality.distanceScale}, default 1.0).
 * <p>The squared distance passed to {@code Entity.isInRangeToRenderDist} is divided by {@code scale^2} (effective radius times
 * {@code scale}). It is changed only at the call inside {@code Entity.isInRangeToRenderVec3D}, which is the render path
 * (RenderGlobal and the native entity visibility test). {@code EntityLiving} calls {@code isInRangeToRenderDist} directly for the
 * despawn rule, and that gameplay check stays unscaled. Scale 1.0 passes the value through unchanged.
 */
@Mixin(Entity.class)
public abstract class EntityDistanceMixin {
    @ModifyArg(
            method = "isInRangeToRenderVec3D(Lnet/minecraft/Vec3;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/Entity;isInRangeToRenderDist(D)Z"),
            index = 0
    )
    private double fml$scaleRenderDistance(double distanceSq) {
        double scale = QualitySwitches.distanceScale();
        return scale == 1.0 ? distanceSq : distanceSq / (scale * scale);
    }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.minecraft.RenderSkeleton;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": redirects of the skeleton's matrix calls to {@link Draw} (template A). */
@Mixin(RenderSkeleton.class)
public abstract class RenderSkeletonMobsMixin {
    @Redirect(method = "scaleSkeleton(Lnet/minecraft/EntitySkeleton;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }

    @Redirect(method = "func_82422_c()V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) { Draw.translate(x, y, z); }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import net.minecraft.RenderGelatinousCube;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": redirects the GL calls of {@link RenderGelatinousCube} to {@link Draw} / {@link GlShim} (templates A and E). */
@Mixin(RenderGelatinousCube.class)
public abstract class RenderGelatinousCubeMobsMixin {
    @Redirect(method = "shouldRenderPass(Lnet/minecraft/EntityGelatinousCube;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "shouldRenderPass(Lnet/minecraft/EntityGelatinousCube;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = "shouldRenderPass(Lnet/minecraft/EntityGelatinousCube;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private void fml$blendFunc(int src, int dst) { GlShim.blendFunc(src, dst); }

    @Redirect(method = "shouldRenderPass(Lnet/minecraft/EntityGelatinousCube;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = "scaleGelatinousCube(Lnet/minecraft/EntityGelatinousCube;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }
}

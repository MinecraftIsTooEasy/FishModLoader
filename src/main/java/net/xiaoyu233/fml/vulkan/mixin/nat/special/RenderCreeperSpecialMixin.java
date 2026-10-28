package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderCreeper;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "special" (NR-20, template E): one redirect per (method, GL11 descriptor).
 * Charged-armour pass: the texture-matrix ops (glMatrixMode/glLoadIdentity/glTranslatef) go through
 * GlShim, which selects Draw.uvMatrix() while mode is GL_TEXTURE.
 */
@Mixin(RenderCreeper.class)
public abstract class RenderCreeperSpecialMixin {

    // ---------------------------------------------------------------- renderCreeperPassModel(EntityCreeper, int, float)I
    @Redirect(method = "renderCreeperPassModel(Lnet/minecraft/EntityCreeper;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private void fml$pass$blendFunc(int src, int dst) { GlShim.blendFunc(src, dst); }

    @Redirect(method = "renderCreeperPassModel(Lnet/minecraft/EntityCreeper;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$pass$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = "renderCreeperPassModel(Lnet/minecraft/EntityCreeper;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthMask(Z)V"))
    private void fml$pass$depthMask(boolean flag) { GlShim.depthMask(flag); }

    @Redirect(method = "renderCreeperPassModel(Lnet/minecraft/EntityCreeper;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$pass$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = "renderCreeperPassModel(Lnet/minecraft/EntityCreeper;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$pass$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "renderCreeperPassModel(Lnet/minecraft/EntityCreeper;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glLoadIdentity()V"))
    private void fml$pass$loadIdentity() { GlShim.loadIdentity(); }

    @Redirect(method = "renderCreeperPassModel(Lnet/minecraft/EntityCreeper;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glMatrixMode(I)V"))
    private void fml$pass$matrixMode(int mode) { GlShim.matrixMode(mode); }

    @Redirect(method = "renderCreeperPassModel(Lnet/minecraft/EntityCreeper;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$pass$translatef(float x, float y, float z) { GlShim.translatef(x, y, z); }

    // ---------------------------------------------------------------- updateCreeperScale(EntityCreeper, float)V
    @Redirect(method = "updateCreeperScale(Lnet/minecraft/EntityCreeper;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale$scalef(float x, float y, float z) { Draw.scale(x, y, z); }
}

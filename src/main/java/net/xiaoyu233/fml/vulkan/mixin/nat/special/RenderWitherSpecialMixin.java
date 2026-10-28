package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderWither;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "special" (NR-20, template E): one redirect per (method, GL11 descriptor).
 * Charged/armoured pass (func_82417_a): texture-matrix ops and the scale through GlShim, which
 * selects Draw.uvMatrix() while mode is GL_TEXTURE.
 */
@Mixin(RenderWither.class)
public abstract class RenderWitherSpecialMixin {

    // ---------------------------------------------------------------- func_82417_a(EntityWither, int, float)I
    @Redirect(method = "func_82417_a(Lnet/minecraft/EntityWither;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private void fml$armour$blendFunc(int src, int dst) { GlShim.blendFunc(src, dst); }

    @Redirect(method = "func_82417_a(Lnet/minecraft/EntityWither;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$armour$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = "func_82417_a(Lnet/minecraft/EntityWither;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthMask(Z)V"))
    private void fml$armour$depthMask(boolean flag) { GlShim.depthMask(flag); }

    @Redirect(method = "func_82417_a(Lnet/minecraft/EntityWither;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$armour$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = "func_82417_a(Lnet/minecraft/EntityWither;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$armour$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "func_82417_a(Lnet/minecraft/EntityWither;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glLoadIdentity()V"))
    private void fml$armour$loadIdentity() { GlShim.loadIdentity(); }

    @Redirect(method = "func_82417_a(Lnet/minecraft/EntityWither;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glMatrixMode(I)V"))
    private void fml$armour$matrixMode(int mode) { GlShim.matrixMode(mode); }

    @Redirect(method = "func_82417_a(Lnet/minecraft/EntityWither;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$armour$scalef(float x, float y, float z) { GlShim.scalef(x, y, z); }

    @Redirect(method = "func_82417_a(Lnet/minecraft/EntityWither;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$armour$translatef(float x, float y, float z) { GlShim.translatef(x, y, z); }

    // ---------------------------------------------------------------- func_82415_a(EntityWither, float)V
    @Redirect(method = "func_82415_a(Lnet/minecraft/EntityWither;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$preRender$scalef(float x, float y, float z) { Draw.scale(x, y, z); }
}

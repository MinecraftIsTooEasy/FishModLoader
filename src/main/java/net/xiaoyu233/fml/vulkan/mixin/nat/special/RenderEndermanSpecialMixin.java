package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderEnderman;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "special" (NR-20, template E): one redirect per (method, GL11 descriptor).
 * Carried block (renderCarrying): matrix and colour through Draw, enable/disable through GlShim.
 */
@Mixin(RenderEnderman.class)
public abstract class RenderEndermanSpecialMixin {

    // ---------------------------------------------------------------- renderCarrying(EntityEnderman, float)V
    @Redirect(method = "renderCarrying(Lnet/minecraft/EntityEnderman;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$carry$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = "renderCarrying(Lnet/minecraft/EntityEnderman;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$carry$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = "renderCarrying(Lnet/minecraft/EntityEnderman;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$carry$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "renderCarrying(Lnet/minecraft/EntityEnderman;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$carry$popMatrix() { Draw.popPose(); }

    @Redirect(method = "renderCarrying(Lnet/minecraft/EntityEnderman;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$carry$pushMatrix() { Draw.pushPose(); }

    @Redirect(method = "renderCarrying(Lnet/minecraft/EntityEnderman;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$carry$rotatef(float a, float x, float y, float z) { Draw.rotate(a, x, y, z); }

    @Redirect(method = "renderCarrying(Lnet/minecraft/EntityEnderman;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$carry$scalef(float x, float y, float z) { Draw.scale(x, y, z); }

    @Redirect(method = "renderCarrying(Lnet/minecraft/EntityEnderman;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$carry$translatef(float x, float y, float z) { Draw.translate(x, y, z); }
}

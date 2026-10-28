package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderFish;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "special" (NR-20, template E): one redirect per (method, GL11 descriptor).
 * Fishing line (doRenderFishHook): the Tessellator line/quad calls stay untouched (NativeTess);
 * only the GL11 state toggles go through GlShim and the matrix ops through Draw.
 */
@Mixin(RenderFish.class)
public abstract class RenderFishSpecialMixin {

    // ---------------------------------------------------------------- doRenderFishHook(EntityFishHook, double x3, float x2)V
    @Redirect(method = "doRenderFishHook(Lnet/minecraft/EntityFishHook;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$hook$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = "doRenderFishHook(Lnet/minecraft/EntityFishHook;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$hook$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "doRenderFishHook(Lnet/minecraft/EntityFishHook;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$hook$popMatrix() { Draw.popPose(); }

    @Redirect(method = "doRenderFishHook(Lnet/minecraft/EntityFishHook;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$hook$pushMatrix() { Draw.pushPose(); }

    @Redirect(method = "doRenderFishHook(Lnet/minecraft/EntityFishHook;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$hook$rotatef(float a, float x, float y, float z) { Draw.rotate(a, x, y, z); }

    @Redirect(method = "doRenderFishHook(Lnet/minecraft/EntityFishHook;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$hook$scalef(float x, float y, float z) { Draw.scale(x, y, z); }

    @Redirect(method = "doRenderFishHook(Lnet/minecraft/EntityFishHook;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$hook$translatef(float x, float y, float z) { Draw.translate(x, y, z); }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderMooshroom;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "special" (NR-20, template E): one redirect per (method, GL11 descriptor).
 * Mushrooms on the back (renderMooshroomEquippedItems): matrix through Draw, enable/disable through GlShim.
 */
@Mixin(RenderMooshroom.class)
public abstract class RenderMooshroomSpecialMixin {

    // ---------------------------------------------------------------- renderMooshroomEquippedItems(EntityMooshroom, float)V
    @Redirect(method = "renderMooshroomEquippedItems(Lnet/minecraft/EntityMooshroom;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$shroom$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = "renderMooshroomEquippedItems(Lnet/minecraft/EntityMooshroom;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$shroom$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "renderMooshroomEquippedItems(Lnet/minecraft/EntityMooshroom;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$shroom$popMatrix() { Draw.popPose(); }

    @Redirect(method = "renderMooshroomEquippedItems(Lnet/minecraft/EntityMooshroom;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$shroom$pushMatrix() { Draw.pushPose(); }

    @Redirect(method = "renderMooshroomEquippedItems(Lnet/minecraft/EntityMooshroom;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$shroom$rotatef(float a, float x, float y, float z) { Draw.rotate(a, x, y, z); }

    @Redirect(method = "renderMooshroomEquippedItems(Lnet/minecraft/EntityMooshroom;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$shroom$scalef(float x, float y, float z) { Draw.scale(x, y, z); }

    @Redirect(method = "renderMooshroomEquippedItems(Lnet/minecraft/EntityMooshroom;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$shroom$translatef(float x, float y, float z) { Draw.translate(x, y, z); }
}

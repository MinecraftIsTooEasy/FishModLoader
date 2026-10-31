package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderIronGolem;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "special" (NR-20, template E): one redirect per (method, GL11 descriptor).
 * Flower in hand (renderIronGolemEquippedItems) and corpse rotation (rotateIronGolemCorpse).
 */
@Mixin(RenderIronGolem.class)
public abstract class RenderIronGolemSpecialMixin {

    // ---------------------------------------------------------------- renderIronGolemEquippedItems(EntityIronGolem, float)V
    @Redirect(method = "renderIronGolemEquippedItems(Lnet/minecraft/EntityIronGolem;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$held$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = "renderIronGolemEquippedItems(Lnet/minecraft/EntityIronGolem;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$held$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = "renderIronGolemEquippedItems(Lnet/minecraft/EntityIronGolem;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$held$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "renderIronGolemEquippedItems(Lnet/minecraft/EntityIronGolem;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$held$popMatrix() { Draw.popPose(); }

    @Redirect(method = "renderIronGolemEquippedItems(Lnet/minecraft/EntityIronGolem;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$held$pushMatrix() { Draw.pushPose(); }

    @Redirect(method = "renderIronGolemEquippedItems(Lnet/minecraft/EntityIronGolem;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$held$rotatef(float a, float x, float y, float z) { Draw.rotate(a, x, y, z); }

    @Redirect(method = "renderIronGolemEquippedItems(Lnet/minecraft/EntityIronGolem;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$held$scalef(float x, float y, float z) { Draw.scale(x, y, z); }

    @Redirect(method = "renderIronGolemEquippedItems(Lnet/minecraft/EntityIronGolem;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$held$translatef(float x, float y, float z) { Draw.translate(x, y, z); }

    // ---------------------------------------------------------------- rotateIronGolemCorpse(EntityIronGolem, float x3)V
    @Redirect(method = "rotateIronGolemCorpse(Lnet/minecraft/EntityIronGolem;FFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$corpse$rotatef(float a, float x, float y, float z) { Draw.rotate(a, x, y, z); }
}

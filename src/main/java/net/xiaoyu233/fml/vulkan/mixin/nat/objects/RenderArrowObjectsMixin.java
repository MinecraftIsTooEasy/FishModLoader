package net.xiaoyu233.fml.vulkan.mixin.nat.objects;

import net.minecraft.RenderArrow;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "objects" (NR-23): GL11 redirects, template A + E; method bodies stay untouched. */
@Mixin(RenderArrow.class)
public abstract class RenderArrowObjectsMixin {
    @Redirect(method = "renderArrow(Lnet/minecraft/EntityArrow;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$push() { Draw.pushPose(); }

    @Redirect(method = "renderArrow(Lnet/minecraft/EntityArrow;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$pop() { Draw.popPose(); }

    @Redirect(method = "renderArrow(Lnet/minecraft/EntityArrow;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) { Draw.translate(x, y, z); }

    @Redirect(method = "renderArrow(Lnet/minecraft/EntityArrow;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$rotate(float a, float x, float y, float z) { Draw.rotate(a, x, y, z); }

    @Redirect(method = "renderArrow(Lnet/minecraft/EntityArrow;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }

    @Redirect(method = "renderArrow(Lnet/minecraft/EntityArrow;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glNormal3f(FFF)V"))
    private void fml$normal(float x, float y, float z) { Draw.normal(x, y, z); }

    @Redirect(method = "renderArrow(Lnet/minecraft/EntityArrow;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "renderArrow(Lnet/minecraft/EntityArrow;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) { GlShim.disable(cap); }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderBiped;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "special" (NR-20, template E): GL11 calls of RenderBiped redirected in place to Draw/GlShim; method bodies, FML core field reads and TF hooks untouched. */
@Mixin(RenderBiped.class)
public abstract class RenderBipedSpecialMixin {
    @Redirect(method = "func_130006_a(Lnet/minecraft/EntityLiving;IF)I", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$func_130006_a_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    @Redirect(method = "func_130005_c(Lnet/minecraft/EntityLiving;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$func_130005_c_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    @Redirect(method = "func_130005_c(Lnet/minecraft/EntityLiving;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$func_130005_c_popMatrix() {
        Draw.popPose();
    }

    @Redirect(method = "func_130005_c(Lnet/minecraft/EntityLiving;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$func_130005_c_pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "func_130005_c(Lnet/minecraft/EntityLiving;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$func_130005_c_rotatef(float a, float x, float y, float z) {
        Draw.rotate(a, x, y, z);
    }

    @Redirect(method = "func_130005_c(Lnet/minecraft/EntityLiving;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$func_130005_c_scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "func_130005_c(Lnet/minecraft/EntityLiving;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$func_130005_c_translatef(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = "func_130013_c(Lnet/minecraft/EntityLiving;IF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$func_130013_c_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    @Redirect(method = "func_82422_c()V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$func_82422_c_translatef(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = "doRenderLiving(Lnet/minecraft/EntityLiving;DDDFF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$doRenderLiving_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }
}

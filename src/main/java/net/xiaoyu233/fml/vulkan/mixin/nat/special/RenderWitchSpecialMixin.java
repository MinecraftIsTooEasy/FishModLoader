package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderWitch;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "special" (NR-20, template E): GL11 calls of RenderWitch redirected in place to Draw/GlShim; method bodies, FML core field reads and TF hooks untouched. */
@Mixin(RenderWitch.class)
public abstract class RenderWitchSpecialMixin {
    @Redirect(method = "func_82409_b(Lnet/minecraft/EntityWitch;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$func_82409_b_scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "func_82410_b()V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$func_82410_b_translatef(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = "func_82411_a(Lnet/minecraft/EntityWitch;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$func_82411_a_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    @Redirect(method = "func_82411_a(Lnet/minecraft/EntityWitch;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$func_82411_a_popMatrix() {
        Draw.popPose();
    }

    @Redirect(method = "func_82411_a(Lnet/minecraft/EntityWitch;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$func_82411_a_pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "func_82411_a(Lnet/minecraft/EntityWitch;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$func_82411_a_rotatef(float a, float x, float y, float z) {
        Draw.rotate(a, x, y, z);
    }

    @Redirect(method = "func_82411_a(Lnet/minecraft/EntityWitch;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$func_82411_a_scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "func_82411_a(Lnet/minecraft/EntityWitch;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$func_82411_a_translatef(float x, float y, float z) {
        Draw.translate(x, y, z);
    }
}

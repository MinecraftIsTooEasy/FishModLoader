package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderPlayer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "special" (NR-20, template E): GL11 calls of RenderPlayer redirected in place to Draw/GlShim; method bodies, FML core field reads and TF hooks untouched. */
@Mixin(RenderPlayer.class)
public abstract class RenderPlayerSpecialMixin {
    @Redirect(method = "setArmorModel(Lnet/minecraft/AbstractClientPlayer;IF)I", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$setArmorModel_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    @Redirect(method = "func_130220_b(Lnet/minecraft/AbstractClientPlayer;IF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$func_130220_b_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    @Redirect(method = "renderSpecials(Lnet/minecraft/AbstractClientPlayer;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$renderSpecials_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    @Redirect(method = "renderSpecials(Lnet/minecraft/AbstractClientPlayer;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$renderSpecials_color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = "renderSpecials(Lnet/minecraft/AbstractClientPlayer;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$renderSpecials_popMatrix() {
        Draw.popPose();
    }

    @Redirect(method = "renderSpecials(Lnet/minecraft/AbstractClientPlayer;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$renderSpecials_pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "renderSpecials(Lnet/minecraft/AbstractClientPlayer;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$renderSpecials_rotatef(float a, float x, float y, float z) {
        Draw.rotate(a, x, y, z);
    }

    @Redirect(method = "renderSpecials(Lnet/minecraft/AbstractClientPlayer;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$renderSpecials_scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "renderSpecials(Lnet/minecraft/AbstractClientPlayer;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$renderSpecials_translatef(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = "renderPlayerScale(Lnet/minecraft/AbstractClientPlayer;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$renderPlayerScale_scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "rotatePlayer(Lnet/minecraft/AbstractClientPlayer;FFF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$rotatePlayer_rotatef(float a, float x, float y, float z) {
        Draw.rotate(a, x, y, z);
    }

    @Redirect(method = "func_130009_a(Lnet/minecraft/AbstractClientPlayer;DDDFF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$func_130009_a_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    @Redirect(method = "renderFirstPersonArm(Lnet/minecraft/EntityPlayer;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glAlphaFunc(IF)V"))
    private void fml$renderFirstPersonArm_alphaFunc(int f, float r) {
        GlShim.alphaFunc(f, r);
    }

    @Redirect(method = "renderFirstPersonArm(Lnet/minecraft/EntityPlayer;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private void fml$renderFirstPersonArm_blendFunc(int s, int d) {
        GlShim.blendFunc(s, d);
    }

    @Redirect(method = "renderFirstPersonArm(Lnet/minecraft/EntityPlayer;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$renderFirstPersonArm_color3f(float r, float g, float b) {
        Draw.color(r, g, b);
    }

    @Redirect(method = "renderFirstPersonArm(Lnet/minecraft/EntityPlayer;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$renderFirstPersonArm_color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = "renderFirstPersonArm(Lnet/minecraft/EntityPlayer;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthMask(Z)V"))
    private void fml$renderFirstPersonArm_depthMask(boolean f) {
        GlShim.depthMask(f);
    }

    @Redirect(method = "renderFirstPersonArm(Lnet/minecraft/EntityPlayer;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$renderFirstPersonArm_disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "renderFirstPersonArm(Lnet/minecraft/EntityPlayer;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$renderFirstPersonArm_enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "renderFirstPersonArm(Lnet/minecraft/EntityPlayer;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$renderFirstPersonArm_popMatrix() {
        Draw.popPose();
    }

    @Redirect(method = "renderFirstPersonArm(Lnet/minecraft/EntityPlayer;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$renderFirstPersonArm_pushMatrix() {
        Draw.pushPose();
    }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.container;

import net.minecraft.GuiAchievements;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "container": GuiAchievements GL11 calls redirected to GlShim (template E). */
@Mixin(GuiAchievements.class)
public abstract class GuiAchievementsContainerMixin {
    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$drawScreenEnable(int cap) { GlShim.enable(cap); }
    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$drawScreenDisable(int cap) { GlShim.disable(cap); }
    @Redirect(method = "genAchievementBackground(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$genAchievementBackgroundEnable(int cap) { GlShim.enable(cap); }
    @Redirect(method = "genAchievementBackground(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$genAchievementBackgroundDisable(int cap) { GlShim.disable(cap); }
    @Redirect(method = "genAchievementBackground(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthFunc(I)V"))
    private void fml$genAchievementBackgroundDepthFunc(int f) { GlShim.depthFunc(f); }
    @Redirect(method = "genAchievementBackground(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$genAchievementBackgroundPush() { Draw.pushPose(); }
    @Redirect(method = "genAchievementBackground(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$genAchievementBackgroundPop() { Draw.popPose(); }
    @Redirect(method = "genAchievementBackground(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$genAchievementBackgroundTranslate(float x, float y, float z) { Draw.translate(x, y, z); }
    @Redirect(method = "genAchievementBackground(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$genAchievementBackgroundColor(float r, float g, float b, float a) { GlShim.color4f(r, g, b, a); }
}

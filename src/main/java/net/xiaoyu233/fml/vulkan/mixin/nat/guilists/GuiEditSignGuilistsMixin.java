package net.xiaoyu233.fml.vulkan.mixin.nat.guilists;

import net.minecraft.GuiEditSign;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guilists": GuiEditSign.drawScreen matrix calls redirected to the native Draw state. */
@Mixin(GuiEditSign.class)
public abstract class GuiEditSignGuilistsMixin {
    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$pushPose() { Draw.pushPose(); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$popPose() { Draw.popPose(); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) { Draw.translate(x, y, z); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$rotate(float angle, float x, float y, float z) { Draw.rotate(angle, x, y, z); }
}

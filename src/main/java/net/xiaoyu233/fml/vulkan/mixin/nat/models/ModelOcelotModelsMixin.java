package net.xiaoyu233.fml.vulkan.mixin.nat.models;

import net.minecraft.ModelOcelot;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "models": template B, matrix calls of ModelOcelot.render redirected to Draw. Redirects only. */
@Mixin(ModelOcelot.class)
public abstract class ModelOcelotModelsMixin {
    @Redirect(method = "render(Lnet/minecraft/Entity;FFFFFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$push() { Draw.pushPose(); }

    @Redirect(method = "render(Lnet/minecraft/Entity;FFFFFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$pop() { Draw.popPose(); }

    @Redirect(method = "render(Lnet/minecraft/Entity;FFFFFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) { Draw.translate(x, y, z); }

    @Redirect(method = "render(Lnet/minecraft/Entity;FFFFFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }
}

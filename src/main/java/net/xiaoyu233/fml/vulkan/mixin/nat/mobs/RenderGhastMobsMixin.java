package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.minecraft.RenderGhast;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": RenderGhast.preRenderGhast matrix and colour calls go to Draw (template A). */
@Mixin(RenderGhast.class)
public abstract class RenderGhastMobsMixin {
    @Redirect(method = "preRenderGhast(Lnet/minecraft/EntityGhast;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }

    @Redirect(method = "preRenderGhast(Lnet/minecraft/EntityGhast;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color(float r, float g, float b, float a) { Draw.color(r, g, b, a); }
}

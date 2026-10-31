package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.minecraft.RenderMagmaCube;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": redirects the GL matrix call of {@link RenderMagmaCube} to {@link Draw} (template A). */
@Mixin(RenderMagmaCube.class)
public abstract class RenderMagmaCubeMobsMixin {
    @Redirect(method = "scaleMagmaCube(Lnet/minecraft/EntityMagmaCube;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }
}

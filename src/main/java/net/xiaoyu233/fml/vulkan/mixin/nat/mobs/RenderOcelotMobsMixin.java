package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.minecraft.RenderOcelot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": redirects the GL matrix call of {@link RenderOcelot} to {@link Draw} (template A). */
@Mixin(RenderOcelot.class)
public abstract class RenderOcelotMobsMixin {
    @Redirect(method = "preRenderOcelot(Lnet/minecraft/EntityOcelot;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }
}

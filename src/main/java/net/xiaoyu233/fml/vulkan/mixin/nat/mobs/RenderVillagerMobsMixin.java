package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.minecraft.RenderVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": redirects the GL matrix call of {@link RenderVillager} to {@link Draw} (template A). */
@Mixin(RenderVillager.class)
public abstract class RenderVillagerMobsMixin {
    @Redirect(method = "preRenderVillager(Lnet/minecraft/EntityVillager;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }
}

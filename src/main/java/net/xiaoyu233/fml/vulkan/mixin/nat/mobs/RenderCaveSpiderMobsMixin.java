package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.minecraft.RenderCaveSpider;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": GL11 scale in scaleSpider routed to the native pose stack. */
@Mixin(RenderCaveSpider.class)
public abstract class RenderCaveSpiderMobsMixin {
    @Redirect(method = "scaleSpider(Lnet/minecraft/EntityCaveSpider;F)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }
}

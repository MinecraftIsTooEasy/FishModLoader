package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.minecraft.RenderWolf;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": wolf shaking shade and collar tint, glColor3f routed to the native colour state. */
@Mixin(RenderWolf.class)
public abstract class RenderWolfMobsMixin {

    @Redirect(method = "func_82447_a(Lnet/minecraft/EntityWolf;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$color(float r, float g, float b) { Draw.color(r, g, b); }
}

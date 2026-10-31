package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.minecraft.RenderSheep;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": sheep wool colour tint, glColor3f routed to the native colour state. */
@Mixin(RenderSheep.class)
public abstract class RenderSheepMobsMixin {

    @Redirect(method = "setWoolColorAndRender(Lnet/minecraft/EntitySheep;IF)I",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor3f(FFF)V"))
    private void fml$color(float r, float g, float b) { Draw.color(r, g, b); }
}

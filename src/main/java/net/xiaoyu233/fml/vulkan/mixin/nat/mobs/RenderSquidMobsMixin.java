package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.minecraft.RenderSquid;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": GL11 translate/rotate in rotateSquidsCorpse routed through the native Draw state. */
@Mixin(RenderSquid.class)
public abstract class RenderSquidMobsMixin {
    @Redirect(method = "rotateSquidsCorpse(Lnet/minecraft/EntitySquid;FFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) { Draw.translate(x, y, z); }

    @Redirect(method = "rotateSquidsCorpse(Lnet/minecraft/EntitySquid;FFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$rotate(float a, float x, float y, float z) { Draw.rotate(a, x, y, z); }
}

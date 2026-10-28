package net.xiaoyu233.fml.vulkan.mixin.nat.mobs;

import net.minecraft.RenderBat;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "mobs": GL11 scale/translate calls in RenderBat routed through the native Draw state. */
@Mixin(RenderBat.class)
public abstract class RenderBatMobsMixin {

    @Redirect(method = "func_82442_a(Lnet/minecraft/EntityBat;F)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "func_82444_a(Lnet/minecraft/EntityBat;FFF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) {
        Draw.translate(x, y, z);
    }
}

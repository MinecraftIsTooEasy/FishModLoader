package net.xiaoyu233.fml.vulkan.mixin.nat.objects;

import net.minecraft.RenderEnderCrystal;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "objects" (NR-23): GL11 redirects, template A + E; method bodies stay untouched. */
@Mixin(RenderEnderCrystal.class)
public abstract class RenderEnderCrystalObjectsMixin {
    @Redirect(method = "doRenderEnderCrystal(Lnet/minecraft/EntityEnderCrystal;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$push() { Draw.pushPose(); }

    @Redirect(method = "doRenderEnderCrystal(Lnet/minecraft/EntityEnderCrystal;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$pop() { Draw.popPose(); }

    @Redirect(method = "doRenderEnderCrystal(Lnet/minecraft/EntityEnderCrystal;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) { Draw.translate(x, y, z); }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.objects;

import net.minecraft.RenderFallingSand;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "objects" (NR-23): GL11 redirects, template A + E; method bodies stay untouched. */
@Mixin(RenderFallingSand.class)
public abstract class RenderFallingSandObjectsMixin {
    @Redirect(method = "doRenderFallingSand(Lnet/minecraft/EntityFallingSand;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$push() { Draw.pushPose(); }

    @Redirect(method = "doRenderFallingSand(Lnet/minecraft/EntityFallingSand;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$pop() { Draw.popPose(); }

    @Redirect(method = "doRenderFallingSand(Lnet/minecraft/EntityFallingSand;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) { Draw.translate(x, y, z); }

    @Redirect(method = "doRenderFallingSand(Lnet/minecraft/EntityFallingSand;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "doRenderFallingSand(Lnet/minecraft/EntityFallingSand;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) { GlShim.disable(cap); }
}

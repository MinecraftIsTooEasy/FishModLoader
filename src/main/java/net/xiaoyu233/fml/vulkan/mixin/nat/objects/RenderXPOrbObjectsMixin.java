package net.xiaoyu233.fml.vulkan.mixin.nat.objects;

import net.minecraft.RenderXPOrb;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "objects" (NR-23): GL11 redirects, template A + E; method bodies stay untouched. */
@Mixin(RenderXPOrb.class)
public abstract class RenderXPOrbObjectsMixin {
    @Redirect(method = "renderTheXPOrb(Lnet/minecraft/EntityXPOrb;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$push() { Draw.pushPose(); }

    @Redirect(method = "renderTheXPOrb(Lnet/minecraft/EntityXPOrb;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$pop() { Draw.popPose(); }

    @Redirect(method = "renderTheXPOrb(Lnet/minecraft/EntityXPOrb;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) { Draw.translate(x, y, z); }

    @Redirect(method = "renderTheXPOrb(Lnet/minecraft/EntityXPOrb;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$rotate(float a, float x, float y, float z) { Draw.rotate(a, x, y, z); }

    @Redirect(method = "renderTheXPOrb(Lnet/minecraft/EntityXPOrb;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) { Draw.scale(x, y, z); }

    @Redirect(method = "renderTheXPOrb(Lnet/minecraft/EntityXPOrb;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = "renderTheXPOrb(Lnet/minecraft/EntityXPOrb;DDDFF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) { GlShim.disable(cap); }
}

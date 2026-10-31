package net.xiaoyu233.fml.vulkan.mixin.nat.tesr;

import net.minecraft.TileEntityEnderChestRenderer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "tesr": redirect-only mixin (template A + E). The original
 * renderEnderChest body is kept; each GL11 call is sent to Draw or GlShim.
 * The chest model and lid angle logic are untouched.
 */
@Mixin(TileEntityEnderChestRenderer.class)
public abstract class TileEntityEnderChestRendererTesrMixin {

    @Redirect(method = "renderEnderChest(Lnet/minecraft/TileEntityEnderChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "renderEnderChest(Lnet/minecraft/TileEntityEnderChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$popMatrix() {
        Draw.popPose();
    }

    @Redirect(method = "renderEnderChest(Lnet/minecraft/TileEntityEnderChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "renderEnderChest(Lnet/minecraft/TileEntityEnderChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "renderEnderChest(Lnet/minecraft/TileEntityEnderChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = "renderEnderChest(Lnet/minecraft/TileEntityEnderChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = "renderEnderChest(Lnet/minecraft/TileEntityEnderChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "renderEnderChest(Lnet/minecraft/TileEntityEnderChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$rotate(float degrees, float x, float y, float z) {
        Draw.rotate(degrees, x, y, z);
    }
}

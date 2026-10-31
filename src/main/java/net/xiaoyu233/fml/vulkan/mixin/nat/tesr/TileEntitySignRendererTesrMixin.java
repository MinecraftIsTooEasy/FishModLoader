package net.xiaoyu233.fml.vulkan.mixin.nat.tesr;

import net.minecraft.TileEntitySignRenderer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "tesr": redirect-only mixin (template E). The original
 * renderTileEntitySignAt body is kept, including the sign post / wall rotation logic and
 * the FontRenderer.drawString text pass (native via NR-16, not touched here). Each GL11 call is
 * sent to Draw or GlShim; glNormal3f keeps its rescale-normal semantic through Draw.normal.
 */
@Mixin(TileEntitySignRenderer.class)
public abstract class TileEntitySignRendererTesrMixin {

    @Redirect(method = "renderTileEntitySignAt(Lnet/minecraft/TileEntitySign;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "renderTileEntitySignAt(Lnet/minecraft/TileEntitySign;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$popMatrix() {
        Draw.popPose();
    }

    @Redirect(method = "renderTileEntitySignAt(Lnet/minecraft/TileEntitySign;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translatef(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = "renderTileEntitySignAt(Lnet/minecraft/TileEntitySign;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$rotatef(float angle, float x, float y, float z) {
        Draw.rotate(angle, x, y, z);
    }

    @Redirect(method = "renderTileEntitySignAt(Lnet/minecraft/TileEntitySign;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "renderTileEntitySignAt(Lnet/minecraft/TileEntitySign;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glNormal3f(FFF)V"))
    private void fml$normal3f(float x, float y, float z) {
        Draw.normal(x, y, z);
    }

    @Redirect(method = "renderTileEntitySignAt(Lnet/minecraft/TileEntitySign;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthMask(Z)V"))
    private void fml$depthMask(boolean flag) {
        GlShim.depthMask(flag);
    }

    @Redirect(method = "renderTileEntitySignAt(Lnet/minecraft/TileEntitySign;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }
}

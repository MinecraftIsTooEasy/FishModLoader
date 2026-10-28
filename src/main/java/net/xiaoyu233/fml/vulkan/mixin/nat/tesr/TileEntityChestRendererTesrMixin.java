package net.xiaoyu233.fml.vulkan.mixin.nat.tesr;

import net.minecraft.TileEntityChestRenderer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "tesr": redirect-only mixin (template E). The original
 * renderTileEntityChestAt body is kept, including the chest alignment, adjacent-chest,
 * christmas and lid-angle logic; each GL11 call is sent to Draw or GlShim.
 */
@Mixin(TileEntityChestRenderer.class)
public abstract class TileEntityChestRendererTesrMixin {

    @Redirect(method = "renderTileEntityChestAt(Lnet/minecraft/TileEntityChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "renderTileEntityChestAt(Lnet/minecraft/TileEntityChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$popMatrix() {
        Draw.popPose();
    }

    @Redirect(method = "renderTileEntityChestAt(Lnet/minecraft/TileEntityChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translatef(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = "renderTileEntityChestAt(Lnet/minecraft/TileEntityChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "renderTileEntityChestAt(Lnet/minecraft/TileEntityChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$rotatef(float angle, float x, float y, float z) {
        Draw.rotate(angle, x, y, z);
    }

    @Redirect(method = "renderTileEntityChestAt(Lnet/minecraft/TileEntityChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = "renderTileEntityChestAt(Lnet/minecraft/TileEntityChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "renderTileEntityChestAt(Lnet/minecraft/TileEntityChest;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }
}

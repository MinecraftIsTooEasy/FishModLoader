package net.xiaoyu233.fml.vulkan.mixin.nat.tesr;

import net.minecraft.TileEntityBeaconRenderer;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "tesr": redirect-only mixin (template E). The original
 * renderTileEntityBeaconAt body is kept; each GL11 call is sent to GlShim.
 * The beam still uses the game Tessellator, and the world-time animation is untouched.
 */
@Mixin(TileEntityBeaconRenderer.class)
public abstract class TileEntityBeaconRendererTesrMixin {

    @Redirect(method = "renderTileEntityBeaconAt(Lnet/minecraft/TileEntityBeacon;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexParameterf(IIF)V"))
    private void fml$texParameterf(int target, int pname, float param) {
        GlShim.texParameterf(target, pname, param);
    }

    @Redirect(method = "renderTileEntityBeaconAt(Lnet/minecraft/TileEntityBeacon;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "renderTileEntityBeaconAt(Lnet/minecraft/TileEntityBeacon;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "renderTileEntityBeaconAt(Lnet/minecraft/TileEntityBeacon;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthMask(Z)V"))
    private void fml$depthMask(boolean flag) {
        GlShim.depthMask(flag);
    }

    @Redirect(method = "renderTileEntityBeaconAt(Lnet/minecraft/TileEntityBeacon;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private void fml$blendFunc(int src, int dst) {
        GlShim.blendFunc(src, dst);
    }
}

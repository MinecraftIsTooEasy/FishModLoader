package net.xiaoyu233.fml.vulkan.mixin.nat.tesr;

import net.minecraft.TileEntityRendererPiston;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "tesr": redirect-only mixin (template E). The original
 * renderPiston body is kept; each GL11 call is sent to GlShim.
 * The piston block geometry still uses the game Tessellator and RenderBlocks,
 * and the piston progress logic is untouched.
 */
@Mixin(TileEntityRendererPiston.class)
public abstract class TileEntityRendererPistonTesrMixin {

    @Redirect(method = "renderPiston(Lnet/minecraft/TileEntityPiston;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private void fml$blendFunc(int src, int dst) {
        GlShim.blendFunc(src, dst);
    }

    @Redirect(method = "renderPiston(Lnet/minecraft/TileEntityPiston;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "renderPiston(Lnet/minecraft/TileEntityPiston;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "renderPiston(Lnet/minecraft/TileEntityPiston;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glShadeModel(I)V"))
    private void fml$shadeModel(int mode) {
        GlShim.shadeModel(mode);
    }
}

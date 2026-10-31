package net.xiaoyu233.fml.vulkan.mixin.nat.tesr;

import net.minecraft.TileEntitySkullRenderer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "tesr": redirect-only mixin (template E). The original bodies are kept.
 * func_82393_a is the only method with GL calls; renderTileEntitySkullAt (and the
 * item-in-hand path, which calls the same method) pick up these redirects too.
 * Texture selection by skull type and the ModelSkeletonHead.func_78088_a call are untouched.
 *
 * func_82393_a is an instance method, so all handlers are instance methods.
 */
@Mixin(TileEntitySkullRenderer.class)
public abstract class TileEntitySkullRendererTesrMixin {

    private static final String FUNC_82393_A = "func_82393_a(FFFIFILjava/lang/String;)V";

    @Redirect(method = FUNC_82393_A,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = FUNC_82393_A,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$popMatrix() {
        Draw.popPose();
    }

    @Redirect(method = FUNC_82393_A,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = FUNC_82393_A,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = FUNC_82393_A,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = FUNC_82393_A,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) {
        GlShim.enable(cap);
    }
}

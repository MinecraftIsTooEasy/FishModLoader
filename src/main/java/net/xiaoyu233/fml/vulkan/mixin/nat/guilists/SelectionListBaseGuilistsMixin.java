package net.xiaoyu233.fml.vulkan.mixin.nat.guilists;

import net.minecraft.SelectionListBase;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "guilists": redirect-only mixin for {@link SelectionListBase}.
 * The original method body (input handling, scrolling, Tessellator calls) is kept as is;
 * only the GL11 calls inside {@code func_96612_a(IIF)V} are redirected to the native layer.
 */
@Mixin(SelectionListBase.class)
public abstract class SelectionListBaseGuilistsMixin {

    @Redirect(method = "func_96612_a(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "func_96612_a(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "func_96612_a(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private void fml$blendFunc(int src, int dst) {
        GlShim.blendFunc(src, dst);
    }

    @Redirect(method = "func_96612_a(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glShadeModel(I)V"))
    private void fml$shadeModel(int mode) {
        GlShim.shadeModel(mode);
    }

    @Redirect(method = "func_96612_a(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }
}

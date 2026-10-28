package net.xiaoyu233.fml.vulkan.mixin.nat.guilists;

import net.minecraft.GuiScreenSelectLocation;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "guilists": redirect-only template D/E mixin for {@link GuiScreenSelectLocation}.
 * The original method bodies are kept; each GL11 call is sent to the native front end.
 */
@Mixin(GuiScreenSelectLocation.class)
public abstract class GuiScreenSelectLocationGuilistsMixin {

    // drawScreen(IIF)V

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private void fml$blendFunc(int src, int dst) { GlShim.blendFunc(src, dst); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glShadeModel(I)V"))
    private void fml$shadeModel(int mode) { GlShim.shadeModel(mode); }

    // func_104083_b(IIII)V (overlay background)

    @Redirect(method = "func_104083_b(IIII)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$colorOverlay(float r, float g, float b, float a) { Draw.color(r, g, b, a); }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.guilists;

import net.minecraft.GuiSlot;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guilists": GL11 calls in {@code GuiSlot} list drawing are redirected to {@code Draw} and {@code GlShim}. */
@Mixin(GuiSlot.class)
public abstract class GuiSlotGuilistsMixin {
    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glShadeModel(I)V"))
    private void fml$shadeModel(int mode) {
        GlShim.shadeModel(mode);
    }

    @Redirect(method = "drawDarkenedBackground(I)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disableDarkened(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "drawDarkenedBackground(I)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4fDarkened(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = "drawDarkenedBackground(I)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enableDarkened(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "drawDarkenedBackground(I)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private void fml$blendFuncDarkened(int src, int dst) {
        GlShim.blendFunc(src, dst);
    }

    @Redirect(method = "drawDarkenedBackground(I)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glShadeModel(I)V"))
    private void fml$shadeModelDarkened(int mode) {
        GlShim.shadeModel(mode);
    }

    @Redirect(method = "overlayBackground(IIII)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4fOverlay(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }
}

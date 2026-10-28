package net.xiaoyu233.fml.vulkan.mixin.nat.guisimple;

import net.minecraft.GuiConfirmOpenLink;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "guisimple" (haiku, template E): {@code drawScreen(IIF)V} keeps its
 * original body; each GL call is redirected in place. The link page texture is
 * bound by {@code bindTexture} just before the {@code glTexParameteri} calls, so
 * {@link GlShim#texParameteri} applies the filter to that bound texture.
 */
@Mixin(GuiConfirmOpenLink.class)
public abstract class GuiConfirmOpenLinkGuisimpleMixin {

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = "drawScreen(IIF)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexParameteri(III)V"))
    private void fml$texParameteri(int target, int pname, int param) { GlShim.texParameteri(target, pname, param); }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.guilists;

import net.minecraft.GuiMultiplayer;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "guilists": redirect-only migration of {@link GuiMultiplayer#drawScreen(int, int, float)}.
 * The original method body stays in place; its three GL11 state calls (glEnable x2, glShadeModel, glDisable)
 * are routed to {@link GlShim}. Input handling and game logic are untouched.
 */
@Mixin(GuiMultiplayer.class)
public abstract class GuiMultiplayerGuilistsMixin {

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glShadeModel(I)V"))
    private void fml$shadeModel(int mode) {
        GlShim.shadeModel(mode);
    }
}

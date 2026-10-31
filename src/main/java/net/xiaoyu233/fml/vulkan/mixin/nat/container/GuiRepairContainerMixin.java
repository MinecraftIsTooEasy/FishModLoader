package net.xiaoyu233.fml.vulkan.mixin.nat.container;

import net.minecraft.GuiRepair;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "container": GL11 calls redirected in place (templates D/E); bodies and other mixin hooks stay. */
@Mixin(GuiRepair.class)
public abstract class GuiRepairContainerMixin {
    @Redirect(method = "drawGuiContainerBackgroundLayer(FII)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private static void fml$bg_Color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = "drawGuiContainerForegroundLayer(II)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private static void fml$fg_Enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "drawGuiContainerForegroundLayer(II)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private static void fml$fg_Disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private static void fml$drawScreen_Disable(int cap) {
        GlShim.disable(cap);
    }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.container;

import net.minecraft.GuiContainerCreative;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** NR-19-e: GL11 redirects (templates D/E) for drawScreen, drawGuiContainerBackgroundLayer, renderCreativeTab. handleMouseClick/updateScreen untouched. */
@Mixin(GuiContainerCreative.class)
public abstract class GuiContainerCreativeContainerMixin {
    @Redirect(method = {"drawScreen", "drawGuiContainerBackgroundLayer"},
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = {"drawScreen", "renderCreativeTab"},
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = "renderCreativeTab",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) { GlShim.enable(cap); }
}

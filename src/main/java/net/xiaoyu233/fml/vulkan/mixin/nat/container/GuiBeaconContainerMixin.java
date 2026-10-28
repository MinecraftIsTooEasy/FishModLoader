package net.xiaoyu233.fml.vulkan.mixin.nat.container;

import net.minecraft.GuiBeacon;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** NR-19-e: GL11 redirect for drawGuiContainerBackgroundLayer (RenderHelper calls are native via NR-06). */
@Mixin(GuiBeacon.class)
public abstract class GuiBeaconContainerMixin {
    @Redirect(method = "drawGuiContainerBackgroundLayer",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }
}

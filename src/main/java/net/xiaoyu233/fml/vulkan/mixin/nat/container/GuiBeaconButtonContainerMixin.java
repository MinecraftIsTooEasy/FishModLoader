package net.xiaoyu233.fml.vulkan.mixin.nat.container;

import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** NR-19-e: GL11 redirect for drawButton. */
@Mixin(targets = "net.minecraft.GuiBeaconButton")
public abstract class GuiBeaconButtonContainerMixin {
    @Redirect(method = "drawButton",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }
}

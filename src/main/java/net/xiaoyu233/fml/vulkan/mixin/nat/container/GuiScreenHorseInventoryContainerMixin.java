package net.xiaoyu233.fml.vulkan.mixin.nat.container;

import net.minecraft.GuiScreenHorseInventory;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** NR-19: GuiScreenHorseInventory.drawGuiContainerBackgroundLayer (1 GL site). */
@Mixin(GuiScreenHorseInventory.class)
public abstract class GuiScreenHorseInventoryContainerMixin {
    @Redirect(method = "drawGuiContainerBackgroundLayer(FII)V",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }
}

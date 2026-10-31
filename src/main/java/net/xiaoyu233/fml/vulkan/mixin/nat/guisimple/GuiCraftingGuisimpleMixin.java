package net.xiaoyu233.fml.vulkan.mixin.nat.guisimple;

import net.minecraft.GuiCrafting;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guisimple": all three {@code glColor4f} calls of {@code GuiCrafting} (white reset, the MITE grey-out tint for unusable recipes, and the white reset after it) go through the typed state (template D redirect, the body stays). */
@Mixin(GuiCrafting.class)
public abstract class GuiCraftingGuisimpleMixin {
    @Redirect(method = "drawGuiContainerBackgroundLayer(FII)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }
}

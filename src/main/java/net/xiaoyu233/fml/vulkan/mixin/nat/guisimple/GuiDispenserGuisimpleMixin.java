package net.xiaoyu233.fml.vulkan.mixin.nat.guisimple;

import net.minecraft.GuiDispenser;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guisimple": the background-layer white tint of {@code GuiDispenser} goes through the typed state (template D redirect, the body stays). */
@Mixin(GuiDispenser.class)
public abstract class GuiDispenserGuisimpleMixin {
    @Redirect(method = "drawGuiContainerBackgroundLayer(FII)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }
}

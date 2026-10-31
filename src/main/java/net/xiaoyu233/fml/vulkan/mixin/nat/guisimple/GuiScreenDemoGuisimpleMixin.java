package net.xiaoyu233.fml.vulkan.mixin.nat.guisimple;

import net.minecraft.GuiScreenDemo;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guisimple": the background white tint of {@code GuiScreenDemo} goes through the typed state (template D redirect, the body stays). */
@Mixin(GuiScreenDemo.class)
public abstract class GuiScreenDemoGuisimpleMixin {
    @Redirect(method = "drawDefaultBackground()V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }
}

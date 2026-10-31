package net.xiaoyu233.fml.vulkan.mixin.nat.guisimple;

import net.minecraft.GuiButtonLanguage;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guisimple": the language button's white tint goes through the typed state (template D redirect, the body stays). */
@Mixin(GuiButtonLanguage.class)
public abstract class GuiButtonLanguageGuisimpleMixin {
    @Redirect(method = "drawButton(Lnet/minecraft/Minecraft;II)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }
}

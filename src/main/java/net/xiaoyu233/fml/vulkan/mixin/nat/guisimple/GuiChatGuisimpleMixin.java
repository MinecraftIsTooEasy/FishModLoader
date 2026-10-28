package net.xiaoyu233.fml.vulkan.mixin.nat.guisimple;

import net.minecraft.GuiChat;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guisimple": the lighting-off call of {@code GuiChat.drawScreen} goes through the typed state (template D redirect, the body stays). */
@Mixin(GuiChat.class)
public abstract class GuiChatGuisimpleMixin {
    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }
}

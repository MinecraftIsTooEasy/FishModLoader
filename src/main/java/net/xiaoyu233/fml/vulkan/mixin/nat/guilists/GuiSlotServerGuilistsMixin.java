package net.xiaoyu233.fml.vulkan.mixin.nat.guilists;

import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guilists": GL colour call in the server list slot redirected to the native render state; logic untouched. */
@Mixin(targets = "net.minecraft.GuiSlotServer")
public abstract class GuiSlotServerGuilistsMixin {

    @Redirect(method = "func_77247_d(IIIILnet/minecraft/Tessellator;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }
}

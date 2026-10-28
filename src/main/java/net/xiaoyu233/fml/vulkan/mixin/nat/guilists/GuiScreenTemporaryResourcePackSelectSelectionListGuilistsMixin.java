package net.xiaoyu233.fml.vulkan.mixin.nat.guilists;

import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guilists": GL colour calls in the resource pack list slot redirected to the native render state; logic untouched. */
@Mixin(targets = "net.minecraft.GuiScreenTemporaryResourcePackSelectSelectionList")
public abstract class GuiScreenTemporaryResourcePackSelectSelectionListGuilistsMixin {

    @Redirect(method = "drawSlot(IIIILnet/minecraft/Tessellator;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }
}

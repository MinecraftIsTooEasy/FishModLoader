package net.xiaoyu233.fml.vulkan.mixin.nat.guilists;

import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guilists": redirects GL state calls in GuiCreateFlatWorldListSlot's private drawing to the native state layer. */
@Mixin(targets = "net.minecraft.GuiCreateFlatWorldListSlot")
public abstract class GuiCreateFlatWorldListSlotGuilistsMixin {
    @Redirect(method = "func_82452_a(IILnet/minecraft/ItemStack;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "func_82452_a(IILnet/minecraft/ItemStack;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "func_82450_b(IIII)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.container;

import net.minecraft.GuiContainer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NR-19 (templates D/E): GuiContainer.drawScreen, drawSlotInventory, drawItemStack and the tooltip method
 * func_102021_a keep their MITE bodies; every GL11 call is redirected in place (same order, exit-state rule).
 * RenderHelper calls are native through RenderHelperCoreMixin.
 */
@Mixin(GuiContainer.class)
public abstract class GuiContainerContainerMixin {
    private static final String M_DRAW = "drawScreen(IIF)V";
    private static final String M_SLOT = "drawSlotInventory(Lnet/minecraft/Slot;)V";
    private static final String M_STACK = "drawItemStack(Lnet/minecraft/ItemStack;IILjava/lang/String;)V";
    private static final String M_TIP = "func_102021_a(Ljava/util/List;IIZ)V";

    @Redirect(method = {M_DRAW, M_SLOT, M_TIP},
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void fml$enable(int cap) { GlShim.enable(cap); }

    @Redirect(method = {M_DRAW, M_SLOT, M_TIP},
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private void fml$disable(int cap) { GlShim.disable(cap); }

    @Redirect(method = M_DRAW,
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glGetBoolean(I)Z"))
    private boolean fml$getBoolean(int cap) {
        if (cap == 2896) {
            return Draw.lightingEnabled();
        }
        GlShim.unhandled("glGetBoolean", cap);
        return false;
    }

    @Redirect(method = M_DRAW,
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) { Draw.color(r, g, b, a); }

    @Redirect(method = M_DRAW,
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$push() { Draw.pushPose(); }

    @Redirect(method = M_DRAW,
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$pop() { Draw.popPose(); }

    @Redirect(method = {M_DRAW, M_STACK},
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) { Draw.translate(x, y, z); }
}

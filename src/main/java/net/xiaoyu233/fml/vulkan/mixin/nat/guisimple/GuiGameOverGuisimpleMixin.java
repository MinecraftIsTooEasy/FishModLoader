package net.xiaoyu233.fml.vulkan.mixin.nat.guisimple;

import net.minecraft.GuiGameOver;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guisimple": the 2x 'You died' title push/scale/pop of {@code GuiGameOver} goes through the typed state (template D redirect, the body stays). */
@Mixin(GuiGameOver.class)
public abstract class GuiGameOverGuisimpleMixin {
    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$popMatrix() {
        Draw.popPose();
    }
}

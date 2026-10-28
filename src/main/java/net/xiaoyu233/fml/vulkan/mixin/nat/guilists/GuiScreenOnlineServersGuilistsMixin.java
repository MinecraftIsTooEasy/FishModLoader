package net.xiaoyu233.fml.vulkan.mixin.nat.guilists;

import net.minecraft.GuiScreenOnlineServers;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "guilists": GL calls in {@code GuiScreenOnlineServers} are routed through {@code Draw}. */
@Mixin(GuiScreenOnlineServers.class)
public abstract class GuiScreenOnlineServersGuilistsMixin {

    @Redirect(method = {
            "func_101001_e(IIII)V",
            "func_101006_d(IIII)V",
            "func_101008_c(IIII)V",
            "func_104039_b(IIIII)V",
            "func_130038_b(II)V"
    }, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void fml$color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = {
            "func_101001_e(IIII)V",
            "func_101006_d(IIII)V",
            "func_101008_c(IIII)V",
            "func_104039_b(IIIII)V",
            "func_130038_b(II)V"
    }, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$push() {
        Draw.pushPose();
    }

    @Redirect(method = {
            "func_101001_e(IIII)V",
            "func_101006_d(IIII)V",
            "func_101008_c(IIII)V",
            "func_104039_b(IIIII)V",
            "func_130038_b(II)V"
    }, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$pop() {
        Draw.popPose();
    }

    @Redirect(method = {
            "func_101001_e(IIII)V",
            "func_101006_d(IIII)V",
            "func_101008_c(IIII)V",
            "func_104039_b(IIIII)V"
    }, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$scale(float x, float y, float z) {
        Draw.scale(x, y, z);
    }
}

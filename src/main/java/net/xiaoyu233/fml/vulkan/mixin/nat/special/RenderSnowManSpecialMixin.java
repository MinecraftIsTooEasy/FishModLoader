package net.xiaoyu233.fml.vulkan.mixin.nat.special;

import net.minecraft.RenderSnowMan;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "special" (NR-20, template E): GL11 calls of RenderSnowMan redirected in place to Draw/GlShim; method bodies, FML core field reads and TF hooks untouched. */
@Mixin(RenderSnowMan.class)
public abstract class RenderSnowManSpecialMixin {
    @Redirect(method = "renderSnowmanPumpkin(Lnet/minecraft/EntitySnowman;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$renderSnowmanPumpkin_popMatrix() {
        Draw.popPose();
    }

    @Redirect(method = "renderSnowmanPumpkin(Lnet/minecraft/EntitySnowman;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$renderSnowmanPumpkin_pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "renderSnowmanPumpkin(Lnet/minecraft/EntitySnowman;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$renderSnowmanPumpkin_rotatef(float a, float x, float y, float z) {
        Draw.rotate(a, x, y, z);
    }

    @Redirect(method = "renderSnowmanPumpkin(Lnet/minecraft/EntitySnowman;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$renderSnowmanPumpkin_scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "renderSnowmanPumpkin(Lnet/minecraft/EntitySnowman;F)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$renderSnowmanPumpkin_translatef(float x, float y, float z) {
        Draw.translate(x, y, z);
    }
}

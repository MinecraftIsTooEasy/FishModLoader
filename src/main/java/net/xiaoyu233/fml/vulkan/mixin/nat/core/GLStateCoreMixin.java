package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import net.minecraft.GLState;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "core": the saved GL state of {@code GLState} (blend 3042, texture 3553 only) is read from and restored to {@code Draw}. */
@Mixin(GLState.class)
public abstract class GLStateCoreMixin {
    @Redirect(method = "<init>(I)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glIsEnabled(I)Z"))
    private static boolean fml$isEnabled(int cap) {
        return switch (cap) {
            case 3042 -> Draw.blendEnabled();
            case 3553 -> Draw.texturingEnabled();
            default -> false;
        };
    }

    @Redirect(method = "restore()V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private static void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "restore()V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private static void fml$disable(int cap) {
        GlShim.disable(cap);
    }
}

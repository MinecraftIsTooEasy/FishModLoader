package net.xiaoyu233.fml.vulkan.mixin.nat.core;

import net.minecraft.GLHelper;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "core": the push/pop toggles of {@code GLHelper} go through the typed state (template E redirects, the body stays). */
@Mixin(GLHelper.class)
public abstract class GLHelperCoreMixin {
    @Redirect(method = "enable(I)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private static void fml$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "disable(I)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private static void fml$disable(int cap) {
        GlShim.disable(cap);
    }
}

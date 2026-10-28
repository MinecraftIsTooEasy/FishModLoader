package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.OpenGlHelper;
import org.lwjgl.opengl.ARBMultitexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-texture-unit workaround for Intel GL drivers. It only has an effect on the phase-1 path ({@code fml.vulkan.native=false} or the
 * core area off): with the native renderer the core mixin of {@code OpenGlHelper} (priority 500, applied first) replaces
 * {@code setActiveTexture} by the typed {@code Draw.activeUnit}, which has no GL call left for these injectors to match, and there is no
 * client-side texture state to repair. The injectors are therefore optional ({@code require = 0}); they must not fail the config when the
 * native overwrite has removed their targets.
 */
@Mixin(OpenGlHelper.class)
public class FixRender {
    @Redirect(method = "setActiveTexture", require = 0, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/ARBMultitexture;glActiveTextureARB(I)V"))
    private static void injectSetClientTextureARB(int textureId) {
        if (GL11.glGetString(GL11.GL_VENDOR).toLowerCase().contains("intel"))
            ARBMultitexture.glClientActiveTextureARB(textureId);
    }

    @Inject(method = "setActiveTexture", require = 0, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL13;glActiveTexture(I)V", shift = At.Shift.BEFORE))
    private static void injectSetClientTexture(int textureId, CallbackInfo callbackInfo) {
        if (GL11.glGetString(GL11.GL_VENDOR).toLowerCase().contains("intel"))
            GL13.glClientActiveTexture(textureId);
    }
}

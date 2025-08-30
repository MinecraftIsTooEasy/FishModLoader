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

@Mixin(OpenGlHelper.class)
public class FixRender {
    @Redirect(method = "setActiveTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/ARBMultitexture;glActiveTextureARB(I)V"))
    private static void injectSetClientTextureARB(int textureId) {
        if (GL11.glGetString(GL11.GL_VENDOR).toLowerCase().contains("intel"))
            ARBMultitexture.glClientActiveTextureARB(textureId);
    }

    @Inject(method = "setActiveTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL13;glActiveTexture(I)V", shift = At.Shift.BEFORE))
    private static void injectSetClientTexture(int textureId, CallbackInfo callbackInfo) {
        if (GL11.glGetString(GL11.GL_VENDOR).toLowerCase().contains("intel"))
            GL13.glClientActiveTexture(textureId);
    }
}

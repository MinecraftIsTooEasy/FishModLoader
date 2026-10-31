package net.xiaoyu233.fml.vulkan.mixin.window;

import net.minecraft.GuiScreen;
import net.xiaoyu233.fml.vulkan.window.WindowManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The clipboard of the game goes through GLFW instead of {@code java.awt.Toolkit}: AWT cannot run beside GLFW on the macOS
 * main thread (FML runs AWT headless there, so the original code fails silently). Without a GLFW window the original code runs.
 */
@Mixin(GuiScreen.class)
public abstract class GuiScreenClipboardMixin {
    @Inject(method = "getClipboardString", at = @At("HEAD"), cancellable = true)
    private static void fml$getClipboardString(CallbackInfoReturnable<String> cir) {
        if (WindowManager.isGLFWReady()) {
            cir.setReturnValue(WindowManager.getClipboard());
        }
    }

    @Inject(method = "setClipboardString", at = @At("HEAD"), cancellable = true)
    private static void fml$setClipboardString(String text, CallbackInfo ci) {
        if (WindowManager.isGLFWReady()) {
            WindowManager.setClipboard(text);
            ci.cancel();
        }
    }
}

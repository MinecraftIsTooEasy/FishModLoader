package net.xiaoyu233.fml.vulkan.mixin.nat.window;

import net.minecraft.GameSettings;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "window": the vsync option goes to {@link NativeWindow} instead of {@code Display}. */
@Mixin(GameSettings.class)
public abstract class GameSettingsWindowMixin {
    @Redirect(method = "setOptionValue", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setVSyncEnabled(Z)V"))
    private void vk$setVSync(boolean enabled) {
        NativeWindow.setVSync(enabled);
    }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.minecraft;

import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "minecraft" (Input group): setIngameFocus asks NativeWindow instead of the Display shim.
 * clickMouse/runTick use only the NR-05 Keyboard/Mouse shims (InputManager) and are left untouched.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInputMinecraftMixin {
    @Redirect(method = "setIngameFocus", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;isActive()Z"))
    private boolean fml$nativeIsActive() {
        return NativeWindow.isActive();
    }
}

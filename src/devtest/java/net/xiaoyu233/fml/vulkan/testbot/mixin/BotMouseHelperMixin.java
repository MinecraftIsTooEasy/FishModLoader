package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.MouseHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;

/**
 * Dev only, test bot mode only: the bot must never grab or hide the system mouse, never warp the cursor and never turn real mouse
 * movement into camera movement (the user keeps working on the machine while the bot runs). The bot moves the player programmatically.
 */
@Mixin(MouseHelper.class)
public abstract class BotMouseHelperMixin {
    @Shadow
    public int deltaX;
    @Shadow
    public int deltaY;

    /** MouseHelper.grabMouseCursor does Mouse.setGrabbed(true) (hides the cursor and captures it). */
    @Inject(method = "grabMouseCursor", at = @At("HEAD"), cancellable = true)
    private void vkbot$noGrab(CallbackInfo ci) {
        if (TestBot.ENABLED) {
            this.deltaX = 0;
            this.deltaY = 0;
            ci.cancel();
        }
    }

    /** ungrabMouseCursor does Mouse.setCursorPosition(window centre): that would move the real cursor. */
    @Inject(method = "ungrabMouseCursor", at = @At("HEAD"), cancellable = true)
    private void vkbot$noUngrabWarp(CallbackInfo ci) {
        if (TestBot.ENABLED) {
            ci.cancel();
        }
    }

    /** Real mouse movement must not rotate the bot's camera. */
    @Inject(method = "mouseXYChange", at = @At("HEAD"), cancellable = true)
    private void vkbot$noLook(CallbackInfo ci) {
        if (TestBot.ENABLED) {
            this.deltaX = 0;
            this.deltaY = 0;
            ci.cancel();
        }
    }
}

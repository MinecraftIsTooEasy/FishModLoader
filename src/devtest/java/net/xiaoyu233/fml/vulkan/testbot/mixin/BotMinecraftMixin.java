package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.GuiScreen;
import net.minecraft.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;

/** Dev only: gives the test bot a tick hook and a "frame fully rendered" hook (right before MITE's own screenshot listener). */
@Mixin(Minecraft.class)
public abstract class BotMinecraftMixin {
    @Shadow
    public boolean inGameHasFocus;
    @Shadow
    private int leftClickCounter;

    @Shadow
    public abstract void displayGuiScreen(GuiScreen screen);

    @Shadow
    public abstract void closeImposedChat();

    /**
     * Test bot mode: Minecraft.setIngameFocus needs Display.isActive() (window focus) and grabs the mouse. The bot runs unfocused
     * and must not grab, so the in-game state is entered without either (see BotMouseHelperMixin for the cursor).
     */
    @Inject(method = "setIngameFocus", at = @At("HEAD"), cancellable = true)
    private void vkbot$focusWithoutGrab(CallbackInfo ci) {
        if (TestBot.ENABLED) {
            if (!this.inGameHasFocus) {
                this.inGameHasFocus = true;
                this.displayGuiScreen(null);
                this.closeImposedChat();
                this.leftClickCounter = 10000;
            }
            ci.cancel();
        }
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void vkbot$tick(CallbackInfo ci) {
        TestBot.onClientTick((Minecraft) (Object) this);
    }

    @Inject(method = "runGameLoop", at = @At(value = "INVOKE", target = "Lnet/minecraft/Minecraft;screenshotListener()V"))
    private void vkbot$postRender(CallbackInfo ci) {
        TestBot.onPostRender((Minecraft) (Object) this);
    }
}

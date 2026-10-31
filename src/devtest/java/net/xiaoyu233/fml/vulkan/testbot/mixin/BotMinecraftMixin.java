package net.xiaoyu233.fml.vulkan.testbot.mixin;

import net.minecraft.GuiScreen;
import net.minecraft.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.xiaoyu233.fml.vulkan.testbot.Frozen;
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

    /**
     * Frozen-world capture window (7.2): the client tick is skipped (cloud counter, renderer update counter, particles, texture animation, ... stand still); the bot's
     * tick hook still runs so waits and queued bot tasks keep working.
     */
    @Inject(method = "runTick", at = @At("HEAD"), cancellable = true)
    private void vkbot$pausedTick(CallbackInfo ci) {
        if (Frozen.clientTicksPaused()) {
            TestBot.onClientTick((Minecraft) (Object) this);
            ci.cancel();
        }
    }

    /** Frozen-world capture window: a constant clock for everything driven by {@code getSystemTime} (glint, title bounce, toasts). */
    @Inject(method = "getSystemTime", at = @At("HEAD"), cancellable = true)
    private static void vkbot$fakeClock(CallbackInfoReturnable<Long> cir) {
        if (Frozen.captureWindowOpen()) {
            cir.setReturnValue(Frozen.clockMs());
        }
    }

    /** Frozen-world capture window: the dev HUD's FPS / server load numbers (they change every second) show constants. */
    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void vkbot$pinHudNumbers(CallbackInfo ci) {
        if (Frozen.captureWindowOpen()) {
            Minecraft.last_fps = 60;
            Minecraft.last_fp10s = 600;
            net.minecraft.GuiIngame.server_load = -1;
            // the vignette brightness moves 1 % towards its target on EVERY rendered frame: start each frame of the window from the same value
            Minecraft mc = (Minecraft) (Object) this;
            if (mc.ingameGUI != null) {
                mc.ingameGUI.prevVignetteBrightness = 1.0F;
            }
        }
    }

    /** Frozen-world mode: MITE's "downtime processing" re-checks the light of random blocks in view with random raycasts (Math.random), which re-meshes sections for ever. */
    @Inject(method = "downtimeProcessing", at = @At("HEAD"), cancellable = true)
    private void vkbot$noDowntime(long milliseconds, CallbackInfo ci) {
        if (Frozen.worldFrozen()) {
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

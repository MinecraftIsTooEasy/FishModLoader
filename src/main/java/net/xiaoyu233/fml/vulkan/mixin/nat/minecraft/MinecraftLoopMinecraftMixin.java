package net.xiaoyu233.fml.vulkan.mixin.nat.minecraft;

import net.minecraft.AxisAlignedBB;
import net.minecraft.EntityRenderer;
import net.minecraft.GuiScreen;
import net.minecraft.IntegratedServer;
import net.minecraft.MemoryConnection;
import net.minecraft.Minecraft;
import net.minecraft.PlayerUsageSnooper;
import net.minecraft.Profiler;
import net.minecraft.RenderBlocks;
import net.minecraft.Timer;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.render.frame.FramePacer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "minecraft" (Loop group, NR-07): the game loop of ARCHITECTURE-NATIVE 4.1. Input is polled right before rendering, the
 * frame is presented before the limiter sleeps, and the deferred window actions run right after the present.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftLoopMinecraftMixin {
    @Shadow public net.minecraft.GameSettings gameSettings;
    @Shadow public net.minecraft.WorldClient theWorld;
    @Shadow public net.minecraft.EntityClientPlayerMP thePlayer;
    @Shadow public boolean isGamePaused;
    @Shadow public GuiScreen currentScreen;
    @Shadow public EntityRenderer entityRenderer;
    @Shadow public net.minecraft.GuiAchievement guiAchievement;
    @Shadow public boolean skipRenderWorld;
    @Shadow public net.minecraft.SoundManager sndManager;
    @Shadow @Final public Profiler mcProfiler;
    @Shadow public int displayWidth;
    @Shadow public int displayHeight;
    @Shadow public String debug;
    @Shadow private Timer timer;
    @Shadow private PlayerUsageSnooper usageSnooper;
    @Shadow private IntegratedServer theIntegratedServer;
    @Shadow private boolean fullscreen;
    @Shadow private static int debugFPS;
    @Shadow long debugUpdateTime;
    @Shadow int fpsCounter;
    @Shadow long prevFrameTime;

    @Shadow public abstract void shutdown();

    @Shadow public abstract void toggleFullscreen();

    @Shadow public abstract void runTick();

    @Shadow public abstract boolean isIntegratedServerRunning();

    @Shadow private void screenshotListener() {
    }

    @Shadow private void displayDebugInfo(long nanos) {
    }

    @Shadow private void resize(int w, int h) {
    }

    @Shadow private int getLimitFramerate() {
        return 0;
    }

    @Shadow public static long getSystemTime() {
        return 0L;
    }

    /**
     * Native replacement: native loop order (PAC-01): poll, render, present, deferred actions, limiter.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    private void runGameLoop() {
        this.gameSettings.difficulty = 3;
        this.gameSettings.gammaSetting = 0.0F;
        AxisAlignedBB.getAABBPool().cleanPool();
        if (this.theWorld != null) {
            this.theWorld.getWorldVec3Pool().clear();
        }

        this.mcProfiler.startSection("root");
        if (NativeWindow.isCloseRequested()) {
            this.shutdown();
        }

        if (this.isGamePaused && this.theWorld != null) {
            float partial = this.timer.renderPartialTicks;
            this.timer.updateTimer();
            this.timer.renderPartialTicks = partial;
        } else {
            this.timer.updateTimer();
        }

        long tickStart = System.nanoTime();
        this.mcProfiler.startSection("tick");
        int ticks = Math.min(this.timer.elapsedTicks, 10);
        for (int i = 0; i < ticks; i++) {
            this.runTick();
        }

        this.mcProfiler.endStartSection("preRenderErrors");
        long tickNanos = System.nanoTime() - tickStart;
        Frame.checkErrors("Pre render");
        RenderBlocks.fancyGrass = this.gameSettings.isFancyGraphicsEnabled();
        this.mcProfiler.endStartSection("sound");
        this.sndManager.setListener(this.thePlayer, this.timer.renderPartialTicks);
        if (!this.isGamePaused) {
            this.sndManager.func_92071_g();
        }

        this.mcProfiler.endSection();
        this.mcProfiler.startSection("render");
        this.mcProfiler.startSection("display");
        Draw.texturing(true);
        FramePacer.pollInput();

        if (this.thePlayer != null && (this.thePlayer.isEntityInsideOpaqueBlock() || this.thePlayer.isLockedInFirstPersonView())) {
            this.gameSettings.thirdPersonView = 0;
        }

        this.mcProfiler.endSection();
        if (!this.skipRenderWorld && !NativeWindow.isIconified()) {
            this.mcProfiler.endStartSection("gameRenderer");
            this.entityRenderer.updateCameraAndRender(this.timer.renderPartialTicks);
            this.mcProfiler.endSection();
        }

        this.mcProfiler.endSection();
        if (this.fullscreen && NativeWindow.leavesFullscreenOnFocusLoss() && !NativeWindow.isActive()) {
            this.toggleFullscreen();
        }

        if (this.gameSettings.showDebugInfo && this.gameSettings.showDebugProfilerChart) {
            if (!this.mcProfiler.profilingEnabled) {
                this.mcProfiler.clearProfiling();
            }

            this.mcProfiler.profilingEnabled = true;
            this.displayDebugInfo(tickNanos);
        } else {
            this.mcProfiler.profilingEnabled = false;
            this.prevFrameTime = System.nanoTime();
        }

        this.guiAchievement.updateAchievementWindow();
        this.mcProfiler.startSection("root");
        Thread.yield();

        this.screenshotListener();
        FramePacer.endFrame();
        FramePacer.runDeferredAfterPresent();

        if (!this.fullscreen && NativeWindow.wasResized()) {
            this.displayWidth = Math.max(NativeWindow.width(), 1);
            this.displayHeight = Math.max(NativeWindow.height(), 1);
            this.resize(this.displayWidth, this.displayHeight);
        }

        Frame.checkErrors("Post render");
        this.fpsCounter++;
        boolean wasPaused = this.isGamePaused;
        this.isGamePaused = Minecraft.isSingleplayer() && this.currentScreen != null && this.currentScreen.doesGuiPauseGame()
                && !this.theIntegratedServer.getPublic();
        if (this.isIntegratedServerRunning() && this.thePlayer != null && this.thePlayer.sendQueue != null && this.isGamePaused != wasPaused) {
            ((MemoryConnection) this.thePlayer.sendQueue.getNetManager()).setGamePaused(this.isGamePaused);
        }

        while (getSystemTime() >= this.debugUpdateTime + 1000L) {
            debugFPS = this.fpsCounter;
            this.debug = debugFPS + " fps, " + WorldRenderer.chunksUpdated + " chunk updates";
            WorldRenderer.chunksUpdated = 0;
            this.debugUpdateTime += 1000L;
            this.fpsCounter = 0;
            this.usageSnooper.addMemoryStatsToSnooper();
            if (!this.usageSnooper.isSnooperRunning()) {
                this.usageSnooper.startSnooper();
            }
        }

        this.mcProfiler.endSection();
        FramePacer.limit(this.getLimitFramerate() > 0 ? EntityRenderer.performanceToFps(this.getLimitFramerate()) : 0);
    }
}

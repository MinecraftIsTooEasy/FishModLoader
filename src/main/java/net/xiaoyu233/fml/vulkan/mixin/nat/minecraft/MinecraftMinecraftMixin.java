package net.xiaoyu233.fml.vulkan.mixin.nat.minecraft;

import net.minecraft.*;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.main.Main;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.frame.FramePacer;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshWorkers;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;
import org.lwjgl.opengl.DisplayMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "minecraft" (misc): GL error check, shutdown, fullscreen toggle, system time, snooper GL parts. */
@Mixin(Minecraft.class)
public abstract class MinecraftMinecraftMixin {
    @Shadow public int displayWidth;
    @Shadow public int displayHeight;
    @Shadow private boolean fullscreen;
    @Shadow private int tempDisplayWidth;
    @Shadow private int tempDisplayHeight;
    @Shadow public GameSettings gameSettings;
    @Shadow private StatFileWriter statFileWriter;
    @Shadow public SoundManager sndManager;
    @Shadow volatile boolean hasCrashed;
    @Shadow @Final private String launchedVersion;
    @Shadow private ResourcePackRepository mcResourcePackRepository;
    @Shadow private IntegratedServer theIntegratedServer;
    @Shadow public static int debugFPS;

    @Shadow public abstract ILogAgent getLogAgent();
    @Shadow public abstract void loadWorld(WorldClient world);
    @Shadow private void resize(int w, int h) { }
    @Shadow public abstract boolean isGuiOpen();

    /**
     * Native replacement: no GL error state under Vulkan.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    private void checkGLError(String label) {
        Frame.checkErrors(label);
    }

    /**
     * Native replacement: Vulkan teardown.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void shutdownMinecraftApplet() {
        try {
            this.statFileWriter.syncStats();
            this.getLogAgent().logInfo("Stopping!");

            try {
                this.loadWorld((WorldClient) null);
            } catch (Throwable ignored) {
            }

            try {
                GLAllocation.deleteTexturesAndDisplayLists();
            } catch (Throwable ignored) {
            }

            this.sndManager.cleanup();
        } finally {
            try {
                MeshWorkers.stop();
            } catch (Throwable ignored) {
            }
            NativeWindow.destroy();
            if (!this.hasCrashed) {
                ThreadedFileIOBase.reportErrorIfNotFinished();
                System.exit(0);
            }
        }

        System.gc();
    }

    /**
     * Native replacement: only records the request; applied after present.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void toggleFullscreen() {
        if (Main.is_MITE_DS) {
            this.fullscreen = false;
        } else {
            try {
                this.fullscreen = !this.fullscreen;
                // MITE: entering takes the desktop mode (updateDisplayMode), leaving restores the windowed size. The mode is set
                // before the switch, so exclusive fullscreen picks its video mode from it (WIN-01 applies both at the frame boundary).
                if (this.fullscreen) {
                    NativeWindow.setDisplayMode(NativeWindow.desktopDisplayMode());
                } else {
                    NativeWindow.setDisplayMode(new DisplayMode(Math.max(1, this.tempDisplayWidth), Math.max(1, this.tempDisplayHeight)));
                }
                NativeWindow.requestFullscreen(this.fullscreen);
                FramePacer.defer(() -> {
                    try {
                        int w;
                        int h;
                        if (this.fullscreen) {
                            w = NativeWindow.width();
                            h = NativeWindow.height();
                        } else {
                            w = this.tempDisplayWidth;
                            h = this.tempDisplayHeight;
                        }
                        this.displayWidth = Math.max(1, w);
                        this.displayHeight = Math.max(1, h);
                        if (this.isGuiOpen()) {
                            this.resize(this.displayWidth, this.displayHeight);
                        }
                        // this closure is the resize for the switch: consume the flag so the loop does not resize again this frame
                        NativeWindow.wasResized();
                        NativeWindow.setVSync(this.gameSettings.isVsyncEnabled());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Native replacement: no LWJGL Sys timer.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public static long getSystemTime() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * Native replacement: display frequency from NativeWindow.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void addServerStatsToSnooper(PlayerUsageSnooper s) {
        s.addData("fps", debugFPS);
        s.addData("texpack_name", this.mcResourcePackRepository.getResourcePackName());
        s.addData("vsync_enabled", this.gameSettings.isVsyncEnabled());
        s.addData("display_frequency", NativeWindow.desktopMode()[2]);
        s.addData("display_type", this.fullscreen ? "fullscreen" : "windowed");
        s.addData("run_time", (MinecraftServer.getSystemTimeMillis() - s.func_130105_g()) / 60L * 1000L);
        if (this.theIntegratedServer != null && this.theIntegratedServer.getPlayerUsageSnooper() != null) {
            s.addData("snooper_partner", this.theIntegratedServer.getPlayerUsageSnooper().getUniqueID());
        }
    }

    /**
     * Native replacement: report Vulkan strings under the GL keys.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void addServerTypeToSnooper(PlayerUsageSnooper s) {
        DeviceInfo info = RenderCore.deviceInfo();
        boolean ok = info != null;
        s.addData("opengl_version", ok ? "Vulkan " + (info.apiVersion() >> 22) + "." + ((info.apiVersion() >> 12) & 0x3FF)
                + " (" + info.deviceName() + ", " + info.driverInfo() + ")" : "Vulkan");
        s.addData("opengl_vendor", ok ? info.vendorName() : "unknown");
        s.addData("client_brand", ClientBrandRetriever.getClientModName());
        s.addData("launched_version", this.launchedVersion);
        s.addData("gl_caps[ARB_multitexture]", ok);
        s.addData("gl_caps[ARB_multisample]", ok);
        s.addData("gl_caps[ARB_texture_cube_map]", ok);
        s.addData("gl_caps[ARB_vertex_blend]", false);
        s.addData("gl_caps[ARB_matrix_palette]", false);
        s.addData("gl_caps[ARB_vertex_program]", ok);
        s.addData("gl_caps[ARB_vertex_shader]", ok);
        s.addData("gl_caps[ARB_fragment_program]", ok);
        s.addData("gl_caps[ARB_fragment_shader]", ok);
        s.addData("gl_caps[ARB_shader_objects]", ok);
        s.addData("gl_caps[ARB_vertex_buffer_object]", ok);
        s.addData("gl_caps[ARB_framebuffer_object]", ok);
        s.addData("gl_caps[ARB_pixel_buffer_object]", ok);
        s.addData("gl_caps[ARB_uniform_buffer_object]", ok);
        s.addData("gl_caps[ARB_texture_non_power_of_two]", ok);
        s.addData("gl_caps[gl_max_vertex_uniforms]", 4096);
        s.addData("gl_caps[gl_max_fragment_uniforms]", 4096);
        s.addData("gl_max_texture_size", ok ? info.maxImageDimension2D() : -1);
    }
}

package net.xiaoyu233.fml.vulkan.render.gui;

import java.util.Locale;
import net.minecraft.FontRenderer;
import net.minecraft.GuiIngame;
import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.SwapchainInfo;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.render.gpu.CommandStream;
import net.xiaoyu233.fml.vulkan.render.legacy.GLAudit;
import net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer;
import net.xiaoyu233.fml.vulkan.render.terrain.TerrainStatsView;
import net.xiaoyu233.fml.vulkan.window.WindowManager;

/**
 * The Vulkan information lines of the F3 screen (right side, below the memory lines of the debug text). Drawn by the native HUD
 * ({@code GuiIngameGuiMixin}) whenever {@code showDebugInfo} is on and {@code gui_mode == 0}, so they show up with the MITE dev-mode
 * F3 screen, with the survival F3 screen and with the RestoreF3 mod alike. {@code fml.vulkan.debugOverlay=false} turns them off.
 * <p>
 * Hot path: the text is rebuilt at most {@value #REFRESH_MS} ms apart (four times per second) into a fixed array of cached strings; a
 * frame in between only draws those strings. Mods that draw their own right-hand F3 rows publish the number of rows they use in the
 * system property {@value #PROP_RIGHT_ROWS}; the Vulkan block starts below them.
 */
public final class VulkanDebugOverlay {
    /** System property a mod sets to the number of 10 px rows it draws at the top of the right F3 column. */
    public static final String PROP_RIGHT_ROWS = "fml.f3.rightRows";
    private static final long REFRESH_NANOS = 250_000_000L;
    private static final int REFRESH_MS = 250;
    private static final int COLOR = 0xE0E0E0;
    private static final int COLOR_HEAD = 0x80D8FF;
    private static final int MAX_LINES = 14;

    private static final boolean ENABLED = VulkanConfig.debugOverlay();
    private static final String[] LINES = new String[MAX_LINES];
    private static final int[] WIDTHS = new int[MAX_LINES];
    private static int lineCount;
    private static long refreshAt;
    private static FontRenderer widthsFor;

    // refresh window
    private static long windowFrom;
    private static long windowNanos;
    private static long lastCommits;
    private static long lastCommitNanos;
    private static final long[] SUM_L = new long[4];
    private static final double[] SUM_D = new double[1];
    private static String staticGpu;
    private static String staticDriver;
    private static String staticApi;

    private static String rowsProp;
    private static int rowsParsed;

    private VulkanDebugOverlay() {
    }

    /** True when the overlay is on (config) and the game wants the F3 text. */
    public static boolean wanted(Minecraft mc) {
        return ENABLED && mc.gameSettings.showDebugInfo && mc.gameSettings.gui_mode == 0;
    }

    /**
     * Draws the block right aligned at the top right of the scaled screen. Render thread, inside the HUD pass.
     *
     * @param scaledWidth width of the scaled GUI
     */
    public static void draw(Minecraft mc, FontRenderer font, int scaledWidth) {
        if (!wanted(mc) || !RenderCore.isInitialized()) {
            return;
        }
        long now = System.nanoTime();
        if (now - refreshAt >= REFRESH_NANOS || widthsFor != font) {
            refresh(now, font, mc);
        }
        int y = 2 + 10 * (rightRows() + (Minecraft.inDevMode() && GuiIngame.server_load >= 0 ? 1 : 0));
        for (int i = 0; i < lineCount; i++) {
            font.drawStringWithShadow(LINES[i], scaledWidth - WIDTHS[i] - 2, y, i == 0 ? COLOR_HEAD : COLOR);
            y += 10;
        }
    }

    private static int rightRows() {
        String v = System.getProperty(PROP_RIGHT_ROWS);
        if (v != rowsProp) { // identity: the property string is replaced only when the mod publishes a new value
            rowsProp = v;
            int n = 0;
            if (v != null) {
                try {
                    n = Math.max(0, Math.min(40, Integer.parseInt(v.trim())));
                } catch (NumberFormatException e) {
                    n = 0;
                }
            }
            rowsParsed = n;
        }
        return rowsParsed;
    }

    private static void refresh(long now, FontRenderer font, Minecraft mc) {
        long dtNanos = refreshAt == 0L ? 0L : now - refreshAt;
        refreshAt = now;
        widthsFor = font;
        long frames = FrameStats.frameCount();
        FrameStats.summarize(windowFrom, SUM_L, SUM_D);
        windowFrom = frames;
        double secs = dtNanos / 1.0e9;
        long n = SUM_L[0];
        double renderedFps = secs > 0.0 ? n / secs : 0.0;
        double presentedFps = secs > 0.0 ? SUM_L[1] / secs : 0.0;
        double cpuMs = n > 0 ? SUM_L[2] / (n * 1.0e6) : 0.0;
        double gpuMs = SUM_L[3] > 0 ? SUM_D[0] / SUM_L[3] : -1.0;

        if (staticGpu == null) {
            buildStatic();
        }
        int k = 0;
        LINES[k++] = staticGpu;
        LINES[k++] = staticDriver;
        LINES[k++] = staticApi;
        StringBuilder sb = new StringBuilder(64);
        boolean vsync = SwapchainInfo.vsyncMode();
        LINES[k++] = sb.append("Present ").append(SwapchainInfo.presentMode()).append(", VSync ").append(vsync ? "on" : "off").toString();
        sb.setLength(0);
        LINES[k++] = sb.append("Swapchain ").append(RenderCore.swapchainImageCount()).append(" images, ")
                .append(RenderCore.framesInFlight()).append(" in flight").toString();
        sb.setLength(0);
        LINES[k++] = sb.append("Extent ").append(SwapchainInfo.width()).append('x').append(SwapchainInfo.height()).append(" fb ")
                .append(WindowManager.getFramebufferWidth()).append('x').append(WindowManager.getFramebufferHeight()).toString();
        sb.setLength(0);
        sb.append("Win ").append(WindowManager.getWindowWidth()).append('x').append(WindowManager.getWindowHeight()).append(" target ")
                .append(RenderCore.framebufferWidth()).append('x').append(RenderCore.framebufferHeight());
        LINES[k++] = sb.toString();
        sb.setLength(0);
        sb.append("FPS ").append(fmt0(renderedFps)).append(" rendered");
        if (Math.abs(renderedFps - presentedFps) > Math.max(1.0, renderedFps * 0.02)) {
            sb.append(" / ").append(fmt0(presentedFps)).append(" presented");
        }
        LINES[k++] = sb.toString();
        sb.setLength(0);
        sb.append("CPU ").append(fmt2(cpuMs)).append(" ms, GPU ");
        if (gpuMs >= 0.0) {
            sb.append(fmt2(gpuMs)).append(" ms");
        } else {
            sb.append("n/a");
        }
        LINES[k++] = sb.toString();
        sb.setLength(0);
        CommandStream.Stats st = CommandStream.lastFrameStats();
        LINES[k++] = sb.append("Draws ").append(st.drawCalls()).append(", pipeline binds ").append(st.pipelineBinds()).toString();
        sb.setLength(0);
        LINES[k++] = sb.append("Sections ").append(TerrainStatsView.sectionsVisible()).append('/').append(TerrainStatsView.sectionsTotal())
                .toString();
        sb.setLength(0);
        long commits = TerrainRenderer.sectionCommits();
        double perSec = lastCommitNanos != 0L && now > lastCommitNanos ? (commits - lastCommits) / ((now - lastCommitNanos) / 1.0e9) : 0.0;
        lastCommits = commits;
        lastCommitNanos = now;
        LINES[k++] = sb.append("Meshing ").append(TerrainRenderer.pendingSections()).append(" pending, ").append(fmt0(perSec))
                .append("/s committed").toString();
        sb.setLength(0);
        long vma = RenderCore.debugVmaBytes();
        sb.append("GPU mem ").append(vma >= 0 ? (vma >> 20) + " MB" : "n/a").append(", GL calls ").append(GLAudit.gameSiteCount());
        long fb = GLAudit.shimFallbacks();
        if (fb != 0L) {
            sb.append(" (+").append(fb).append(" shim)");
        }
        LINES[k++] = sb.toString();
        lineCount = k;
        for (int i = 0; i < k; i++) {
            WIDTHS[i] = font.getStringWidth(LINES[i]);
        }
    }

    private static void buildStatic() {
        DeviceInfo info = RenderCore.deviceInfo();
        if (info == null) {
            staticGpu = "Vulkan: no device";
            staticDriver = "";
            staticApi = "";
            return;
        }
        String type = switch (info.deviceType()) {
            case 1 -> "integrated";
            case 2 -> "discrete";
            case 3 -> "virtual";
            case 4 -> "cpu";
            default -> "other";
        };
        staticGpu = "Vulkan: " + info.deviceName() + " (" + info.vendorName() + ", " + type + ")";
        String drv = info.driverInfo();
        staticDriver = "Driver: " + (drv == null ? "?" : drv);
        int v = info.apiVersion();
        staticApi = String.format(Locale.ROOT, "API %d.%d.%d, MoltenVK: %s", v >> 22, (v >> 12) & 0x3FF, v & 0xFFF,
                info.isMoltenVK() ? "yes" + moltenVersion(drv) : "no");
    }

    /** " (1.4.0)" out of a driver text like "MoltenVK 1.4.0" ("" when none). */
    private static String moltenVersion(String driver) {
        if (driver == null) {
            return "";
        }
        int i = driver.toLowerCase(Locale.ROOT).indexOf("moltenvk");
        if (i < 0) {
            return "";
        }
        String rest = driver.substring(i + 8).trim();
        return rest.isEmpty() ? "" : " (" + rest + ")";
    }

    private static String fmt0(double v) {
        return Long.toString(Math.round(v));
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}

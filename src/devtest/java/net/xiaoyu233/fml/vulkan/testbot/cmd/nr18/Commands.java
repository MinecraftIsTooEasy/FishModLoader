package net.xiaoyu233.fml.vulkan.testbot.cmd.nr18;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import net.minecraft.GuiMainMenu;
import net.minecraft.Minecraft;
import net.minecraft.ScreenShotHelper;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.Readback;
import net.xiaoyu233.fml.vulkan.render.gpu.RenderTarget;
import net.xiaoyu233.fml.vulkan.render.screen.Panorama;
import net.xiaoyu233.fml.vulkan.render.screen.ScreenshotWriter;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/**
 * Devtest commands of work package NR-18 (owner: NR-18): {@code screenshotTest}, {@code panoramaTest}, {@code loadingTest}.
 * All use only the public API of the main source set plus MITE classes; scene work runs through {@link TestBot}.
 */
public final class Commands implements DevCommandProvider {
    private static final long CAPTURE_LIMIT_NANOS = 2_000_000L;

    @Override
    public String packageId() {
        return "NR-18";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new ScreenshotTest(), new PanoramaTest(), new LoadingTest());
    }

    // ------------------------------------------------------------------ helpers

    /** Synchronous readColor of the whole main framebuffer, RGBA bottom-up. Must run on the client/render thread. */
    private static byte[] readFrame(Minecraft mc) {
        int w = mc.displayWidth;
        int h = mc.displayHeight;
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
        RenderCore.readColor(0, 0, w, h, buf);
        byte[] out = new byte[w * h * 4];
        buf.get(0, out);
        return out;
    }

    private static long hash(byte[] data) {
        long h = 1125899906842597L;
        for (byte b : data) {
            h = 31 * h + b;
        }
        return h;
    }

    private static boolean uniform(byte[] rgba) {
        for (int i = 4; i < rgba.length; i += 4) {
            if (rgba[i] != rgba[0] || rgba[i + 1] != rgba[1] || rgba[i + 2] != rgba[2]) {
                return false;
            }
        }
        return true;
    }

    private static boolean ready(Report.Section s, String name) {
        if (!RenderCore.isInitialized() || TestBot.get() == null) {
            s.warn(name, "Vulkan backend or testbot not running");
            return false;
        }
        return true;
    }

    /**
     * Times the render-thread half of a screenshot readback exactly as {@code Screenshots} performs it: the
     * {@code Readback.readColorAsync} record call (in beforeFrameEnd) and the {@code ScreenshotWriter.submit} copy inside the
     * readback callback (render thread, a few frames later). The encode runs on the writer thread and is not timed.
     */
    private static final class ReadbackTiming implements RenderCore.FrameListener {
        private static final ReadbackTiming INSTANCE = new ReadbackTiming();
        private static final AtomicInteger ARMED = new AtomicInteger();
        private static final AtomicInteger DELIVERED = new AtomicInteger();
        private static final AtomicInteger SEQ = new AtomicInteger();
        private static final AtomicLong MAX_RECORD = new AtomicLong();
        private static final AtomicLong MAX_COPY = new AtomicLong();
        private static volatile String error;
        private static volatile File dir;
        private static volatile int width;
        private static volatile int height;
        private static boolean installed;

        static synchronized void install() {
            if (!installed) {
                RenderCore.addListener(INSTANCE);
                installed = true;
            }
        }

        static void reset(File outDir, int w, int h) {
            dir = outDir;
            width = w;
            height = h;
            ARMED.set(0);
            DELIVERED.set(0);
            MAX_RECORD.set(0L);
            MAX_COPY.set(0L);
            error = null;
        }

        @Override
        public void beforeFrameEnd() {
            int n = ARMED.getAndSet(0);
            for (int i = 0; i < n; i++) {
                try {
                    long t0 = System.nanoTime();
                    Readback.readColorAsync(RenderTarget.main(), 0, 0, width, height, buf -> {
                        long c0 = System.nanoTime();
                        ScreenshotWriter.submit(buf, width, height, new File(dir, "nr18-timing-" + SEQ.incrementAndGet() + ".png"));
                        MAX_COPY.accumulateAndGet(System.nanoTime() - c0, Math::max);
                        DELIVERED.incrementAndGet();
                    });
                    MAX_RECORD.accumulateAndGet(System.nanoTime() - t0, Math::max);
                } catch (Throwable e) {
                    error = String.valueOf(e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ (a) screenshot

    private static final class ScreenshotTest implements DevCommand {
        @Override
        public String name() {
            return "screenshotTest";
        }

        @Override
        public String description() {
            return "ScreenShotHelper.func_74292_a vs synchronous readColor of the same frame (exact), render-thread cost per capture (capture call, readback record, submit copy) < 2 ms";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            if (!ready(s, "screenshotTest")) {
                return;
            }
            TestBot bot = TestBot.get();
            Minecraft mc = bot.minecraft();
            bot.frames(5); // static scene: consecutive frames are identical
            String fileName = "nr18-screenshot-" + System.currentTimeMillis() + ".png";
            long[] nanos = new long[1];
            byte[][] reference = new byte[1][];
            String[] message = new String[1];
            bot.afterFrame(() -> {
                long t0 = System.nanoTime();
                message[0] = ScreenShotHelper.func_74292_a(mc.mcDataDir, fileName, mc.displayWidth, mc.displayHeight);
                nanos[0] = System.nanoTime() - t0;
                reference[0] = readFrame(mc);
                return null;
            });
            long maxCaptureNanos = nanos[0];
            // further capture() calls only to measure the call cost on the render thread
            for (int i = 0; i < 4; i++) {
                bot.frames(2);
                long[] n = new long[1];
                bot.afterFrame(() -> {
                    long t0 = System.nanoTime();
                    ScreenShotHelper.func_74292_a(mc.mcDataDir, null, mc.displayWidth, mc.displayHeight);
                    n[0] = System.nanoTime() - t0;
                    return null;
                });
                maxCaptureNanos = Math.max(maxCaptureNanos, n[0]);
            }

            // readback record + submit copy, measured where they really run (render thread)
            ReadbackTiming.install();
            File shotsDir = new File(mc.mcDataDir, "screenshots");
            shotsDir.mkdirs();
            ReadbackTiming.reset(shotsDir, mc.displayWidth, mc.displayHeight);
            for (int i = 0; i < 5; i++) {
                bot.afterFrame(() -> {
                    ReadbackTiming.ARMED.incrementAndGet();
                    return null;
                });
                bot.frames(2);
            }
            for (int i = 0; i < 60 && ReadbackTiming.DELIVERED.get() < 5 && ReadbackTiming.error == null; i++) {
                bot.frames(1);
            }
            bot.frames(4);
            ScreenshotWriter.drainForTest();
            long recordMax = ReadbackTiming.MAX_RECORD.get();
            long copyMax = ReadbackTiming.MAX_COPY.get();
            int delivered = ReadbackTiming.DELIVERED.get();
            String timingError = ReadbackTiming.error;
            File[] timingFiles = shotsDir.listFiles((d, name) -> name.startsWith("nr18-timing-"));
            if (timingFiles != null) {
                for (File f : timingFiles) {
                    f.delete();
                }
            }
            s.check(delivered == 5 && timingError == null, "5 timed readbacks delivered",
                    "delivered " + delivered + (timingError == null ? "" : ", error " + timingError));

            bot.frames(4);
            ScreenshotWriter.drainForTest();
            s.info("screenshot message", String.valueOf(message[0]));
            File png = new File(shotsDir, fileName);
            if (!png.isFile()) {
                s.fail("screenshot PNG written", "missing " + png);
                return;
            }
            BufferedImage img = ImageIO.read(png);
            int w = mc.displayWidth;
            int h = mc.displayHeight;
            s.check(img != null && img.getWidth() == w && img.getHeight() == h, "PNG size equals display size",
                    img == null ? "unreadable" : img.getWidth() + "x" + img.getHeight() + " vs " + w + "x" + h);
            if (img == null || img.getWidth() != w || img.getHeight() != h) {
                return;
            }
            long diff = 0;
            int maxDelta = 0;
            byte[] ref = reference[0];
            for (int y = 0; y < h; y++) {
                int rowBase = (h - 1 - y) * w * 4; // readColor is bottom-up
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    int o = rowBase + x * 4;
                    int d = Math.max(Math.abs(((rgb >> 16) & 255) - (ref[o] & 255)),
                            Math.max(Math.abs(((rgb >> 8) & 255) - (ref[o + 1] & 255)), Math.abs((rgb & 255) - (ref[o + 2] & 255))));
                    if (d != 0) {
                        diff++;
                        maxDelta = Math.max(maxDelta, d);
                    }
                }
            }
            s.check(diff == 0, "T0 PNG equals RenderCore.readColor exactly", diff + " differing pixels, max channel delta " + maxDelta);
            long worst = Math.max(maxCaptureNanos, Math.max(recordMax, copyMax));
            s.check(worst < CAPTURE_LIMIT_NANOS, "render-thread cost per screenshot < 2 ms",
                    String.format("worst %.3f ms (max over 5): capture() %.3f, readColorAsync record %.3f, submit copy %.3f",
                            worst / 1e6, maxCaptureNanos / 1e6, recordMax / 1e6, copyMax / 1e6));
            s.info("capture time", String.format("capture() max %.3f ms (first %.3f ms); readback record max %.3f ms; submit copy max %.3f ms",
                    maxCaptureNanos / 1e6, nanos[0] / 1e6, recordMax / 1e6, copyMax / 1e6));
            String failure = null;
            while ((failure = ScreenshotWriter.pollFailure()) != null) {
                s.fail("screenshot writer failure", failure);
            }
        }
    }

    // ------------------------------------------------------------------ (b) panorama

    private static final class PanoramaTest implements DevCommand {
        @Override
        public String name() {
            return "panoramaTest";
        }

        @Override
        public String description() {
            return "GuiMainMenu with fixed panoramaTimer: partial 0 vs 0.3 vs 0.25 quantisation (each hash from a real redraw), panorama cache key checks, menu FPS over 300 frames";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            if (!ready(s, "panoramaTest")) {
                return;
            }
            TestBot bot = TestBot.get();
            Minecraft mc = bot.minecraft();
            GuiMainMenu[] menu = new GuiMainMenu[1];
            net.minecraft.GuiScreen[] previous = new net.minecraft.GuiScreen[1];
            bot.client(() -> {
                previous[0] = mc.currentScreen;
                menu[0] = new GuiMainMenu();
                mc.displayGuiScreen(menu[0]);
            });
            try {
                bot.frames(10);
                Field timer = GuiMainMenu.class.getDeclaredField("panoramaTimer");
                timer.setAccessible(true);

                // T1: fixed timer, partial 0, both hashes come from a real redraw (cache released first)
                long t1a = drawFixed(bot, mc, menu[0], timer, 100, 0.0F, true);
                long t1b = drawFixed(bot, mc, menu[0], timer, 100, 0.0F, true);
                s.check(t1a == t1b, "T1 panorama redraw (timer 100, partial 0) is reproducible", Long.toHexString(t1a) + " vs " + Long.toHexString(t1b));

                // T2: quarter-step quantisation, every hash from a real redraw
                long p25 = drawFixed(bot, mc, menu[0], timer, 100, 0.25F, true);
                long p30 = drawFixed(bot, mc, menu[0], timer, 100, 0.3F, true);
                long p00 = drawFixed(bot, mc, menu[0], timer, 100, 0.0F, true);
                s.check(Panorama.quantize(0.3F) == Panorama.quantize(0.25F), "quantize(0.3) == quantize(0.25)",
                        Panorama.quantize(0.3F) + " vs " + Panorama.quantize(0.25F));
                s.check(p25 == p30, "T2 partial 0.3 redraws identically to 0.25", Long.toHexString(p25) + " vs " + Long.toHexString(p30));
                s.check(p00 != p25, "T2 partial 0 differs from partial 0.25", Long.toHexString(p00) + " vs " + Long.toHexString(p25));
                s.info("hashes", "p0=" + Long.toHexString(p00) + " p0.25=" + Long.toHexString(p25) + " p0.3=" + Long.toHexString(p30));

                // T3: cache key is the panoramaTimer only (design 3.11).
                boolean[] key = bot.afterFrame(() -> {
                    Panorama.release();
                    timer.setInt(menu[0], 100);
                    menu[0].drawScreen(0, 0, 0.25F);
                    return new boolean[] {Panorama.needsRedraw(100), Panorama.needsRedraw(101)};
                });
                s.check(!key[0], "T3 cache valid after a redraw (timer 100); the cache key is the timer only", "needsRedraw=" + key[0]);
                s.check(key[1], "T3 a new panoramaTimer invalidates the cache", "needsRedraw=" + key[1]);

                // T4: redraw rate with a frozen timer. Each frame decides via needsRedraw before drawing, so a redraw is counted for real.
                // Only the first frame after release may redraw; the other 59 must hit the cache.
                AtomicInteger redraws = new AtomicInteger();
                bot.client(() -> Panorama.release());
                for (int i = 0; i < 60; i++) {
                    bot.afterFrame(() -> {
                        timer.setInt(menu[0], 100);
                        if (Panorama.needsRedraw(100)) {
                            redraws.incrementAndGet();
                        }
                        menu[0].drawScreen(0, 0, 0.0F);
                        return null;
                    });
                }
                s.check(redraws.get() == 1, "T4 frozen panorama redraws once, then hits the cache (60 frames)",
                        "redraw frames " + redraws.get());

                // menu FPS over 300 frames (informational)
                long start = System.nanoTime();
                bot.frames(300);
                long elapsed = System.nanoTime() - start;
                s.info("menu FPS (300 frames)", String.format("%.1f fps", 300 / (elapsed / 1e9)));
            } finally {
                bot.client(() -> mc.displayGuiScreen(previous[0]));
            }
        }

        private static long drawFixed(TestBot bot, Minecraft mc, GuiMainMenu menu, Field timer, int timerValue, float partial, boolean fresh)
                throws Exception {
            bot.frames(2);
            return bot.afterFrame(() -> {
                if (fresh) {
                    Panorama.release();
                }
                timer.setInt(menu, timerValue);
                menu.drawScreen(0, 0, partial);
                return hash(readFrame(mc));
            });
        }
    }

    // ------------------------------------------------------------------ (c) loading

    private static final class LoadingTest implements DevCommand {
        @Override
        public String name() {
            return "loadingTest";
        }

        @Override
        public String description() {
            return "LoadingScreenRenderer.setLoadingProgress(50) after a reset draws the standalone loading frame (progress bar), reproducible";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            if (!ready(s, "loadingTest")) {
                return;
            }
            TestBot bot = TestBot.get();
            Minecraft mc = bot.minecraft();
            int percent = args.length > 0 ? Integer.parseInt(args[0]) : 50;
            AtomicLong[] hashes = {new AtomicLong(), new AtomicLong()};
            boolean[] flat = new boolean[2];
            for (int i = 0; i < 2; i++) {
                int run = i;
                bot.frames(2);
                bot.afterFrame(() -> {
                    mc.loadingScreen.resetProgressAndMessage("NR-18 loading test");
                    try {
                        Thread.sleep(150); // setLoadingProgress is throttled to one draw per 100 ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    mc.loadingScreen.setLoadingProgress(percent);
                    byte[] px = readFrame(mc);
                    hashes[run].set(hash(px));
                    flat[run] = uniform(px);
                    return null;
                });
            }
            s.check(!flat[0], "loading frame is not a flat colour", "uniform framebuffer after setLoadingProgress(" + percent + ")");
            s.check(hashes[0].get() == hashes[1].get(), "loading frame (" + percent + "%) is reproducible",
                    Long.toHexString(hashes[0].get()) + " vs " + Long.toHexString(hashes[1].get()));
            bot.frames(3);
            bot.screenshot(s, "nr18-loading-" + percent);
        }
    }
}

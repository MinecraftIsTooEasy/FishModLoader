package net.xiaoyu233.fml.vulkan.testbot.cmd.nr29;

import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;

/** Devtest commands of work package NR-29: {@code presentTest} (presentation path switching and throughput). */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-29";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new PresentTest());
    }

    /**
     * vsync off -> on -> off (macOS: layer presenter -> swapchain FIFO -> layer presenter), resizes and fullscreen toggles with the active
     * path, and the presented rate of each phase (RenderCore.presentCount over wall time). Args: {@code [rounds=6]}.
     */
    private static final class PresentTest implements DevCommand {
        @Override
        public String name() {
            return "presentTest";
        }

        @Override
        public String description() {
            return "presentation path switching (vsync toggle, resize, fullscreen) and presented FPS per phase: presentTest [rounds]";
        }

        @Override
        public void run(Report.Section s, String[] args) throws Exception {
            int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 6;
            if (!NativeWindow.isCreated()) {
                s.fail("presentTest", "no window");
                return;
            }
            measure(s, "start");
            for (int i = 0; i < rounds; i++) {
                boolean vsync = i % 2 == 0;
                call(() -> {
                    NativeWindow.setVSync(vsync);
                    return null;
                });
                frames(10);
                double fps = measure(s, "vsync " + (vsync ? "on" : "off") + " #" + i);
                String path = call(RenderCore::presentationName);
                if (vsync) {
                    s.check(fps < 200, "vsync on #" + i + " is display paced (" + path + ")", String.format(Locale.ROOT, "%.0f presents/s", fps));
                } else {
                    s.check(fps > 200, "vsync off #" + i + " is uncapped (" + path + ")", String.format(Locale.ROOT, "%.0f presents/s", fps));
                }
            }
            int[] start = call(() -> new int[] {NativeWindow.width(), NativeWindow.height()});
            for (int i = 0; i < rounds; i++) {
                int w = start[0] + (i % 2 == 0 ? 160 : -80);
                int h = start[1] + (i % 2 == 0 ? 90 : -45);
                call(() -> {
                    NativeWindow.setDisplayMode(new org.lwjgl.opengl.DisplayMode(w, h));
                    return null;
                });
                frames(6);
                measure(s, "resize " + w + "x" + h);
            }
            call(() -> {
                NativeWindow.setDisplayMode(new org.lwjgl.opengl.DisplayMode(start[0], start[1]));
                return null;
            });
            frames(6);
            for (int i = 0; i < 2; i++) {
                boolean fs = i == 0;
                call(() -> {
                    NativeWindow.requestFullscreen(fs);
                    return null;
                });
                frames(30);
                measure(s, "fullscreen " + fs);
            }
            frames(10);
            double end = measure(s, "end");
            s.check(end > 200, "uncapped again at the end (" + call(RenderCore::presentationName) + ")", String.format(Locale.ROOT, "%.0f presents/s", end));
        }

        private static double measure(Report.Section s, String phase) throws Exception {
            long p0 = RenderCore.presentCount();
            long t0 = System.nanoTime();
            Thread.sleep(1500);
            long p1 = RenderCore.presentCount();
            double fps = (p1 - p0) / ((System.nanoTime() - t0) / 1e9);
            String path = call(RenderCore::presentationName);
            s.info(phase, String.format(Locale.ROOT, "%.0f presented frames/s via %s, %d images", fps, path, call(RenderCore::swapchainImageCount)));
            return fps;
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static final Queue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();
    private static boolean installed;

    private static synchronized void install() {
        if (!installed) {
            installed = true;
            RenderCore.addListener(new RenderCore.FrameListener() {
                @Override
                public void onFrameBegin() {
                    Runnable r;
                    while ((r = QUEUE.poll()) != null) {
                        r.run();
                    }
                }
            });
        }
    }

    private static <T> T call(Callable<T> c) throws Exception {
        install();
        CompletableFuture<T> f = new CompletableFuture<>();
        QUEUE.add(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10_000, TimeUnit.MILLISECONDS);
    }

    private static void frames(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            call(() -> null);
        }
    }
}

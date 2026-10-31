package net.xiaoyu233.fml.vulkan.render.screen;

import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.Readback;
import net.xiaoyu233.fml.vulkan.render.gpu.RenderTarget;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Screenshot capture through the native readback (1.13). Contract: {@link #capture} chooses the file name synchronously (MITE naming),
 * records {@code Readback.readColorAsync(MAIN, ...)} at the end of the frame's drawing (deferred to {@code beforeFrameEnd}, after the
 * toast and before present), returns "Saved screenshot as NAME" at once; the callback copies the BGRA bottom-up buffer to a worker
 * ({@link ScreenshotWriter}) which flips, builds the BufferedImage and writes the PNG.
 * Errors are queued in {@link ScreenshotWriter#pollFailure()} and reported in chat on the next tick.
 *
 * <p>Render-thread cost: the screenshots directory is created and listed once per directory (first capture, or after the game
 * directory changes); later names are chosen from that in-memory set, so a capture does no filesystem calls.
 */
public final class Screenshots {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH.mm.ss");
    /** Names in flight: chosen by capture, not yet released by the writer. */
    private static final Set<String> RESERVED = new HashSet<>();
    /** Names known to be taken in {@link #primedDir}: the directory listing at priming time plus every name chosen since. */
    private static final Set<String> TAKEN = new HashSet<>();
    private static String primedDir;
    private static final ArrayList<Request> QUEUE = new ArrayList<>();

    private record Request(File file, int w, int h) {
    }

    private static final RenderCore.FrameListener LISTENER = new RenderCore.FrameListener() {
        @Override
        public void onFrameBegin() {
            String failure = ScreenshotWriter.pollFailure();
            if (failure != null) {
                try {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null && mc.ingameGUI != null) {
                        mc.ingameGUI.getChatGUI().printChatMessage(failure);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void beforeFrameEnd() {
            if (QUEUE.isEmpty()) {
                return;
            }
            Request[] reqs = QUEUE.toArray(new Request[0]);
            QUEUE.clear();
            for (Request r : reqs) {
                try {
                    Readback.readColorAsync(RenderTarget.main(), 0, 0, r.w(), r.h(), buf -> {
                        try {
                            ScreenshotWriter.submit(buf, r.w(), r.h(), r.file());
                        } catch (Throwable e) {
                            release(r.file());
                            ScreenshotWriter.fail(e);
                        }
                    });
                } catch (Throwable e) {
                    release(r.file());
                    ScreenshotWriter.fail(e);
                }
            }
        }
    };

    private Screenshots() {
    }

    /** Returns the MITE chat message. */
    public static String capture(File gameDir, String fileNameOrNull, int width, int height) {
        try {
            File dir = new File(gameDir, "screenshots");
            File file = chooseFile(dir, fileNameOrNull);
            // addListener is idempotent; checking a local flag would miss a listener array reset by RenderCore.destroyAll()
            RenderCore.addListener(LISTENER);
            QUEUE.add(new Request(file, width, height));
            return "Saved screenshot as " + file.getName();
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed to save: " + e;
        }
    }

    /** MITE file naming: yyyy-MM-dd_HH.mm.ss[_n].png, first free name in {@code dir}, also reserving names of queued writes. */
    static File chooseFile(File screenshotsDir, String fileNameOrNull) {
        if (fileNameOrNull != null) {
            return new File(screenshotsDir, fileNameOrNull);
        }
        String stamp = LocalDateTime.now().format(STAMP);
        synchronized (RESERVED) {
            prime(screenshotsDir);
            for (int n = 1; ; n++) {
                String name = stamp + (n == 1 ? "" : "_" + n) + ".png";
                if (!TAKEN.contains(name)) {
                    TAKEN.add(name);
                    RESERVED.add(new File(screenshotsDir, name).getPath());
                    return new File(screenshotsDir, name);
                }
            }
        }
    }

    /** Creates the directory and lists its names once per directory; must be called holding the RESERVED lock. */
    private static void prime(File screenshotsDir) {
        String path = screenshotsDir.getPath();
        if (path.equals(primedDir)) {
            return;
        }
        screenshotsDir.mkdir();
        TAKEN.clear();
        String[] names = screenshotsDir.list();
        if (names != null) {
            for (String n : names) {
                TAKEN.add(n);
            }
        }
        primedDir = path;
    }

    static void release(File f) {
        synchronized (RESERVED) {
            RESERVED.remove(f.getPath());
        }
    }
}

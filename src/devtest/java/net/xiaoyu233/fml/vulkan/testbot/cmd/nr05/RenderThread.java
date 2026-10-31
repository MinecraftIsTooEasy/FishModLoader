package net.xiaoyu233.fml.vulkan.testbot.cmd.nr05;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

/** Runs work on the render thread at the start of a frame (the commands run on the bot thread, GLFW and the window are render thread only). */
final class RenderThread {
    private static final Queue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();
    private static boolean installed;

    private RenderThread() {
    }

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

    static <T> T call(Callable<T> c, long timeoutMs) throws Exception {
        install();
        CompletableFuture<T> f = new CompletableFuture<>();
        QUEUE.add(() -> {
            try {
                f.complete(c.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    static void run(Runnable r, long timeoutMs) throws Exception {
        call(() -> {
            r.run();
            return null;
        }, timeoutMs);
    }

    /** Lets {@code n} frames pass (each present runs the frame begin listeners once). */
    static void frames(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            call(() -> null, 10_000);
        }
    }
}

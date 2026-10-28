package net.xiaoyu233.fml.vulkan.testbot.cmd.nr04;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

/**
 * Runs a task on the render thread at the start of the next frame (a {@link RenderCore.FrameListener}), so the commands of NR-04, which run
 * on the bot thread, can use the render-thread-only texture API without depending on the bot's client task queue.
 */
final class RenderThreadTasks implements RenderCore.FrameListener {
    private static final RenderThreadTasks INSTANCE = new RenderThreadTasks();
    private static boolean installed;

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

    private RenderThreadTasks() {
    }

    static <T> T call(Callable<T> task, long timeoutMs) throws Exception {
        synchronized (RenderThreadTasks.class) {
            if (!installed) {
                RenderCore.addListener(INSTANCE);
                installed = true;
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        INSTANCE.queue.add(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    @Override
    public void onFrameBegin() {
        Runnable r;
        while ((r = queue.poll()) != null) {
            r.run();
        }
    }
}

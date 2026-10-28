package net.xiaoyu233.fml.vulkan.opt;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared daemon pool for non-render background work (network, region IO, resource reload).
 * Threads: {@code max(1, cores/4)} (override {@code -Dfml.vulkan.opt.workers=n}), named {@code FML-Opt-n}, minimum priority.
 * Created lazily and thread-safely; {@link #executor()} may be called from any thread. Tasks must not block on the render thread.
 */
public final class OptWorkers {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static volatile ExecutorService executor;

    private OptWorkers() {
    }

    public static int threadCount() {
        int auto = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        return Math.max(1, OptFlags.integer("fml.vulkan.opt.workers", auto));
    }

    public static ExecutorService executor() {
        ExecutorService e = executor;
        if (e == null) {
            synchronized (OptWorkers.class) {
                e = executor;
                if (e == null) {
                    int n = threadCount();
                    ThreadPoolExecutor pool = new ThreadPoolExecutor(n, n, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(r, "FML-Opt-" + COUNTER.incrementAndGet());
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    });
                    pool.allowCoreThreadTimeOut(true);
                    executor = e = pool;
                }
            }
        }
        return e;
    }

    /** Convenience for fire-and-forget work. */
    public static void submit(Runnable task) {
        executor().execute(task);
    }
}

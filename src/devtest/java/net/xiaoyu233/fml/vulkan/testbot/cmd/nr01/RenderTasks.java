package net.xiaoyu233.fml.vulkan.testbot.cmd.nr01;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.xiaoyu233.fml.vulkan.core.RenderCore;

/**
 * Runs steps of a test on the render thread, one per frame begin ({@code RenderCore.FrameListener.onFrameBegin}, i.e. inside
 * {@code Display.update}), so a command on the bot thread can drive GPU work and wait for the outcome. Independent of the testbot core.
 */
final class RenderTasks {
    /** One test: {@link #step} runs once per frame on the render thread until it returns true. */
    interface Task {
        boolean step(long frame) throws Exception;
    }

    private static final ConcurrentLinkedQueue<Running> QUEUE = new ConcurrentLinkedQueue<>();
    private static boolean installed;

    private static final class Running {
        final Task task;
        final CompletableFuture<Void> done = new CompletableFuture<>();

        Running(Task task) {
            this.task = task;
        }
    }

    private RenderTasks() {
    }

    /** Runs {@code task} frame by frame; returns when it finished or throws its failure / a timeout (bot thread). */
    static void run(Task task, long timeoutSeconds) throws Exception {
        install();
        Running r = new Running(task);
        QUEUE.add(r);
        try {
            r.done.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        RenderCore.addListener(new RenderCore.FrameListener() {
            private final java.util.ArrayList<Running> active = new java.util.ArrayList<>();

            @Override
            public void onFrameBegin() {
                Running r;
                while ((r = QUEUE.poll()) != null) {
                    active.add(r);
                }
                long frame = RenderCore.frameNumber();
                for (int i = 0; i < active.size(); ) {
                    Running run = active.get(i);
                    try {
                        if (run.task.step(frame)) {
                            run.done.complete(null);
                            active.remove(i);
                            continue;
                        }
                    } catch (Throwable t) {
                        run.done.completeExceptionally(t);
                        active.remove(i);
                        continue;
                    }
                    i++;
                }
            }
        });
    }
}

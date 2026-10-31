package net.xiaoyu233.fml.vulkan.testbot.cmd.nr16;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.RenderTarget;
import net.xiaoyu233.fml.vulkan.render.state.AttribMask;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.render.text.TextRenderer;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code cursorCommand}: clears an offscreen target to a known colour, draws {@code TextRenderer.cursorQuad} with the two-pass emulation
 * forced on (always run) and, when the device enables {@code logicOp}, on the logic-op path (the default selection), and reads the pixel
 * back with {@code RenderCore.readColor}. Each pass also asserts which path {@code cursorQuad} reported (its return value).
 * R and G must equal {@code 255 - dst}, B must be 255, pixels outside the quad keep the clear colour.
 */
final class CursorCommand implements DevCommand {
    private static final int SIZE = 64;
    private static final int[] DST = {51, 102, 153}; // clear colour (0.2, 0.4, 0.6)

    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();
    private static boolean installed;

    @Override
    public String name() {
        return "cursorCommand";
    }

    @Override
    public String description() {
        return "GuiTextField cursor quad, logicOp off/on paths, read back R,G = 255-dst and B = 255";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        if (!RenderCore.isInitialized()) {
            s.warn("cursorCommand", "Vulkan backend not running");
            return;
        }
        boolean logicOp = RenderCore.deviceInfo() != null && RenderCore.deviceInfo().logicOp();
        s.info("cursorCommand device", "logicOp feature " + (logicOp ? "enabled" : "not enabled"));
        onFrameBegin(() -> {
            pass(s, true);
            if (logicOp) {
                pass(s, false);
            } else {
                s.info("cursorCommand logicOp path", "skipped (feature not enabled on this device)");
            }
        });
    }

    /** {@code forceEmulation} true: the two-pass emulation is forced ("off"); false: the default selection ("on", logicOp pipeline here). */
    private static void pass(Report.Section s, boolean forceEmulation) {
        String label = "cursorCommand logicOp " + (forceEmulation ? "off" : "on");
        RenderTarget target = new RenderTarget("cursorCommand", SIZE, SIZE, false);
        Draw.pushAttrib(AttribMask.decode(0xFFFFF));
        Draw.projection().push();
        Draw.pose().push();
        try {
            target.bind();
            RenderCore.clear(true, true, DST[0] / 255f, DST[1] / 255f, DST[2] / 255f, 1f, 1f, 0, 0, SIZE, SIZE);
            Frame.viewport(0, 0, SIZE, SIZE);
            Draw.projection().loadIdentity();
            Draw.projection().ortho(0, SIZE, SIZE, 0, -1, 1);
            Draw.pose().loadIdentity();
            Draw.depthTest(false);
            Draw.cull(false);
            boolean usedLogicOp = TextRenderer.cursorQuad(8f, 8f, 40f, 40f, forceEmulation);
            Frame.flush();
            s.check(usedLogicOp != forceEmulation, label + " path",
                    forceEmulation ? "forced emulation reported logicOp pipeline" : "logicOp device did not use logicOp pipeline");
            int[] inside = read(24, 40);   // GL y up: quad rows 8..40 from the top are GL y 24..56
            int[] outside = read(2, 2);
            check(s, label + " inside R = 255-dst", inside[0], 255 - DST[0]);
            check(s, label + " inside G = 255-dst", inside[1], 255 - DST[1]);
            check(s, label + " inside B = 255", inside[2], 255);
            s.check(outside[0] == DST[0] && outside[1] == DST[1] && outside[2] == DST[2], label + " outside keeps clear colour",
                    "got " + outside[0] + "," + outside[1] + "," + outside[2]);
        } catch (Throwable t) {
            s.exception(label, t);
        } finally {
            Draw.pose().pop();
            Draw.projection().pop();
            Draw.popAttrib();
            RenderTarget.bindMain();
            Frame.viewportFull();
            target.destroy();
        }
    }

    private static void check(Report.Section s, String subject, int got, int expected) {
        s.check(Math.abs(got - expected) <= 1, subject, "got " + got + ", expected " + expected);
    }

    private static int[] read(int x, int y) {
        ByteBuffer buf = ByteBuffer.allocateDirect(4);
        RenderCore.readColor(x, y, 1, 1, buf);
        return new int[] {buf.get(0) & 0xFF, buf.get(1) & 0xFF, buf.get(2) & 0xFF};
    }

    /** Runs {@code r} on the render thread at the next frame begin and waits (bot thread). */
    private static void onFrameBegin(Runnable r) throws Exception {
        synchronized (QUEUE) {
            if (!installed) {
                installed = true;
                RenderCore.addListener(new RenderCore.FrameListener() {
                    @Override
                    public void onFrameBegin() {
                        Runnable task;
                        while ((task = QUEUE.poll()) != null) {
                            task.run();
                        }
                    }
                });
            }
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        QUEUE.add(() -> {
            try {
                r.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}

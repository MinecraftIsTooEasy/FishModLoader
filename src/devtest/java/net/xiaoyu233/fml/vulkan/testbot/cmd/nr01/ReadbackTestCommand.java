package net.xiaoyu233.fml.vulkan.testbot.cmd.nr01;

import java.nio.ByteBuffer;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.Readback;
import net.xiaoyu233.fml.vulkan.render.gpu.RenderTarget;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code readbackTest}: clears an extra {@link RenderTarget} to a known colour, reads a rectangle of it asynchronously and checks the
 * returned BGRA bytes (clear colour, alpha 255, a half outside the target stays 0) and that the callback arrives within
 * framesInFlight + 1 frames; then clears a two-colour pattern with scissored clears to verify the bottom-up row order, and finally
 * reads MAIN to make sure the readback of the main framebuffer works and leaves the frame intact.
 */
final class ReadbackTestCommand implements DevCommand {
    @Override
    public String name() {
        return "readbackTest";
    }

    @Override
    public String description() {
        return "async readback of a cleared target returns the clear colour within framesInFlight+1 frames; row order; MAIN readback";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        if (!RenderCore.isInitialized()) {
            s.warn("readbackTest", "Vulkan backend not running");
            return;
        }
        RenderTasks.run(new Task(s), 120);
    }

    private static final class Task implements RenderTasks.Task {
        private static final int SIZE = 64;
        private final Report.Section s;
        private int phase;
        private RenderTarget target;
        private long requestedFrame;
        private volatile boolean arrived;
        private long arrivedFrame;
        private byte[] solid;
        private byte[] pattern;
        private int solidW, solidH;

        Task(Report.Section s) {
            this.s = s;
        }

        @Override
        public boolean step(long frame) {
            switch (phase) {
                case 0 -> {
                    target = new RenderTarget("readbackTest", SIZE, SIZE, false);
                    target.bind();
                    // colour (0.2, 0.4, 0.6) -> RGBA8 51, 102, 153 -> BGRA bytes 153, 102, 51, 255
                    RenderCore.clear(true, true, 0.2f, 0.4f, 0.6f, 1.0f, 1.0f, 0, 0, SIZE, SIZE);
                    // read a window of 48x48 that sticks 16 pixels out of the target at the left and bottom
                    solidW = 48;
                    solidH = 48;
                    requestedFrame = RenderCore.frameNumber();
                    arrived = false;
                    Readback.readColorAsync(target, -16, -16, solidW, solidH, buf -> {
                        solid = copy(buf);
                        arrived = true;
                        arrivedFrame = RenderCore.frameNumber();
                    });
                    RenderTarget.bindMain();
                    phase = 1;
                    return false;
                }
                case 1 -> {
                    if (!arrived) {
                        if (RenderCore.frameNumber() - requestedFrame > RenderCore.framesInFlight() + 1) {
                            s.fail("readbackTest callback latency", "no callback after " + (RenderCore.frameNumber() - requestedFrame) + " frames");
                            return cleanup();
                        }
                        return false;
                    }
                    long latency = arrivedFrame - requestedFrame;
                    s.check(latency <= RenderCore.framesInFlight() + 1, "readbackTest callback within framesInFlight+1 frames",
                            "arrived after " + latency + " frames");
                    s.info("readbackTest latency", latency + " frames (framesInFlight " + RenderCore.framesInFlight() + ")");
                    verifySolid();
                    // pattern: lower half red, upper half green (GL: y up), via scissor-free full clears of sub rectangles
                    target.bind();
                    RenderCore.clear(true, true, 0f, 1f, 0f, 1f, 1f, 0, 0, SIZE, SIZE);
                    RenderCore.clear(true, false, 1f, 0f, 0f, 1f, 1f, 0, 0, SIZE, SIZE / 2);     // Vulkan y down: this is the TOP half in GL
                    arrived = false;
                    requestedFrame = RenderCore.frameNumber();
                    Readback.readColorAsync(target, 0, 0, SIZE, SIZE, buf -> {
                        pattern = copy(buf);
                        arrived = true;
                    });
                    RenderTarget.bindMain();
                    phase = 2;
                    return false;
                }
                case 2 -> {
                    if (!arrived) {
                        if (RenderCore.frameNumber() - requestedFrame > RenderCore.framesInFlight() + 2) {
                            s.fail("readbackTest pattern", "no callback");
                            return cleanup();
                        }
                        return false;
                    }
                    verifyPattern();
                    requestedFrame = RenderCore.frameNumber();
                    arrived = false;
                    Readback.readColorAsync(RenderTarget.main(), 0, 0, 16, 16, buf -> {
                        solid = copy(buf);
                        arrived = true;
                    });
                    phase = 3;
                    return false;
                }
                default -> {
                    if (!arrived) {
                        if (RenderCore.frameNumber() - requestedFrame > RenderCore.framesInFlight() + 2) {
                            s.fail("readbackTest MAIN", "no callback");
                            return cleanup();
                        }
                        return false;
                    }
                    s.check(solid != null && solid.length == 16 * 16 * 4, "readbackTest MAIN readback delivers 16x16 BGRA", "length "
                            + (solid == null ? -1 : solid.length));
                    return cleanup();
                }
            }
        }

        private boolean cleanup() {
            RenderTarget.bindMain();
            if (target != null) {
                target.destroy();
                target = null;
            }
            return true;
        }

        private static byte[] copy(ByteBuffer b) {
            byte[] out = new byte[b.remaining()];
            b.duplicate().get(out);
            return out;
        }

        private void verifySolid() {
            if (solid == null || solid.length != solidW * solidH * 4) {
                s.fail("readbackTest size", "got " + (solid == null ? -1 : solid.length) + " bytes, expected " + solidW * solidH * 4);
                return;
            }
            int bad = 0;
            int outsideBad = 0;
            for (int y = 0; y < solidH; y++) {
                for (int x = 0; x < solidW; x++) {
                    int o = (y * solidW + x) * 4;
                    boolean inside = x >= 16 && y >= 16;          // GL coordinates of the window: the target covers x,y >= 16
                    int b = solid[o] & 0xFF, g = solid[o + 1] & 0xFF, r = solid[o + 2] & 0xFF, a = solid[o + 3] & 0xFF;
                    if (inside) {
                        if (Math.abs(b - 153) > 1 || Math.abs(g - 102) > 1 || Math.abs(r - 51) > 1 || a != 255) {
                            bad++;
                        }
                    } else if ((b | g | r | a) != 0) {
                        outsideBad++;
                    }
                }
            }
            s.check(bad == 0, "readbackTest clear colour returned (BGRA 153,102,51,255)", bad + " wrong pixels inside the target");
            s.check(outsideBad == 0, "readbackTest pixels outside the target are 0", outsideBad + " non-zero pixels outside");
        }

        private void verifyPattern() {
            if (pattern == null || pattern.length != SIZE * SIZE * 4) {
                s.fail("readbackTest pattern size", "got " + (pattern == null ? -1 : pattern.length));
                return;
            }
            // Vulkan clear rect y 0..SIZE/2 = top rows of the image = GL rows SIZE/2..SIZE-1 (red); GL rows 0..SIZE/2-1 stay green
            int wrong = 0;
            for (int glY = 0; glY < SIZE; glY++) {
                boolean expectRed = glY >= SIZE / 2;
                int o = (glY * SIZE + 5) * 4;
                int b = pattern[o] & 0xFF, g = pattern[o + 1] & 0xFF, r = pattern[o + 2] & 0xFF;
                boolean isRed = r > 200 && g < 50 && b < 50;
                boolean isGreen = g > 200 && r < 50 && b < 50;
                if (expectRed ? !isRed : !isGreen) {
                    wrong++;
                }
            }
            s.check(wrong == 0, "readbackTest rows are bottom-up (GL order)", wrong + " of " + SIZE + " rows have the wrong colour");
        }
    }
}

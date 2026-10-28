package net.xiaoyu233.fml.vulkan.render.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.xiaoyu233.fml.vulkan.render.buffer.Batches;

/**
 * Scoped switch that makes {@link Batches#consumer} (and with it {@code NativeTess.draw}) deliver {@link RecordedDraw}s into a list
 * instead of the GPU. Use with try-with-resources; scopes do not nest (the inner {@code open} throws). Test support of NR-00.
 *
 * <pre>
 * try (DrawCapture capture = DrawCapture.open()) {
 *     tess.draw();
 *     RecordedDraw first = capture.draws().get(0);
 * }
 * </pre>
 */
public final class DrawCapture implements AutoCloseable {
    private final ArrayList<RecordedDraw> draws = new ArrayList<>();
    private boolean closed;

    private DrawCapture() {
    }

    public static DrawCapture open() {
        if (Batches.isTestCapturing()) {
            throw new IllegalStateException("A DrawCapture scope is already open");
        }
        DrawCapture capture = new DrawCapture();
        Batches.testCapture(capture.draws::add);
        return capture;
    }

    /** The draws recorded so far, in submission order (live, read-only view). */
    public List<RecordedDraw> draws() {
        return Collections.unmodifiableList(draws);
    }

    public int totalVertices() {
        int n = 0;
        for (RecordedDraw d : draws) {
            n += d.vertexCount();
        }
        return n;
    }

    public void clear() {
        draws.clear();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            Batches.testCapture(null);
        }
    }
}

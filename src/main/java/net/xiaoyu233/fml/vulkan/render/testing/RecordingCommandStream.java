package net.xiaoyu233.fml.vulkan.render.testing;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records command-stream calls (binds, state, draws) as data instead of Vulkan commands, with the same method names and parameters as
 * {@code render.gpu.CommandStream}, so batching code can be unit-tested for ordering without a device. The real {@code CommandStream}
 * is a static facade; code under test takes this class (or a lambda over it) through its own seam. Test support of NR-00.
 */
public final class RecordingCommandStream {
    /** Kinds of recorded commands. */
    public enum Kind {
        BIND_PIPELINE, BIND_DESCRIPTOR_SET, BIND_VERTEX_BUFFER, BIND_INDEX_BUFFER, PUSH_CONSTANTS, SET_VIEWPORT, SET_SCISSOR_FULL,
        SET_LINE_WIDTH, SET_DEPTH_BIAS, SET_DYNAMIC_DEPTH_CULL, DRAW_INDEXED, DRAW_INDEXED_INDIRECT, INVALIDATE
    }

    /** One recorded command; the meaning of {@code a..d} / {@code f0..f3} depends on {@link Kind} (documented at each recording method). */
    public record Command(Kind kind, long a, long b, long c, long d, long e, float f0, float f1, float f2, float f3, byte[] data) {
    }

    private final ArrayList<Command> commands = new ArrayList<>();

    public List<Command> commands() {
        return Collections.unmodifiableList(commands);
    }

    public int size() {
        return commands.size();
    }

    public void clear() {
        commands.clear();
    }

    /** Number of recorded commands of {@code kind}. */
    public int count(Kind kind) {
        int n = 0;
        for (Command c : commands) {
            if (c.kind == kind) {
                n++;
            }
        }
        return n;
    }

    /** a = pipeline. */
    public void bindPipeline(long pipeline) {
        add(Kind.BIND_PIPELINE, pipeline, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    /** a = set index, b = descriptor set, c = dynamic offset. */
    public void bindDescriptorSet(int set, long descriptorSet, int dynamicOffset) {
        add(Kind.BIND_DESCRIPTOR_SET, set, descriptorSet, dynamicOffset, 0, 0, 0, 0, 0, 0, null);
    }

    /** a = binding, b = buffer, c = offset. */
    public void bindVertexBuffer(int binding, long buffer, long offset) {
        add(Kind.BIND_VERTEX_BUFFER, binding, buffer, offset, 0, 0, 0, 0, 0, 0, null);
    }

    /** a = buffer, b = offset, c = 1 for u32 indices. */
    public void bindIndexBuffer(long buffer, long offset, boolean u32) {
        add(Kind.BIND_INDEX_BUFFER, buffer, offset, u32 ? 1 : 0, 0, 0, 0, 0, 0, 0, null);
    }

    /** a = offset, data = copy of the remaining bytes of {@code data}. */
    public void pushConstants(ByteBuffer data, int offset) {
        byte[] copy = new byte[data.remaining()];
        data.duplicate().get(copy);
        add(Kind.PUSH_CONSTANTS, offset, 0, 0, 0, 0, 0, 0, 0, 0, copy);
    }

    /** f0..f3 = x, y, w, h. */
    public void setViewport(float x, float y, float w, float h) {
        add(Kind.SET_VIEWPORT, 0, 0, 0, 0, 0, x, y, w, h, null);
    }

    public void setScissorFull() {
        add(Kind.SET_SCISSOR_FULL, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    /** f0 = width. */
    public void setLineWidth(float w) {
        add(Kind.SET_LINE_WIDTH, 0, 0, 0, 0, 0, w, 0, 0, 0, null);
    }

    /** f0 = constant factor, f1 = slope factor. */
    public void setDepthBias(float constant, float slope) {
        add(Kind.SET_DEPTH_BIAS, 0, 0, 0, 0, 0, constant, slope, 0, 0, null);
    }

    /** a = test, b = write, c = compare op, d = cull mode. */
    public void setDynamicDepthCull(boolean test, boolean write, int compareOp, int cullMode) {
        add(Kind.SET_DYNAMIC_DEPTH_CULL, test ? 1 : 0, write ? 1 : 0, compareOp, cullMode, 0, 0, 0, 0, 0, null);
    }

    /** a = index count, b = instances, c = first index, d = vertex offset, e = first instance. */
    public void drawIndexed(int indexCount, int instances, int firstIndex, int vertexOffset, int firstInstance) {
        add(Kind.DRAW_INDEXED, indexCount, instances, firstIndex, vertexOffset, firstInstance, 0, 0, 0, 0, null);
    }

    /** a = buffer, b = offset, c = draw count, d = stride. */
    public void drawIndexedIndirect(long buffer, long offset, int count, int stride) {
        add(Kind.DRAW_INDEXED_INDIRECT, buffer, offset, count, stride, 0, 0, 0, 0, 0, null);
    }

    public void invalidate() {
        add(Kind.INVALIDATE, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    private void add(Kind kind, long a, long b, long c, long d, long e, float f0, float f1, float f2, float f3, byte[] data) {
        commands.add(new Command(kind, a, b, c, d, e, f0, f1, f2, f3, data));
    }
}

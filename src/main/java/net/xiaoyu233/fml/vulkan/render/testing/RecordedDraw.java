package net.xiaoyu233.fml.vulkan.render.testing;

import java.util.Arrays;
import net.xiaoyu233.fml.vulkan.render.buffer.VertexConsumer;
import net.xiaoyu233.fml.vulkan.render.state.RenderType;

/**
 * Data record of one finalised vertex block: the {@link RenderType} and the vertices exactly as they would be written to the GPU
 * (position, packed ABGR colour, uv, packed light, overlay ARGB), plus line segments. Also a {@link VertexConsumer}, so it can be
 * handed to code that writes vertices. Test support (recording fake of NR-00); not thread safe.
 */
public final class RecordedDraw implements VertexConsumer {
    /** Floats per vertex in {@link #floats()}: x, y, z, u, v. */
    public static final int FLOATS_PER_VERTEX = 5;
    /** Ints per vertex in {@link #ints()}: abgr, packedLight, overlayArgb. */
    public static final int INTS_PER_VERTEX = 3;
    /** Floats per line segment in {@link #lines()}: x0, y0, z0, x1, y1, z1. */
    public static final int FLOATS_PER_LINE = 6;

    private final RenderType type;
    private float[] floats = new float[FLOATS_PER_VERTEX * 16];
    private int[] ints = new int[INTS_PER_VERTEX * 16];
    private int vertexCount;
    private float[] lineFloats = new float[0];
    private int[] lineColors = new int[0];
    private int lineCount;

    public RecordedDraw(RenderType type) {
        this.type = type;
    }

    public RenderType type() {
        return type;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** Whole quads (4 vertices each). */
    public int quadCount() {
        return vertexCount / 4;
    }

    public int lineCount() {
        return lineCount;
    }

    /** Backing array, {@link #FLOATS_PER_VERTEX} per vertex; valid up to {@code vertexCount * FLOATS_PER_VERTEX}. */
    public float[] floats() {
        return floats;
    }

    /** Backing array, {@link #INTS_PER_VERTEX} per vertex; valid up to {@code vertexCount * INTS_PER_VERTEX}. */
    public int[] ints() {
        return ints;
    }

    public float[] lines() {
        return lineFloats;
    }

    public int[] lineColors() {
        return lineColors;
    }

    public float x(int i) {
        return floats[i * FLOATS_PER_VERTEX];
    }

    public float y(int i) {
        return floats[i * FLOATS_PER_VERTEX + 1];
    }

    public float z(int i) {
        return floats[i * FLOATS_PER_VERTEX + 2];
    }

    public float u(int i) {
        return floats[i * FLOATS_PER_VERTEX + 3];
    }

    public float v(int i) {
        return floats[i * FLOATS_PER_VERTEX + 4];
    }

    public int abgr(int i) {
        return ints[i * INTS_PER_VERTEX];
    }

    public int packedLight(int i) {
        return ints[i * INTS_PER_VERTEX + 1];
    }

    public int overlayArgb(int i) {
        return ints[i * INTS_PER_VERTEX + 2];
    }

    @Override
    public void vertex(float x, float y, float z, int abgr, float u, float v, int packedLight, int overlayArgb) {
        if ((vertexCount + 1) * FLOATS_PER_VERTEX > floats.length) {
            floats = Arrays.copyOf(floats, floats.length * 2);
            ints = Arrays.copyOf(ints, ints.length * 2);
        }
        int f = vertexCount * FLOATS_PER_VERTEX;
        floats[f] = x;
        floats[f + 1] = y;
        floats[f + 2] = z;
        floats[f + 3] = u;
        floats[f + 4] = v;
        int n = vertexCount * INTS_PER_VERTEX;
        ints[n] = abgr;
        ints[n + 1] = packedLight;
        ints[n + 2] = overlayArgb;
        vertexCount++;
    }

    @Override
    public void line(float x0, float y0, float z0, float x1, float y1, float z1, int abgr) {
        if ((lineCount + 1) * FLOATS_PER_LINE > lineFloats.length) {
            int cap = Math.max(4, lineCount * 2);
            lineFloats = Arrays.copyOf(lineFloats, cap * FLOATS_PER_LINE);
            lineColors = Arrays.copyOf(lineColors, cap);
        }
        int f = lineCount * FLOATS_PER_LINE;
        lineFloats[f] = x0;
        lineFloats[f + 1] = y0;
        lineFloats[f + 2] = z0;
        lineFloats[f + 3] = x1;
        lineFloats[f + 4] = y1;
        lineFloats[f + 5] = z1;
        lineColors[lineCount++] = abgr;
    }

    @Override
    public String toString() {
        return "RecordedDraw{" + type + ", vertices=" + vertexCount + ", lines=" + lineCount + '}';
    }
}

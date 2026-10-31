package net.xiaoyu233.fml.vulkan.render.gpu;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StreamSlice;
import net.xiaoyu233.fml.vulkan.pipeline.ShaderInterface;
import net.xiaoyu233.fml.vulkan.pipeline.Pipelines;
import org.lwjgl.system.MemoryUtil;

/**
 * Pass uniform block of the native programs (1.9), std140, 256 bytes, bound as a dynamic uniform buffer at set 0:
 * <pre>
 *   0   mat4 projection        64  vec4 fogColor      80  vec4 fogParams (start, end, density, mode 1 LINEAR / 2 EXP / 3 EXP2)
 *   96  vec4 viewport (w, h, 1/w, 1/h)                 112 vec4 misc (x time in seconds)
 *   128 mat4 terrainView       192 vec4 terrainParams (x section scale, y section bias)     208 vec4 reserved[3]
 * </pre>
 * Render thread only. The batches write the current values with the setters when state changes and call {@link #currentOffset()} before
 * a draw: the block is copied into a slice of the frame's uniform stream at the first draw after a change ({@link #markDirty()} or any
 * setter that changed a value), so one block serves every draw until the next change. {@link #descriptorSet()} is the set to bind
 * together with that dynamic offset (set 0 of every native pipeline layout; the layout equals the phase-1 uniform set layout).
 */
public final class PassUniforms {
    /** Size of the block in bytes. */
    public static final int SIZE = 256;

    /** Bytes taken from the stream per block: the phase-1 uniform descriptor covers {@link ShaderInterface#UBO_SIZE}. */
    private static final int SLICE_BYTES = Math.max(SIZE, ShaderInterface.UBO_SIZE);

    /** MITE section scale (WorldRenderer: scale(1.00001) around the section centre). */
    public static final float SECTION_SCALE = 1.00001F;

    private static final int OFF_PROJECTION = 0;
    private static final int OFF_FOG_COLOR = 64;
    private static final int OFF_FOG_PARAMS = 80;
    private static final int OFF_VIEWPORT = 96;
    private static final int OFF_MISC = 112;
    private static final int OFF_TERRAIN_VIEW = 128;
    private static final int OFF_TERRAIN_PARAMS = 192;

    private static final float[] PROJECTION = identity();
    private static final float[] TERRAIN_VIEW = identity();
    private static final float[] FOG = {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 3.0F};   // colour rgba, start, end, density, mode
    private static float viewportW = 1.0F;
    private static float viewportH = 1.0F;
    private static float sectionScale = SECTION_SCALE;
    private static float sectionBias = 8.0F * (SECTION_SCALE - 1.0F);

    private static boolean dirty = true;
    private static long frameOfSlice = -1L;
    private static int offset;
    private static long descriptorSet;
    private static long epochNanos = System.nanoTime();
    private static long listenerDevice;

    private PassUniforms() {
    }

    // ------------------------------------------------------------------------------------------------------------
    // contract (5.6)
    // ------------------------------------------------------------------------------------------------------------

    /** The block changed (or may have changed): the next {@link #currentOffset()} writes a new copy. */
    public static void markDirty() {
        dirty = true;
    }

    /**
     * Dynamic offset of an up-to-date copy of the block in the uniform stream; writes a new copy when something changed since the last
     * call or when the frame changed (the stream is reset per frame).
     */
    public static int currentOffset() {
        commit();
        return offset;
    }

    // ------------------------------------------------------------------------------------------------------------
    // values
    // ------------------------------------------------------------------------------------------------------------

    /** Descriptor set (set 0) to bind with {@link #currentOffset()}; 0 before the Vulkan core runs. */
    public static long descriptorSet() {
        commit();
        return descriptorSet;
    }

    /** GL projection matrix, column major (like {@code Draw.projection().top()}). */
    public static void setProjection(float[] m16) {
        dirty |= copyIfChanged(PROJECTION, m16);
    }

    /** Camera modelview of the terrain pass (rotation and bobbing, no translation). */
    public static void setTerrainView(float[] m16) {
        dirty |= copyIfChanged(TERRAIN_VIEW, m16);
    }

    /** Fog colour (rgba), distances, density and mode (1 LINEAR, 2 EXP, 3 EXP2); mode 0 is written as 0 (no fog evaluated). */
    public static void setFog(float r, float g, float b, float a, float start, float end, float density, int mode) {
        float[] f = FOG;
        if (f[0] != r || f[1] != g || f[2] != b || f[3] != a || f[4] != start || f[5] != end || f[6] != density || f[7] != mode) {
            f[0] = r;
            f[1] = g;
            f[2] = b;
            f[3] = a;
            f[4] = start;
            f[5] = end;
            f[6] = density;
            f[7] = mode;
            dirty = true;
        }
    }

    /** Size in pixels of the bound target. */
    public static void setViewport(int width, int height) {
        float w = Math.max(1, width);
        float h = Math.max(1, height);
        if (w != viewportW || h != viewportH) {
            viewportW = w;
            viewportH = h;
            dirty = true;
        }
    }

    /** Terrain section transform {@code world = p * scale - bias}; the default is MITE's {@code 1.00001} / {@code 8 * (1.00001 - 1)}. */
    public static void setTerrainParams(float scale, float bias) {
        if (scale != sectionScale || bias != sectionBias) {
            sectionScale = scale;
            sectionBias = bias;
            dirty = true;
        }
    }

    /** Restores the start-up values (identity matrices, fog off, MITE section transform). */
    public static void reset() {
        System.arraycopy(identity(), 0, PROJECTION, 0, 16);
        System.arraycopy(identity(), 0, TERRAIN_VIEW, 0, 16);
        FOG[0] = 0.0F;
        FOG[1] = 0.0F;
        FOG[2] = 0.0F;
        FOG[3] = 1.0F;
        FOG[4] = 0.0F;
        FOG[5] = 1.0F;
        FOG[6] = 1.0F;
        FOG[7] = 3.0F;
        viewportW = 1.0F;
        viewportH = 1.0F;
        sectionScale = SECTION_SCALE;
        sectionBias = 8.0F * (SECTION_SCALE - 1.0F);
        epochNanos = System.nanoTime();
        dirty = true;
        frameOfSlice = -1L;
        descriptorSet = 0L;
    }

    // ------------------------------------------------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------------------------------------------------

    private static void commit() {
        if (!RenderCore.isInitialized()) {
            return;
        }
        ensureListener();
        long frame = RenderCore.frameNumber();
        if (!dirty && frame == frameOfSlice) {
            return;
        }
        StreamSlice slice = RenderCore.streamUniform(SLICE_BYTES);
        write(slice.address());
        offset = (int) slice.offset();
        descriptorSet = Pipelines.uniformSet(slice.buffer());
        frameOfSlice = frame;
        dirty = false;
    }

    /** Serialises the block at {@code address} ({@link #SIZE} bytes). */
    static void write(long address) {
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(address + OFF_PROJECTION + 4L * i, PROJECTION[i]);
            MemoryUtil.memPutFloat(address + OFF_TERRAIN_VIEW + 4L * i, TERRAIN_VIEW[i]);
        }
        for (int i = 0; i < 4; i++) {
            MemoryUtil.memPutFloat(address + OFF_FOG_COLOR + 4L * i, FOG[i]);
        }
        MemoryUtil.memPutFloat(address + OFF_FOG_PARAMS, FOG[4]);
        MemoryUtil.memPutFloat(address + OFF_FOG_PARAMS + 4, FOG[5]);
        MemoryUtil.memPutFloat(address + OFF_FOG_PARAMS + 8, FOG[6]);
        MemoryUtil.memPutFloat(address + OFF_FOG_PARAMS + 12, FOG[7]);
        MemoryUtil.memPutFloat(address + OFF_VIEWPORT, viewportW);
        MemoryUtil.memPutFloat(address + OFF_VIEWPORT + 4, viewportH);
        MemoryUtil.memPutFloat(address + OFF_VIEWPORT + 8, 1.0F / viewportW);
        MemoryUtil.memPutFloat(address + OFF_VIEWPORT + 12, 1.0F / viewportH);
        MemoryUtil.memPutFloat(address + OFF_MISC, (float) ((System.nanoTime() - epochNanos) * 1.0e-9));
        MemoryUtil.memPutFloat(address + OFF_MISC + 4, 0.0F);
        MemoryUtil.memPutFloat(address + OFF_MISC + 8, 0.0F);
        MemoryUtil.memPutFloat(address + OFF_MISC + 12, 0.0F);
        MemoryUtil.memPutFloat(address + OFF_TERRAIN_PARAMS, sectionScale);
        MemoryUtil.memPutFloat(address + OFF_TERRAIN_PARAMS + 4, sectionBias);
        MemoryUtil.memPutFloat(address + OFF_TERRAIN_PARAMS + 8, 0.0F);
        MemoryUtil.memPutFloat(address + OFF_TERRAIN_PARAMS + 12, 0.0F);
        MemoryUtil.memSet(address + 208, 0, SIZE - 208);
    }

    /** The uniform stream restarts with every frame, so the first call of a frame must write a new copy; the device may be recreated. */
    private static void ensureListener() {
        long device = RenderCore.device().address();
        if (device == listenerDevice) {
            return;
        }
        listenerDevice = device;
        frameOfSlice = -1L;
        dirty = true;
        RenderCore.addListener(new RenderCore.FrameListener() {
            @Override
            public void onFrameBegin() {
                dirty = true;
            }
        });
    }

    private static boolean copyIfChanged(float[] dst, float[] src) {
        boolean changed = false;
        for (int i = 0; i < 16; i++) {
            if (dst[i] != src[i]) {
                dst[i] = src[i];
                changed = true;
            }
        }
        return changed;
    }

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = 1.0F;
        m[5] = 1.0F;
        m[10] = 1.0F;
        m[15] = 1.0F;
        return m;
    }
}

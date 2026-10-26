package net.xiaoyu233.fml.vulkan.gl.draw;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StaticBuffer;
import net.xiaoyu233.fml.vulkan.gl.GLErrors;
import net.xiaoyu233.fml.vulkan.gl.state.GLState;
import net.xiaoyu233.fml.vulkan.pipeline.CommandRecorder;
import net.xiaoyu233.fml.vulkan.pipeline.PipelineKey;
import net.xiaoyu233.fml.vulkan.pipeline.Pipelines;
import net.xiaoyu233.fml.vulkan.pipeline.ShaderInterface;
import net.xiaoyu233.fml.vulkan.pipeline.VertexLayout;
import net.xiaoyu233.fml.vulkan.texture.TextureManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Geometry and draw path of the GL emulation (module D2): immediate mode, client arrays and buffer objects, primitive conversion,
 * the shared static quad index buffers and <b>draw merging</b>. Render thread only.
 * <p>
 * Every draw (immediate-mode glEnd, glDrawArrays/Elements, a display list geometry op) ends in the same path:
 * <ol>
 * <li>{@code GLState.applyDynamicState()} and {@code GLState.bindUniforms()} (viewport/scissor/... and the uniform block);</li>
 * <li>pipeline = {@code Pipelines.get(pipelineBits | topology, vertexLayout)}, texture sets of unit 0/1, the 128 byte push constants;</li>
 * <li>the vertices (and generated indices) are copied into this frame's stream ring (bump allocated, so back-to-back draws are
 * adjacent in the buffer) or, for display lists, come from the list's static buffer;</li>
 * <li>the draw is held back as the <i>pending draw</i>; the next draw is appended to it when pipeline, both texture sets,
 * push constants, vertex layout and uniform state are identical and its vertices (and indices) directly follow; otherwise the
 * pending draw is recorded ({@link #flush}) and the new one becomes pending.</li>
 * </ol>
 * <p>
 * <b>Merging protocol with GLState</b> (module D1, ARCHITECTURE section 5.9): deferral needs the cooperation of {@code GLState}.
 * (1) {@code long uniformStamp()}: identity of the uniform block bound by the last {@code bindUniforms()}, it changes exactly when a
 * new block was written, so equal stamps mean the same uniform set and dynamic offset. (2) {@code GLState} must call {@link #flush()}
 * before it changes anything the {@code CommandRecorder} applies at draw time: a new uniform block in {@code bindUniforms()}, a changed
 * viewport / scissor / depth bias / line width / blend constants in {@code applyDynamicState()}, and before {@code RenderCore.clear} /
 * glFinish. The pending draw then goes out with the recorder state it was prepared with. Texture sets, pipeline, push constants and
 * buffers are held by the pending draw itself. {@link VulkanConfig#noDrawMerge()} ({@code fml.vulkan.noDrawMerge}) records every draw
 * immediately (debugging aid).
 */
public final class DrawSystem {
    /** Bytes of one immediate-mode vertex ({@link VertexLayout#IMMEDIATE}). */
    static final int IMMEDIATE_STRIDE = 56;

    private static final int O_TEX0 = 16;
    private static final int O_TEX1 = 32;
    private static final int O_NORMAL = 40;
    private static final int O_COLOR = 52;

    private static final int QUAD_INITIAL = 8192;
    private static final int PC_SIZE = ShaderInterface.PUSH_CONSTANT_SIZE;

    // lifecycle
    private static boolean initialized;
    private static int epoch;
    /** Draws are held back as the pending draw and merged (see the class comment); false = recorded immediately. */
    private static boolean defer;

    private static final StreamArena vertexArena = new StreamArena(false, 1 << 20);
    private static final StreamArena indexArena = new StreamArena(true, 1 << 19);
    private static final NativeBuf indexScratch = new NativeBuf();

    // two push constant blocks: the one being built for the new draw and the one of the pending draw
    private static long pcMem;
    private static long pcCur;
    private static long pcPend;

    // shared static quad index buffers (uint32), grown on demand: smooth (0,1,2)(0,2,3), flat (3,0,1)(3,1,2)
    private static StaticBuffer quadSmooth;
    private static StaticBuffer quadFlat;
    private static int quadSmoothCap;
    private static int quadFlatCap;

    // the pending draw
    private static boolean pendValid;
    /** Later draws may be appended (false for baked indexed display list draws). */
    private static boolean pendMerge;
    private static int pendKind;
    private static long pendPipeline;
    private static long pendTex0;
    private static long pendTex1;
    private static long pendStamp;
    private static VertexLayout pendLayout;
    private static long pendVbuf;
    private static long pendVoff;
    private static long pendVend;
    private static int pendVertexCount;
    private static long pendIbuf;
    private static long pendIoff;
    private static long pendIend;
    private static int pendIndexCount;

    // state of the draw being prepared (beginDraw)
    private static long curPipeline;
    private static long curTex0;
    private static long curTex1;
    private static long curStamp;
    private static VertexLayout curLayout;
    private static boolean curFlat;

    // draw trace (-Dfml.vulkan.traceFrame=<presented frame>): every recorded draw of that frame is logged with its state
    private static long traceFrame = traceFrameProperty();
    private static String curTrace;
    private static String pendTrace;

    /** Test harness: trace the draws of this presented frame (-1 = none). */
    public static void setTraceFrame(long frame) {
        traceFrame = frame;
    }

    private static long traceFrameProperty() {
        try {
            String v = System.getProperty("fml.vulkan.traceFrame");
            return v == null || v.trim().isEmpty() ? -1L : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** True while the frame selected by {@code fml.vulkan.traceFrame} is being recorded. */
    static boolean tracing() {
        return traceFrame >= 0 && RenderCore.frameNumber() == traceFrame;
    }

    private static boolean pipelineCached;
    private static long cachedKey;
    private static VertexLayout cachedLayout;
    private static long cachedPipeline;

    /** Immediate-mode vertices of the glBegin..glEnd batch being executed. */
    private static final class Batch {
        final NativeBuf buf = new NativeBuf();
        int count;
        int mode;
        boolean active;
    }

    private static final Batch live = new Batch();

    private DrawSystem() {
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------------------------------------------------------------

    /** Called once by VulkanBackend.start after GLState.init; calling it again while initialized does nothing. */
    public static void init() {
        if (initialized) {
            return;
        }
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalStateException("The Vulkan backend writes vertex data in native byte order and needs a little endian host");
        }
        VkLog.LOGGER.debug("DrawSystem.init");
        ClientState.reset();
        pcMem = MemoryUtil.nmemCalloc(2, PC_SIZE);
        if (pcMem == 0L) {
            throw new OutOfMemoryError("push constant blocks");
        }
        pcCur = pcMem;
        pcPend = pcMem + PC_SIZE;
        pendValid = false;
        pipelineCached = false;
        vertexArena.reset();
        indexArena.reset();
        defer = !VulkanConfig.noDrawMerge();
        if (!defer) {
            VkLog.LOGGER.info("DrawSystem: draw merging is switched off by -D{}", VulkanConfig.PROP_NO_DRAW_MERGE);
        }
        epoch++;
        RenderCore.addListener(new Listener(epoch));
        initialized = true;
    }

    /** Drops the pending draw (the device is idle, nothing may be recorded any more) and frees every resource of D2. */
    public static void shutdown() {
        if (!initialized) {
            return;
        }
        VkLog.LOGGER.debug("DrawSystem.shutdown");
        initialized = false;
        epoch++; // listeners registered by this init become inert
        pendValid = false;
        live.active = false;
        live.buf.free();
        DisplayLists.shutdown();
        if (quadSmooth != null) {
            quadSmooth.free();
            quadSmooth = null;
        }
        if (quadFlat != null) {
            quadFlat.free();
            quadFlat = null;
        }
        quadSmoothCap = 0;
        quadFlatCap = 0;
        vertexArena.reset();
        indexArena.reset();
        indexScratch.free();
        ArrayData.shutdown();
        BufferObjects.shutdown();
        Queries.shutdown();
        ClientState.reset();
        if (pcMem != 0L) {
            MemoryUtil.nmemFree(pcMem);
            pcMem = 0L;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /** Records the pending merged draw, if any. Safe to call at any time (also re-entrantly from GLState). */
    public static void flush() {
        flushPending();
    }

    private static final class Listener implements RenderCore.FrameListener {
        private final int owner;

        Listener(int owner) {
            this.owner = owner;
        }

        @Override
        public void onFrameBegin() {
            if (owner == epoch) {
                pendValid = false;
                vertexArena.reset(); // the stream ring memory belongs to the frame slot
                indexArena.reset();
            }
        }

        @Override
        public void onCommandBufferReset() {
            if (owner == epoch) {
                pendValid = false;
            }
        }

        @Override
        public void beforePassEnd() {
            if (owner == epoch) {
                flushPending();
            }
        }

        @Override
        public void beforeFrameEnd() {
            if (owner == epoch) {
                flushPending();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // immediate mode
    // ------------------------------------------------------------------------------------------------------------------------

    /** True between glBegin and glEnd (a display list can not be started then). */
    static boolean immediateActive() {
        return live.active;
    }

    static boolean flatShadingNow() {
        return initialized && GLState.get().flatShading();
    }

    /** glBegin: compiled into the list being built (and also executed for GL_COMPILE_AND_EXECUTE). */
    public static void beginImmediate(int mode) {
        if (DisplayLists.isCompiling()) {
            DisplayLists.captureBegin(mode);
            if (!DisplayLists.executing()) {
                return;
            }
        }
        liveBegin(mode);
    }

    /** glEnd. */
    public static void endImmediate() {
        if (DisplayLists.isCompiling()) {
            DisplayLists.captureEnd();
            if (!DisplayLists.executing()) {
                return;
            }
        }
        liveEnd();
    }

    /** glVertex*: the attributes are the ones GLState holds (or, inside a display list, the ones set in the list). */
    public static void vertex(float x, float y, float z, float w) {
        if (DisplayLists.isCompiling()) {
            DisplayLists.captureVertex(x, y, z, w);
            if (!DisplayLists.executing()) {
                return;
            }
        }
        liveVertex(x, y, z, w);
    }

    /** glRect*: executed now (a compiled glRect replays this), as a 4 vertex GL_POLYGON. */
    public static void rect(float x1, float y1, float x2, float y2) {
        if (live.active) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        liveBegin(GL11.GL_POLYGON);
        liveVertex(x1, y1, 0f, 1f);
        liveVertex(x2, y1, 0f, 1f);
        liveVertex(x2, y2, 0f, 1f);
        liveVertex(x1, y2, 0f, 1f);
        liveEnd();
    }

    private static void liveBegin(int mode) {
        if (!Primitives.isMode(mode)) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (live.active) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        live.active = true;
        live.mode = mode;
        live.count = 0;
        live.buf.clear();
    }

    private static void liveVertex(float x, float y, float z, float w) {
        if (!live.active) {
            return;
        }
        GLState st = GLState.get();
        putVertex(live.buf.append(IMMEDIATE_STRIDE), x, y, z, w, st.currentColor(), st.currentNormal(), st.currentTexCoord(0),
                st.currentTexCoord(1));
        live.count++;
    }

    private static void liveEnd() {
        if (!live.active) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        live.active = false;
        int n = live.count;
        if (n == 0 || !initialized) {
            return;
        }
        boolean flat = flatShadingNow();
        if (!Primitives.classifyArrays(live.mode, n, flat)) {
            return;
        }
        int v = Primitives.vertices;
        ArrayData.layout = VertexLayout.IMMEDIATE;
        ArrayData.stride = IMMEDIATE_STRIDE;
        ArrayData.src = live.buf.address;
        ArrayData.srcBytes = v * IMMEDIATE_STRIDE;
        ArrayData.allocBytes = v * IMMEDIATE_STRIDE;
        issueStream(live.mode, flat, 0L, 0, 0, 0);
    }

    /**
     * Writes one immediate-mode vertex ({@link VertexLayout#IMMEDIATE}: pos f32x4 @0, tex0 f32x4 @16, tex1 f32x2 @32, normal f32x3 @40,
     * color u8x4 UNORM @52). The position keeps its w; the color is clamped to [0, 1].
     */
    static void putVertex(long p, float x, float y, float z, float w, float[] color, float[] normal, float[] tex0, float[] tex1) {
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutFloat(p + 12, w);
        MemoryUtil.memPutFloat(p + O_TEX0, tex0[0]);
        MemoryUtil.memPutFloat(p + O_TEX0 + 4, tex0[1]);
        MemoryUtil.memPutFloat(p + O_TEX0 + 8, tex0[2]);
        MemoryUtil.memPutFloat(p + O_TEX0 + 12, tex0[3]);
        MemoryUtil.memPutFloat(p + O_TEX1, tex1[0]);
        MemoryUtil.memPutFloat(p + O_TEX1 + 4, tex1[1]);
        MemoryUtil.memPutFloat(p + O_NORMAL, normal[0]);
        MemoryUtil.memPutFloat(p + O_NORMAL + 4, normal[1]);
        MemoryUtil.memPutFloat(p + O_NORMAL + 8, normal[2]);
        MemoryUtil.memPutInt(p + O_COLOR, u8(color[0]) | u8(color[1]) << 8 | u8(color[2]) << 16 | u8(color[3]) << 24);
    }

    /** Float [0, 1] to unorm8 with rounding; out of range values (colors above 1.0 are common in MITE) clamp, NaN is 0. */
    static int u8(float v) {
        if (!(v > 0f)) {
            return 0;
        }
        if (v >= 1f) {
            return 255;
        }
        return (int) (v * 255f + 0.5f);
    }

    /** Copies one attribute of an immediate-mode vertex to another vertex (back-fill of display list batches). */
    static void copyAttribute(int attr, long from, long to) {
        switch (attr) {
            case VertexLayout.ATTR_COLOR:
                MemoryUtil.memPutInt(to + O_COLOR, MemoryUtil.memGetInt(from + O_COLOR));
                break;
            case VertexLayout.ATTR_TEX0:
                MemoryUtil.memCopy(from + O_TEX0, to + O_TEX0, 16);
                break;
            case VertexLayout.ATTR_TEX1:
                MemoryUtil.memCopy(from + O_TEX1, to + O_TEX1, 8);
                break;
            case VertexLayout.ATTR_NORMAL:
                MemoryUtil.memCopy(from + O_NORMAL, to + O_NORMAL, 12);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // client array draws
    // ------------------------------------------------------------------------------------------------------------------------

    /** glDrawArrays (compiled into the list being built, and executed unless the list is GL_COMPILE only). */
    public static void drawArrays(int mode, int first, int count) {
        if (live.active || DisplayLists.batchActive()) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (first < 0 || count < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (!Primitives.isMode(mode)) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (count == 0 || !(initialized || DisplayLists.isCompiling()) || !ArrayData.canDraw()) {
            return;
        }
        boolean flat = flatShadingNow();
        if (!Primitives.classifyArrays(mode, count, flat) || !ArrayData.prepare(first, Primitives.vertices)) {
            return;
        }
        deliver(mode, flat, 0L, 0, 0, 0);
    }

    /** glMultiDrawArrays: one draw per (first, count) pair. */
    public static void multiDrawArrays(int mode, IntBuffer first, IntBuffer count) {
        int n = Math.min(first.remaining(), count.remaining());
        int fp = first.position();
        int cp = count.position();
        for (int i = 0; i < n; i++) {
            drawArrays(mode, first.get(fp + i), count.get(cp + i));
        }
    }

    /** glDrawElements with client memory indices at {@code address} (readable up to {@code limit}). */
    public static void drawElements(int mode, int count, int type, long address, long limit) {
        elements(mode, count, type, address, limit);
    }

    /** glDrawElements(mode, ByteBuffer): GL_UNSIGNED_BYTE indices from the position, count = remaining. */
    public static void drawElements(int mode, ByteBuffer indices) {
        elements(mode, indices.remaining(), GL11.GL_UNSIGNED_BYTE, Mem.addr(indices), Mem.endOfStorage(indices));
    }

    public static void drawElements(int mode, ShortBuffer indices) {
        elements(mode, indices.remaining(), GL11.GL_UNSIGNED_SHORT, Mem.addr(indices), Mem.endOfStorage(indices));
    }

    public static void drawElements(int mode, IntBuffer indices) {
        elements(mode, indices.remaining(), GL11.GL_UNSIGNED_INT, Mem.addr(indices), Mem.endOfStorage(indices));
    }

    /** glDrawRangeElements: like glDrawElements; GL_INVALID_VALUE when {@code end < start} (the range itself is not needed here). */
    public static void drawRangeElements(int mode, int start, int end, ByteBuffer indices) {
        if (end < start) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        drawElements(mode, indices);
    }

    public static void drawRangeElements(int mode, int start, int end, ShortBuffer indices) {
        if (end < start) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        drawElements(mode, indices);
    }

    public static void drawRangeElements(int mode, int start, int end, IntBuffer indices) {
        if (end < start) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        drawElements(mode, indices);
    }

    public static void drawRangeElementsVbo(int mode, int start, int end, int count, int type, long offset) {
        if (end < start) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        drawElementsVbo(mode, count, type, offset);
    }

    /** glDrawElements / glDrawRangeElements with indices in the buffer bound to GL_ELEMENT_ARRAY_BUFFER at byte {@code offset}. */
    public static void drawElementsVbo(int mode, int count, int type, long offset) {
        BufferObjects.BufferObject b = BufferObjects.get(ClientState.getElementArrayBufferBinding());
        if (b == null || b.address == 0L || offset < 0 || offset > b.size) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        elements(mode, count, type, b.address + offset, b.address + b.size);
    }

    private static void elements(int mode, int count, int type, long address, long limit) {
        if (live.active || DisplayLists.batchActive()) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        int typeSize;
        switch (type) {
            case GL11.GL_UNSIGNED_BYTE:
                typeSize = 1;
                break;
            case GL11.GL_UNSIGNED_SHORT:
                typeSize = 2;
                break;
            case GL11.GL_UNSIGNED_INT:
                typeSize = 4;
                break;
            default:
                GLErrors.set(GL11.GL_INVALID_ENUM);
                return;
        }
        if (count < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (!Primitives.isMode(mode)) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (count == 0 || !(initialized || DisplayLists.isCompiling()) || !ArrayData.canDraw()) {
            return;
        }
        if (address == 0L || address + (long) count * typeSize > limit) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        ArrayData.scanIndices(address, type, count);
        long min = ArrayData.scanMin;
        long max = ArrayData.scanMax;
        if (max >= Integer.MAX_VALUE) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        boolean flat = flatShadingNow();
        if (!Primitives.classifyElements(mode, count)) {
            return;
        }
        if (!ArrayData.prepare((int) min, (int) (max - min + 1))) {
            return;
        }
        deliver(mode, flat, address, type, (int) min, count);
    }

    /**
     * glArrayElement: one vertex read from the enabled arrays and added to the current glBegin..glEnd batch (live and/or compiled).
     * Attributes without an enabled array keep the current value.
     */
    public static void arrayElement(int i) {
        boolean compiling = DisplayLists.isCompiling();
        boolean capture = compiling && DisplayLists.batchActive();
        boolean execute = (!compiling || DisplayLists.executing()) && live.active;
        if ((!capture && !execute) || !ArrayData.readElement(i)) {
            return;
        }
        int present = ArrayData.ePresent;
        if (capture) {
            if ((present & (1 << VertexLayout.ATTR_COLOR)) != 0) {
                float[] c = ArrayData.eColor;
                DisplayLists.compileAttrib(VertexLayout.ATTR_COLOR, c[0], c[1], c[2], c[3]);
            }
            if ((present & (1 << VertexLayout.ATTR_NORMAL)) != 0) {
                float[] n = ArrayData.eNormal;
                DisplayLists.compileAttrib(VertexLayout.ATTR_NORMAL, n[0], n[1], n[2], 1f);
            }
            if ((present & (1 << VertexLayout.ATTR_TEX0)) != 0) {
                float[] t = ArrayData.eTex0;
                DisplayLists.compileAttrib(VertexLayout.ATTR_TEX0, t[0], t[1], t[2], t[3]);
            }
            if ((present & (1 << VertexLayout.ATTR_TEX1)) != 0) {
                float[] t = ArrayData.eTex1;
                DisplayLists.compileAttrib(VertexLayout.ATTR_TEX1, t[0], t[1], t[2], t[3]);
            }
            float[] p = ArrayData.ePos;
            DisplayLists.captureVertex(p[0], p[1], p[2], p[3]);
        }
        if (execute) {
            GLState st = GLState.get();
            float[] p = ArrayData.ePos;
            putVertex(live.buf.append(IMMEDIATE_STRIDE), p[0], p[1], p[2], p[3],
                    (present & (1 << VertexLayout.ATTR_COLOR)) != 0 ? ArrayData.eColor : st.currentColor(),
                    (present & (1 << VertexLayout.ATTR_NORMAL)) != 0 ? ArrayData.eNormal : st.currentNormal(),
                    (present & (1 << VertexLayout.ATTR_TEX0)) != 0 ? ArrayData.eTex0 : st.currentTexCoord(0),
                    (present & (1 << VertexLayout.ATTR_TEX1)) != 0 ? ArrayData.eTex1 : st.currentTexCoord(1));
            live.count++;
        }
    }

    /**
     * The prepared vertices ({@link ArrayData}) and the classified primitive ({@link Primitives}) go into the display list being
     * compiled and/or are drawn now. A display list does not know the shade model of its replay: its indexed draws carry the
     * indices of both conventions (and a smooth GL_TRIANGLES draw becomes indexed for that), the replay picks.
     */
    private static void deliver(int mode, boolean flat, long idxAddr, int idxType, int idxMin, int idxCount) {
        if (DisplayLists.isCompiling()) {
            int kind = Primitives.kind;
            int ic = Primitives.indexCount;
            if (kind == Primitives.K_DIRECT && mode == GL11.GL_TRIANGLES) {
                kind = Primitives.K_INDEXED;
                ic = Primitives.vertices;
            }
            long isrc = 0L;
            long isrcFlat = 0L;
            if (kind == Primitives.K_INDEXED) {
                boolean both = Primitives.flatSensitive(mode);
                long bytes = (long) ic * 4;
                if (bytes * (both ? 2 : 1) > Integer.MAX_VALUE) {
                    GLErrors.set(GL11.GL_OUT_OF_MEMORY);
                    return;
                }
                isrc = indexScratch.ensure((int) bytes * (both ? 2 : 1));
                writeIndices(mode, Primitives.vertices, false, isrc, idxAddr, idxType, idxMin, idxCount, 0);
                if (both) {
                    isrcFlat = isrc + bytes;
                    writeIndices(mode, Primitives.vertices, true, isrcFlat, idxAddr, idxType, idxMin, idxCount, 0);
                }
            }
            DisplayLists.appendItem(ArrayData.layout, ArrayData.stride, kind, Primitives.topology, ArrayData.allocBytes / ArrayData.stride,
                    ArrayData.src, ArrayData.srcBytes, isrc, isrcFlat, ic);
            if (!DisplayLists.executing()) {
                return;
            }
        }
        if (initialized) {
            issueStream(mode, flat, idxAddr, idxType, idxMin, idxCount);
        }
    }

    /** Writes the indices of the classified draw: array draws number the vertices from 0, element draws map through the index array. */
    private static void writeIndices(int mode, int nVertices, boolean flat, long dst, long idxAddr, int idxType, int idxMin, int idxCount,
                                     int bias) {
        if (idxAddr == 0L) {
            Primitives.writeArrayIndices(mode, nVertices, flat, bias, dst);
        } else {
            Primitives.writeElementIndices(mode, idxCount, flat, idxAddr, idxType, idxMin, bias, dst);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // issuing a draw
    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Draw state shared by every draw: dynamic state, uniform block, pipeline, texture sets, push constants into {@code pcCur}.
     * GLState flushes the pending draw itself when applyDynamicState/bindUniforms change what the recorder holds.
     */
    private static void beginDraw(VertexLayout layout, int topology) {
        GLState st = GLState.get();
        st.applyDynamicState();
        st.bindUniforms();
        curStamp = defer ? st.uniformStamp() : 0L;
        curFlat = st.flatShading();
        curLayout = layout;
        final int attribMask = layout.attribMask();
        st.writePushConstants(pcCur, attribMask);
        clearSuppliedAttributes(pcCur, attribMask);
        long key = PipelineKey.set(st.pipelineBits(), PipelineKey.TOPOLOGY, topology);
        if (!pipelineCached || key != cachedKey || layout != cachedLayout) {
            cachedPipeline = Pipelines.get(key, layout);
            cachedKey = key;
            cachedLayout = layout;
            pipelineCached = true;
        }
        curPipeline = cachedPipeline;
        curTex0 = TextureManager.descriptorSetForUnit(0);
        curTex1 = st.texture2D(1) ? TextureManager.descriptorSetForUnit(1) : curTex0;
        if (traceFrame >= 0 && RenderCore.frameNumber() == traceFrame) {
            curTrace = TextureManager.describeUnit(0) + " " + (st.texture2D(1) ? TextureManager.describeUnit(1) + " " : "") + "usedSet0=0x"
                    + Long.toHexString(curTex0) + " attribMask=" + attribMask + " " + st.describeForTrace() + " | "
                    + Pipelines.describe(key) + " | layout=" + layout;
        } else {
            curTrace = null;
        }
    }

    /**
     * Zeroes the current color / normal / texcoord fields of the push constants for the attributes the vertex stream supplies: the
     * shader reads those fields only for attributes that are absent from the stream (ShaderInterface, attribMask), so their values are
     * irrelevant for the picture, but they take part in the comparison that decides whether two draws can be merged. Left as they are, a
     * draw whose vertices carry every attribute (every immediate-mode draw: the glyphs of the font renderer set a different current
     * texcoord each) would never merge with the previous one.
     */
    private static void clearSuppliedAttributes(long pc, int attribMask) {
        if ((attribMask & ShaderInterface.ATTR_COLOR_BIT) != 0) {
            zero16(pc + ShaderInterface.PC_COLOR);
        }
        if ((attribMask & ShaderInterface.ATTR_NORMAL_BIT) != 0) {
            zero16(pc + ShaderInterface.PC_NORMAL);
        }
        if ((attribMask & ShaderInterface.ATTR_TEX0_BIT) != 0) {
            zero16(pc + ShaderInterface.PC_TEXCOORD0);
        }
        if ((attribMask & ShaderInterface.ATTR_TEX1_BIT) != 0) {
            MemoryUtil.memPutLong(pc + ShaderInterface.PC_TEXCOORD1, 0L); // vec2
        }
    }

    private static void zero16(long p) {
        MemoryUtil.memPutLong(p, 0L);
        MemoryUtil.memPutLong(p + 8, 0L);
    }

    /** True when the pending draw has exactly the state of the draw being prepared (so their vertices may be joined). */
    private static boolean compatible() {
        if (!pendValid || !pendMerge || pendPipeline != curPipeline || pendTex0 != curTex0 || pendTex1 != curTex1
                || pendLayout != curLayout || pendStamp != curStamp) {
            return false;
        }
        for (int i = 0; i < PC_SIZE; i += 8) {
            if (MemoryUtil.memGetLong(pcCur + i) != MemoryUtil.memGetLong(pcPend + i)) {
                return false;
            }
        }
        return true;
    }

    /** A stream draw: vertices (and for K_INDEXED the indices) are copied to this frame's rings, then committed. */
    private static void issueStream(int mode, boolean flat, long idxAddr, int idxType, int idxMin, int idxCount) {
        final VertexLayout layout = ArrayData.layout;
        final int stride = ArrayData.stride;
        final int allocBytes = ArrayData.allocBytes;
        final int vcount = allocBytes / stride;
        final int kind = Primitives.kind;
        final int topology = Primitives.topology;
        final int ic = Primitives.indexCount;
        final int nVertices = Primitives.vertices;

        beginDraw(layout, topology);
        vertexArena.alloc(allocBytes);
        final long vbuf = vertexArena.outBuffer;
        final long voff = vertexArena.outOffset;
        final long vaddr = vertexArena.outAddress;
        MemoryUtil.memCopy(ArrayData.src, vaddr, ArrayData.srcBytes);
        if (allocBytes > ArrayData.srcBytes) {
            MemoryUtil.memSet(vaddr + ArrayData.srcBytes, 0, allocBytes - ArrayData.srcBytes);
        }
        switch (kind) {
            case Primitives.K_DIRECT:
                if (compatible() && pendKind == Primitives.K_DIRECT && pendVbuf == vbuf && pendVend == voff) {
                    pendVertexCount += vcount;
                    pendVend = voff + (long) vcount * stride;
                } else {
                    flushPending();
                    start(Primitives.K_DIRECT, vbuf, voff, vcount, stride, 0L, 0L, 0, true);
                }
                break;
            case Primitives.K_QUADS: {
                int quads = vcount >> 2;
                StaticBuffer ib = quadIndices(flat, quads);
                int cap = flat ? quadFlatCap : quadSmoothCap;
                long ibuf = ib.buffer();
                long ioff = ib.offset();
                if (compatible() && pendKind == Primitives.K_QUADS && pendVbuf == vbuf && pendVend == voff && pendIbuf == ibuf
                        && pendIoff == ioff && (pendVertexCount + vcount) / 4 <= cap) {
                    pendVertexCount += vcount;
                    pendIndexCount += ic;
                    pendVend = voff + (long) vcount * stride;
                } else {
                    flushPending();
                    start(Primitives.K_QUADS, vbuf, voff, vcount, stride, ibuf, ioff, ic, true);
                }
                break;
            }
            default: {
                indexArena.alloc(ic * 4);
                long ibuf = indexArena.outBuffer;
                long ioff = indexArena.outOffset;
                boolean merge = compatible() && pendKind == Primitives.K_INDEXED && pendVbuf == vbuf && pendVend == voff
                        && pendIbuf == ibuf && pendIend == ioff && (long) pendVertexCount + vcount < Integer.MAX_VALUE;
                int bias = merge ? pendVertexCount : 0;
                writeIndices(mode, nVertices, flat, indexArena.outAddress, idxAddr, idxType, idxMin, idxCount, bias);
                if (merge) {
                    pendVertexCount += vcount;
                    pendIndexCount += ic;
                    pendVend = voff + (long) vcount * stride;
                    pendIend = ioff + 4L * ic;
                } else {
                    flushPending();
                    start(Primitives.K_INDEXED, vbuf, voff, vcount, stride, ibuf, ioff, ic, true);
                }
                break;
            }
        }
    }

    /** Replays one captured display list draw from the list's static buffer. */
    static void issueStatic(DisplayLists.DrawItem it) {
        if (!initialized || !it.ready()) {
            return; // not running, or the list's geometry could not be uploaded
        }
        beginDraw(it.layout, it.topology);
        switch (it.kind) {
            case Primitives.K_DIRECT:
                if (compatible() && pendKind == Primitives.K_DIRECT && pendVbuf == it.vbuf && pendVend == it.voff) {
                    pendVertexCount += it.vertexCount;
                    pendVend = it.voff + (long) it.vertexCount * it.stride;
                } else {
                    flushPending();
                    start(Primitives.K_DIRECT, it.vbuf, it.voff, it.vertexCount, it.stride, 0L, 0L, 0, true);
                }
                break;
            case Primitives.K_QUADS: {
                int quads = it.vertexCount >> 2;
                StaticBuffer ib = quadIndices(curFlat, quads);
                int cap = curFlat ? quadFlatCap : quadSmoothCap;
                long ibuf = ib.buffer();
                long ioff = ib.offset();
                int ic = quads * 6;
                if (compatible() && pendKind == Primitives.K_QUADS && pendVbuf == it.vbuf && pendVend == it.voff && pendIbuf == ibuf
                        && pendIoff == ioff && (pendVertexCount + it.vertexCount) / 4 <= cap) {
                    pendVertexCount += it.vertexCount;
                    pendIndexCount += ic;
                    pendVend = it.voff + (long) it.vertexCount * it.stride;
                } else {
                    flushPending();
                    start(Primitives.K_QUADS, it.vbuf, it.voff, it.vertexCount, it.stride, ibuf, ioff, ic, true);
                }
                break;
            }
            default:
                // baked indices are relative to the item's own vertices: never joined with another draw; the list for the shade model
                // of this replay (curFlat) is used, whatever the shade model was when the list was compiled
                flushPending();
                start(Primitives.K_INDEXED, it.vbuf, it.voff, it.vertexCount, it.stride, it.vbuf,
                        curFlat && it.ioffFlat != 0L ? it.ioffFlat : it.ioff, it.indexCount, false);
                break;
        }
    }

    /** The draw being prepared becomes the pending draw (the pending draw was recorded or merged before). */
    private static void start(int kind, long vbuf, long voff, int vcount, int stride, long ibuf, long ioff, int icount, boolean mergeable) {
        pendValid = true;
        pendMerge = mergeable && defer;
        pendKind = kind;
        pendPipeline = curPipeline;
        pendTrace = curTrace;
        pendTex0 = curTex0;
        pendTex1 = curTex1;
        pendStamp = curStamp;
        pendLayout = curLayout;
        pendVbuf = vbuf;
        pendVoff = voff;
        pendVend = voff + (long) vcount * stride;
        pendVertexCount = vcount;
        pendIbuf = ibuf;
        pendIoff = ioff;
        pendIndexCount = icount;
        pendIend = ioff + 4L * icount;
        long t = pcPend;
        pcPend = pcCur;
        pcCur = t;
        if (!defer) {
            flushPending();
        }
    }

    private static void flushPending() {
        if (!pendValid) {
            return;
        }
        pendValid = false; // before recording: a re-entrant call must not submit the draw twice
        final long pipeline = pendPipeline;
        final long tex0 = pendTex0;
        final long tex1 = pendTex1;
        final long vbuf = pendVbuf;
        final long voff = pendVoff;
        final long ibuf = pendIbuf;
        final long ioff = pendIoff;
        final long pc = pcPend;
        final int kind = pendKind;
        final int vcount = pendVertexCount;
        final int icount = pendIndexCount;
        if (pendTrace != null) {
            VkLog.LOGGER.info("[trace f{}] draw kind={} vertices={} indices={} pipeline={} {}", RenderCore.frameNumber(), kind, vcount,
                    icount, pipeline, pendTrace);
            pendTrace = null;
        }
        CommandRecorder.bindPipeline(pipeline);
        CommandRecorder.bindTexture(0, tex0);
        CommandRecorder.bindTexture(1, tex1);
        CommandRecorder.pushConstants(pc);
        CommandRecorder.bindVertexBuffer(0, vbuf, voff);
        if (kind == Primitives.K_DIRECT) {
            CommandRecorder.draw(vcount, 0);
        } else {
            CommandRecorder.bindIndexBuffer(ibuf, ioff, VK10.VK_INDEX_TYPE_UINT32);
            CommandRecorder.drawIndexed(icount, 0, 0);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // static quad index buffers
    // ------------------------------------------------------------------------------------------------------------------------

    /** The shared quad index buffer (flat or smooth pattern) with room for at least {@code quads} quads; it is rebuilt bigger when needed. */
    private static StaticBuffer quadIndices(boolean flat, int quads) {
        StaticBuffer sb = flat ? quadFlat : quadSmooth;
        int cap = flat ? quadFlatCap : quadSmoothCap;
        if (sb != null && quads <= cap) {
            return sb;
        }
        int newCap = QUAD_INITIAL;
        while (newCap < quads) {
            newCap <<= 1;
        }
        ByteBuffer data = MemoryUtil.memAlloc(newCap * 24);
        long p = MemoryUtil.memAddress(data);
        for (int q = 0; q < newCap; q++, p += 24) {
            int b = q << 2;
            if (flat) {
                MemoryUtil.memPutInt(p, b + 3);
                MemoryUtil.memPutInt(p + 4, b);
                MemoryUtil.memPutInt(p + 8, b + 1);
                MemoryUtil.memPutInt(p + 12, b + 3);
                MemoryUtil.memPutInt(p + 16, b + 1);
                MemoryUtil.memPutInt(p + 20, b + 2);
            } else {
                MemoryUtil.memPutInt(p, b);
                MemoryUtil.memPutInt(p + 4, b + 1);
                MemoryUtil.memPutInt(p + 8, b + 2);
                MemoryUtil.memPutInt(p + 12, b);
                MemoryUtil.memPutInt(p + 16, b + 2);
                MemoryUtil.memPutInt(p + 20, b + 3);
            }
        }
        StaticBuffer created;
        try {
            created = RenderCore.createStatic(data, VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        } finally {
            MemoryUtil.memFree(data);
        }
        if (sb != null) {
            sb.free(); // deferred until the frames that may still use it have completed
        }
        if (flat) {
            quadFlat = created;
            quadFlatCap = newCap;
        } else {
            quadSmooth = created;
            quadSmoothCap = newCap;
        }
        return created;
    }
}

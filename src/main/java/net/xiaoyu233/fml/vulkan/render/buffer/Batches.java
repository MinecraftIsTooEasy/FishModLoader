package net.xiaoyu233.fml.vulkan.render.buffer;

import java.util.function.Consumer;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.gpu.PassUniforms;
import net.xiaoyu233.fml.vulkan.render.legacy.LegacyInterop;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.render.state.MaterialKey;
import net.xiaoyu233.fml.vulkan.render.state.StaticDrawHandler;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import net.xiaoyu233.fml.vulkan.render.state.RenderType;
import net.xiaoyu233.fml.vulkan.render.testing.RecordedDraw;

/**
 * Phase-aware batching of finalised vertices (1.8). One ordered stream of entries per phase lives in preallocated parallel arrays; each
 * entry is a RenderType plus dynamic values (line width, polygon offset) plus a vertex range of a pooled {@link BufferBuilder} (or a static
 * mesh). In ORDERED phases consecutive equal entries merge (the builder is simply extended). In the batched phase (ENTITIES) geometry whose
 * type is not order sensitive goes to one builder per (RenderType, dynamic values); order-sensitive geometry joins the ordered stream.
 * Flush order: batched buffers in first-use order, then the ordered stream. Texture descriptor sets are captured when an entry is closed.
 * <p>Threading: render thread only (no synchronisation); the test capture sink is the only volatile state.
 */
public final class Batches {
    private static final int KIND_STREAM = 0, KIND_STATIC = 1;
    private static final int FORMATS = VertexFormat.values().length;

    private static volatile Consumer<RecordedDraw> testSink;

    // ---- entry storage (parallel arrays, grown geometrically, reused every frame)
    private static int n;
    private static RenderType[] eType = new RenderType[64];
    private static BufferBuilder[] eBuilder = new BufferBuilder[64];
    private static StaticMesh[] eMesh = new StaticMesh[64];
    private static int[] eStart = new int[64];
    private static int[] eCount = new int[64];
    private static int[] eColor = new int[64];
    private static byte[] eKind = new byte[64];
    private static boolean[] eBatched = new boolean[64];
    private static float[] eLine = new float[64];
    private static float[] ePolyF = new float[64];
    private static float[] ePolyU = new float[64];
    private static long[] eTex = new long[64];
    private static long[] eLight = new long[64];
    private static float[] eModel = new float[64 * 16];

    /** Entry ids of batched entries in first-use order. */
    private static int[] batchedIds = new int[16];
    private static int batchedN;
    private static int cur = -1;

    /** Builders idle for this many consecutive frames at under a quarter of their capacity are freed (only above {@link #SHRINK_MIN_BYTES}). */
    private static final int SHRINK_AFTER_FRAMES = 60;
    private static final long SHRINK_MIN_BYTES = 4L << 20;
    private static final long INITIAL_BYTES = 1L << 16;

    /** A pooled builder plus the usage bookkeeping that drives shrinking. {@code high} approximates capacity (growth doubles). */
    private static final class Pooled {
        final BufferBuilder b;
        long high;
        long frameUse;
        int quietFrames;

        Pooled(BufferBuilder b) {
            this.b = b;
        }
    }

    private static final Pooled[] ORDERED_BUILDERS = new Pooled[FORMATS];
    private static Pooled[] pool = new Pooled[8];
    private static int poolN, poolTotal;

    private static boolean flushing;
    private static int pendingProjVersion, pendingFogVersion;
    private static int draws, quads, lastDraws, lastQuads;

    static {
        install();
    }

    private Batches() {
    }

    /** Registers the {@link StaticDrawHandler}; idempotent. */
    static void install() {
        Draw.setStaticDrawHandler(Batches::enqueueStatic);
    }

    /**
     * Registers the frame listener on first use of the GPU path (not in the static initialiser: RenderCore may not be set up yet). Not
     * registered from Frame: Frame only reaches its listener through clear/pushTarget, so a frame without either would never rotate.
     */
    private static final RenderCore.FrameListener LISTENER = new RenderCore.FrameListener() {
        @Override
        public void onFrameBegin() {
            Batches.onFrameBegin();
        }

        @Override
        public void onCommandBufferReset() {
            BatchRecorder.onFrameBegin();
        }
    };

    private static void ensureListener() {
        // addListener dedups by identity, so this survives RenderCore listener resets after re-init.
        RenderCore.addListener(LISTENER);
    }

    /**
     * Consumer for the vertices of {@code type}. While a {@link #testCapture} sink is installed every call creates a new
     * {@link RecordedDraw}, hands it to the sink at once (the object is filled as the caller writes vertices) and returns it.
     * Otherwise the returned object is the pooled {@link BufferBuilder} of the entry; it is valid until the next call of this class.
     */
    public static VertexConsumer consumer(RenderType type) {
        Consumer<RecordedDraw> sink = testSink;
        if (sink != null) {
            RecordedDraw draw = new RecordedDraw(type);
            sink.accept(draw);
            return draw;
        }
        ensureListener();
        syncPassState();
        long key = type.materialKey();
        float lw = type.format() == VertexFormat.LINE ? Draw.lineWidthPx() : 0f;
        float pf = 0f, pu = 0f;
        if (MaterialKey.polygonOffset(key)) {
            pf = Draw.polygonOffsetFactor();
            pu = Draw.polygonOffsetUnits();
        }
        if (Frame.phase().batched() && !type.orderSensitive()) {
            for (int i = 0; i < batchedN; i++) {
                int e = batchedIds[i];
                if (eType[e] == type && eLine[e] == lw && ePolyF[e] == pf && ePolyU[e] == pu) {
                    return eBuilder[e];
                }
            }
            int e = newEntry(type, KIND_STREAM, lw, pf, pu);
            eBatched[e] = true;
            eBuilder[e] = takeBuilder(type.format());
            eStart[e] = 0;
            if (batchedN == batchedIds.length) {
                batchedIds = java.util.Arrays.copyOf(batchedIds, batchedN * 2);
            }
            batchedIds[batchedN++] = e;
            return eBuilder[e];
        }
        int c = cur;
        if (c >= 0 && eType[c] == type && eLine[c] == lw && ePolyF[c] == pf && ePolyU[c] == pu) {
            return eBuilder[c];
        }
        closeCurrent();
        int fi = type.format().ordinal();
        Pooled p = ORDERED_BUILDERS[fi];
        if (p == null) {
            p = ORDERED_BUILDERS[fi] = new Pooled(new BufferBuilder(type.format(), (int) INITIAL_BYTES));
        }
        BufferBuilder b = p.b;
        int e = newEntry(type, KIND_STREAM, lw, pf, pu);
        eBuilder[e] = b;
        eStart[e] = b.vertexCount();
        cur = e;
        return b;
    }

    /** Tees every finalised vertex block into {@code sink} instead of the GPU; null switches the capture off. */
    public static void testCapture(Consumer<RecordedDraw> sink) {
        testSink = sink;
    }

    /** True while a test capture sink is installed. */
    public static boolean isTestCapturing() {
        return testSink != null;
    }

    /**
     * Enqueues an ordered entry for a static mesh (called by the {@code StaticDrawHandler} installed by {@link #install}).
     * {@code model} is copied; no allocation per call.
     */
    static void enqueueStatic(StaticMesh mesh, RenderType type, float[] model, int colorAbgr) {
        if (testSink != null) {
            return;
        }
        ensureListener();
        syncPassState();
        closeCurrent();
        int e = newEntry(type, KIND_STATIC, 0f, 0f, 0f);
        eMesh[e] = mesh;
        eColor[e] = colorAbgr;
        System.arraycopy(model, 0, eModel, e * 16, 16);
        eTex[e] = texSet(type.texture());
        eLight[e] = texSet(type.lightmapTexture());
    }

    /**
     * Frame restart hook (called by the {@code RenderCore.FrameListener} registered by {@link #ensureListener}): drops per-frame entry
     * state (entries still pending were not flushed and must not be replayed against the new frame) and rotates the draw/quad counters.
     * Also resets the {@link BatchRecorder} caches.
     */
    public static void onFrameBegin() {
        lastDraws = draws;
        lastQuads = quads;
        draws = 0;
        quads = 0;
        reset();
        trimIdle();
        BatchRecorder.onFrameBegin();
    }

    /** Flushes all pending entries when one of them samples {@code textureId} (texture upload or sampler change). */
    public static void flushIfTextureReferenced(int textureId) {
        if (n == 0 || testSink != null) {
            return;
        }
        for (int i = 0; i < n; i++) {
            RenderType t = eType[i];
            if (t.texture() == textureId || t.lightmapTexture() == textureId) {
                flushPhase();
                return;
            }
        }
    }

    /** Called by {@code Frame}: closes every entry, records them (batched buffers first, then the ordered stream) and clears the builders. */
    public static void flushPhase() {
        if (flushing || testSink != null) {
            return;
        }
        closeCurrent();
        if (n == 0) {
            return;
        }
        flushing = true;
        try {
            LegacyInterop.beforeNativeRecord();
            for (int i = 0; i < batchedN; i++) {
                int e = batchedIds[i];
                eStart[e] = 0;
                eCount[e] = eBuilder[e].vertexCount();
                eTex[e] = texSet(eType[e].texture());
                eLight[e] = texSet(eType[e].lightmapTexture());
                record(e);
            }
            for (int e = 0; e < n; e++) {
                if (!eBatched[e]) {
                    record(e);
                }
            }
        } finally {
            flushing = false;
            reset();
        }
    }

    public static int drawsLastFrame() {
        return lastDraws;
    }

    public static int quadsLastFrame() {
        return lastQuads;
    }

    // ------------------------------------------------------------------------------------------------ internals

    /**
     * Projection / fog flush trigger and pass-uniform feed. Pending entries are drawn at flush time with the PassUniforms current then, so
     * when the projection or fog version differs from the one the pending entries were queued under they are flushed first (PassUniforms
     * still holds their values), then the new Draw state is copied in. With an empty queue the state is always re-copied (cheap, change
     * detected inside PassUniforms), which also survives PassUniforms.reset().
     */
    private static void syncPassState() {
        var proj = Draw.projection();
        int pv = proj.version();
        int fv = Draw.fogVersion();
        if (n > 0) {
            if (pv == pendingProjVersion && fv == pendingFogVersion) {
                return;
            }
            flushPhase();
            if (n > 0) {
                return;
            }
        }
        pendingProjVersion = pv;
        pendingFogVersion = fv;
        PassUniforms.setProjection(proj.top());
        int mode = !Draw.fogEnabled() ? 0 : switch (Draw.fogModeState()) {
            case LINEAR -> 1;
            case EXP -> 2;
            case EXP2 -> 3;
        };
        PassUniforms.setFog(Draw.fogColorComponent(0), Draw.fogColorComponent(1), Draw.fogColorComponent(2), Draw.fogColorComponent(3),
                Draw.fogStartValue(), Draw.fogEndValue(), Draw.fogDensityValue(), mode);
    }

    private static final float[] MODEL_SCRATCH = new float[16];

    private static void record(int e) {
        if (eKind[e] == KIND_STATIC) {
            System.arraycopy(eModel, e * 16, MODEL_SCRATCH, 0, 16);
            BatchRecorder.drawStatic(eMesh[e], eType[e], MODEL_SCRATCH, eColor[e], eTex[e], eLight[e]);
            draws++;
            quads += eMesh[e].quadCount();
            return;
        }
        int count = eCount[e];
        if (count <= 0) {
            return;
        }
        BufferBuilder b = eBuilder[e];
        long addr = b.address() + (long) eStart[e] * eType[e].format().stride();
        BatchRecorder.drawStream(eType[e], addr, count, eTex[e], eLight[e], eLine[e], ePolyF[e], ePolyU[e]);
        int q = count >> 2;
        quads += q;
        draws += (q + QuadIndexBuffers.MAX_QUADS_U16 - 1) / QuadIndexBuffers.MAX_QUADS_U16;
    }

    private static long texSet(int id) {
        return id == 0 ? 0L : Textures.descriptorSet(id);
    }

    /** Closes the open ordered entry: fixes its vertex count and captures the texture descriptor sets (1.11). */
    private static void closeCurrent() {
        int c = cur;
        if (c < 0) {
            return;
        }
        cur = -1;
        eCount[c] = eBuilder[c].vertexCount() - eStart[c];
        eTex[c] = texSet(eType[c].texture());
        eLight[c] = texSet(eType[c].lightmapTexture());
    }

    private static int newEntry(RenderType type, int kind, float lw, float pf, float pu) {
        if (n == eType.length) {
            int cap = n * 2;
            eType = java.util.Arrays.copyOf(eType, cap);
            eBuilder = java.util.Arrays.copyOf(eBuilder, cap);
            eMesh = java.util.Arrays.copyOf(eMesh, cap);
            eStart = java.util.Arrays.copyOf(eStart, cap);
            eCount = java.util.Arrays.copyOf(eCount, cap);
            eColor = java.util.Arrays.copyOf(eColor, cap);
            eKind = java.util.Arrays.copyOf(eKind, cap);
            eBatched = java.util.Arrays.copyOf(eBatched, cap);
            eLine = java.util.Arrays.copyOf(eLine, cap);
            ePolyF = java.util.Arrays.copyOf(ePolyF, cap);
            ePolyU = java.util.Arrays.copyOf(ePolyU, cap);
            eTex = java.util.Arrays.copyOf(eTex, cap);
            eLight = java.util.Arrays.copyOf(eLight, cap);
            eModel = java.util.Arrays.copyOf(eModel, cap * 16);
        }
        int e = n++;
        eType[e] = type;
        eKind[e] = (byte) kind;
        eBatched[e] = false;
        eBuilder[e] = null;
        eMesh[e] = null;
        eStart[e] = 0;
        eCount[e] = 0;
        eLine[e] = lw;
        ePolyF[e] = pf;
        ePolyU[e] = pu;
        eTex[e] = 0L;
        eLight[e] = 0L;
        return e;
    }

    private static BufferBuilder takeBuilder(VertexFormat f) {
        // pool[0, poolN) handed out in this phase; pool[poolN, poolTotal) idle builders kept for reuse
        for (int i = poolN; i < poolTotal; i++) {
            Pooled p = pool[i];
            if (p.b.format() == f) {
                pool[i] = pool[poolN];
                pool[poolN++] = p;
                return p.b;
            }
        }
        if (poolTotal == pool.length) {
            pool = java.util.Arrays.copyOf(pool, poolTotal * 2);
        }
        Pooled p = new Pooled(new BufferBuilder(f, (int) INITIAL_BYTES));
        pool[poolTotal] = pool[poolN];
        pool[poolN++] = p;
        poolTotal++;
        return p.b;
    }

    /** Records one builder's use since its last reset (bytes it holds) and clears it; the memory stays allocated. */
    private static void account(Pooled p) {
        int used = p.b.bytes();
        p.frameUse += used;
        if (used > p.high) {
            p.high = used;
        }
        p.b.clear();
    }

    /** Clears entries and builders; builders stay allocated until {@link #trimIdle} decides otherwise. */
    private static void reset() {
        for (int i = 0; i < n; i++) {
            eBuilder[i] = null;
            eMesh[i] = null;
            eType[i] = null;
        }
        for (int i = 0; i < poolN; i++) {
            account(pool[i]);
        }
        poolN = 0;
        for (Pooled p : ORDERED_BUILDERS) {
            if (p != null) {
                account(p);
            }
        }
        n = 0;
        batchedN = 0;
        cur = -1;
    }

    /**
     * Once per frame (after {@link #reset}): a builder whose estimated capacity is at least {@link #SHRINK_MIN_BYTES} and whose use this
     * frame stayed under a quarter of it counts a quiet frame; after {@link #SHRINK_AFTER_FRAMES} in a row it is freed. Freed ordered
     * builders are re-created on demand, so a single huge phase no longer pins its memory for the process lifetime.
     */
    private static void trimIdle() {
        for (int f = 0; f < FORMATS; f++) {
            Pooled p = ORDERED_BUILDERS[f];
            if (p != null && shouldFree(p)) {
                p.b.free();
                ORDERED_BUILDERS[f] = null;
            }
        }
        int keep = 0;
        for (int i = 0; i < poolTotal; i++) {
            Pooled p = pool[i];
            if (shouldFree(p)) {
                p.b.free();
            } else {
                pool[keep++] = p;
            }
        }
        for (int i = keep; i < poolTotal; i++) {
            pool[i] = null;
        }
        poolTotal = keep;
        poolN = 0;
    }

    private static boolean shouldFree(Pooled p) {
        long cap = INITIAL_BYTES;
        while (cap < p.high) {
            cap <<= 1;
        }
        boolean quiet = cap >= SHRINK_MIN_BYTES && p.frameUse * 4 < cap;
        p.quietFrames = quiet ? p.quietFrames + 1 : 0;
        p.frameUse = 0;
        return p.quietFrames >= SHRINK_AFTER_FRAMES;
    }
}

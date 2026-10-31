package net.xiaoyu233.fml.vulkan.render.gpu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.pipeline.Pipelines;
import net.xiaoyu233.fml.vulkan.pipeline.ShaderInterface;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.MaterialKey;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTExtendedDynamicState;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSpecializationInfo;
import org.lwjgl.vulkan.VkSpecializationMapEntry;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

/**
 * Baked pipelines of the native programs with a persistent cache and asynchronous misses (ARCHITECTURE-NATIVE 1.9, 5.6).
 *
 * <p>A pipeline is identified by its <em>pipeline key</em>: the material key ({@link MaterialKey}) without texture ids, with the
 * program in bits 0-3, normalised so that state without effect (blend factors while blending is off, the alpha reference of a dynamic
 * or absent alpha test, the depth function while the depth test is off, ...) does not multiply pipelines. With extended dynamic state
 * the depth test/write/function and the cull state are dynamic and masked out of the key ({@link MaterialKey#pipelineKey}).
 *
 * <p>Creation policy: one {@code VkPipelineCache} file per device ({@code <gameDir>/.fml/vulkan/native-pipeline-cache.bin}, validated
 * against vendor/device/driver UUID), a small GUI bootstrap set created synchronously by {@link #init()}, then the shipped seed list
 * ({@code /assets/fml/native-pipelines.seed}) and the per-user journal ({@code <gameDir>/.fml/vulkan/native-pipelines.txt}) created on
 * a private worker pool. {@link #get} never compiles on the render thread after the bootstrap: a miss queues the creation and returns
 * the nearest already created pipeline (a <em>fallback</em>) or {@code 0} (skip the draw); the exact pipeline is returned as soon as the
 * worker is done. All methods are render-thread only except where noted; the workers never touch render-thread state.
 */
public final class PipelineLibrary {
    private static final String SHADER_DIR = "/assets/fml_vulkan/shaders/native/";
    private static final String SEED_RESOURCE = "/assets/fml/native-pipelines.seed";
    private static final int CACHE_HEADER_SIZE = 32;
    private static final long MAX_CACHE_FILE_BYTES = 256L << 20;
    private static final long CACHE_SAVE_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int MAX_JOURNAL_LINES = 50_000;
    private static final int MAX_FAILURE_LOGS = 16;
    private static final long SKIP = -1L;

    // material key bit groups (MaterialKey layout)
    private static final long BIT_TEXTURED = 1L << 4;
    private static final long BIT_LIGHTMAP = 1L << 5;
    private static final long BIT_FOG = 1L << 6;
    private static final long BIT_OVERLAY = 1L << 7;
    private static final long ALPHA_MODE_BITS = 3L << 8;
    private static final long ALPHA_REF_BITS = 0xFFL << 10;
    private static final long BIT_BLEND = 1L << 18;
    private static final long BLEND_FACTOR_BITS = 0xFFL << 19;
    private static final long BIT_DEPTH_TEST = 1L << 27;
    private static final long DEPTH_FUNC_BITS = 7L << 28;
    private static final long BIT_DEPTH_WRITE = 1L << 31;
    private static final long BIT_CULL = 1L << 32;
    private static final long COLOR_MASK_BITS = 7L << 33;
    private static final long BIT_LOGIC_OP = 1L << 37;
    private static final long BIT_POLYGON_OFFSET = 1L << 36;
    private static final long BIT_CULL_FRONT = 1L << 38;
    private static final long USED_BITS = (1L << 39) - 1L;

    /** Vulkan blend factor of each {@link BlendFactor} ordinal. */
    private static final int[] VK_BLEND = {
            VK10.VK_BLEND_FACTOR_ZERO, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_SRC_COLOR, VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR,
            VK10.VK_BLEND_FACTOR_SRC_ALPHA, VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK10.VK_BLEND_FACTOR_DST_ALPHA,
            VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA, VK10.VK_BLEND_FACTOR_DST_COLOR, VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR
    };

    /** Alpha references 0..3 are fixed by the design (1.7); further indices come from {@code render.state.AlphaRefs} when it exists. */
    private static final float[] BASE_ALPHA_REFS = {0.1F, 1.0F / 255.0F, 0.01F, 0.001F};

    private record Created(long key, long handle, long nanos, int result, boolean warmUp) {
    }

    // ---- state (render thread unless noted) ----
    private static boolean initialized;
    private static volatile int generation;
    private static boolean dynamicDepthCull;
    private static boolean logicOpSupported;
    private static long pipelineLayout;
    private static long pipelineCache;
    private static final long[] VERT_MODULES = new long[Program.values().length];
    private static final long[] FRAG_MODULES = new long[Program.values().length];

    private static HandleMap created = new HandleMap();
    private static long[] createdKeys = new long[256];
    private static long[] createdHandles = new long[256];
    private static volatile int createdCount;
    private static HandleMap fallbacks = new HandleMap();
    private static long lastKey = Long.MIN_VALUE;
    private static long lastHandle;
    private static long lastResult;
    private static boolean lastExact = true;

    /** Keys whose creation was started by anybody (render thread or worker); guards against duplicate creation. */
    private static Set<Long> claimed = ConcurrentHashMap.newKeySet();
    private static ConcurrentLinkedQueue<Created> completed = new ConcurrentLinkedQueue<>();
    private static ExecutorService pool;
    /** Priority lane: run-time misses are created here so that they never wait behind the seed/journal list. */
    private static ExecutorService missPool;
    /** Warm-up keys queued on {@link #pool} that no thread has started yet (a miss may take one over). */
    private static Set<Long> warmPending = ConcurrentHashMap.newKeySet();
    private static volatile boolean journalEnabled = true;

    // journal
    private static long[] journalNew = new long[64];
    private static int journalNewCount;
    private static int journalFlushed;

    // cache file
    private static final Object FILE_LOCK = new Object();
    private static volatile int savedPipelineCount;
    private static long lastSaveNanos;
    private static volatile boolean cacheLoaded;
    private static volatile long cacheLoadedBytes;

    // counters
    private static int createdTotal;
    private static long renderThreadCreations;
    private static long fallbackDraws;
    private static long skippedDraws;
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static AtomicInteger warmTotal = new AtomicInteger();
    private static AtomicInteger warmDone = new AtomicInteger();
    private static AtomicInteger warmIntegrated = new AtomicInteger();
    private static volatile long warmStartNanos;
    private static volatile long warmEndNanos;
    private static volatile boolean warmListed;
    private static volatile boolean bootstrapDone;
    private static java.lang.reflect.Method alphaRefLookup;
    private static boolean alphaRefLookupResolved;

    private PipelineLibrary() {
    }

    // ------------------------------------------------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Loads the pipeline cache, creates the layout and the GUI bootstrap set synchronously and queues the seed list and the journal on the
     * worker pool. Requires {@code RenderCore} and the phase-1 {@code Pipelines} (shared descriptor set layouts).
     */
    public static void init() {
        if (initialized) {
            VkLog.LOGGER.warn("PipelineLibrary.init() called while already initialized, ignored");
            return;
        }
        generation++;
        try {
            VkDevice device = RenderCore.device();
            dynamicDepthCull = DeviceFeatures.extendedDynamicState();
            logicOpSupported = DeviceFeatures.logicOp();
            resetState();
            createLayout(device);
            loadModules(device);
            pipelineCache = createPipelineCache(device);
            startPool();
            lastSaveNanos = System.nanoTime();
            initialized = true;
            warmStartNanos = System.nanoTime();
            bootstrapDone = false;
            createBootstrapSet();
            bootstrapDone = true;
            RenderCore.addListener(new Saver(generation));
            queueWarmUp(generation);
            VkLog.LOGGER.info("Native pipeline library ready: {} bootstrap pipelines, cache {}, dynamic depth/cull state {}", createdCount,
                    cacheLoaded ? "loaded (" + cacheLoadedBytes + " bytes)" : "empty", dynamicDepthCull ? "on" : "off");
        } catch (Throwable t) {
            initialized = true;     // let shutdown release what exists
            shutdown();
            throw t;
        }
    }

    /** Waits for the workers, stores the cache and the journal, destroys every pipeline, module and the layout. */
    public static void shutdown() {
        if (!initialized) {
            return;
        }
        initialized = false;
        generation++;
        VkDevice device = RenderCore.device();
        stopPool();
        drainCompleted();
        try {
            saveCacheNow(device);
        } catch (Throwable t) {
            VkLog.LOGGER.warn("Could not save the native pipeline cache", t);
        }
        if (VulkanConfig.debug() || VkLog.LOGGER.isDebugEnabled()) {
            VkLog.LOGGER.info("Native pipelines: {} created ({} on the render thread after start-up), {} fallback draws, {} skipped draws, "
                    + "{} failures", createdTotal, renderThreadCreations, fallbackDraws, skippedDraws, FAILURES.get());
        }
        for (int i = 0; i < createdCount; i++) {
            VK10.vkDestroyPipeline(device, createdHandles[i], null);
        }
        for (int i = 0; i < VERT_MODULES.length; i++) {
            if (VERT_MODULES[i] != 0L) {
                VK10.vkDestroyShaderModule(device, VERT_MODULES[i], null);
                VERT_MODULES[i] = 0L;
            }
            if (FRAG_MODULES[i] != 0L) {
                VK10.vkDestroyShaderModule(device, FRAG_MODULES[i], null);
                FRAG_MODULES[i] = 0L;
            }
        }
        if (pipelineCache != 0L) {
            VK10.vkDestroyPipelineCache(device, pipelineCache, null);
            pipelineCache = 0L;
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = 0L;
        }
        resetState();
    }

    public static boolean isInitialized() {
        return initialized;
    }

    private static void resetState() {
        created = new HandleMap();
        createdKeys = new long[256];
        createdHandles = new long[256];
        createdCount = 0;
        fallbacks = new HandleMap();
        lastKey = Long.MIN_VALUE;
        lastHandle = 0L;
        lastResult = 0L;
        lastExact = true;
        claimed = ConcurrentHashMap.newKeySet();
        completed = new ConcurrentLinkedQueue<>();
        journalNewCount = 0;
        journalFlushed = 0;
        savedPipelineCount = 0;
        cacheLoaded = false;
        cacheLoadedBytes = 0L;
        createdTotal = 0;
        renderThreadCreations = 0L;
        fallbackDraws = 0L;
        skippedDraws = 0L;
        FAILURES.set(0);
        warmTotal = new AtomicInteger();
        warmDone = new AtomicInteger();
        warmIntegrated = new AtomicInteger();
        warmPending = ConcurrentHashMap.newKeySet();
        warmStartNanos = 0L;
        warmEndNanos = 0L;
        warmListed = false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // public API
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The exact pipeline of {@code (p, pipelineKey)}, or on a miss: queues its asynchronous creation and returns the nearest created
     * pipeline (fallback; see {@link #isExact}) or {@code 0} when there is none (the caller skips the draw). Never blocks on a compile
     * after start-up unless {@code fml.vulkan.pipelineAsync=false}. {@code f} must be the vertex format of {@code p}
     * ({@link Program#vertexFormat()}); the program decides.
     *
     * <p>Caller obligations (see also {@link #layout}): (1) BLIT keys drop every depth/cull bit, so with extended dynamic state a blit draw
     * must call {@code setDynamicDepthCull(false, false, ALWAYS, NONE)} first, or it inherits the previous draw's state. (2) Every program
     * statically references sets 1 and 2 (inside spec-constant branches), so valid descriptors must be bound for them even when TEXTURED or
     * LIGHTMAP is false (bind dummy textures). (3) Polygon-offset keys bake depthBiasEnable but the bias values are dynamic: call
     * {@code vkCmdSetDepthBias} before each such draw.
     */
    public static long get(Program p, long pipelineKey, VertexFormat f) {
        if (!initialized) {
            return 0L;
        }
        if (!completed.isEmpty()) {
            drainCompleted();
        }
        long key = normalize(p, pipelineKey);
        if (key == lastKey) {
            lastResult = lastHandle;
            lastExact = true;
            return lastHandle;
        }
        long handle = created.get(key);
        if (handle != 0L) {
            lastKey = key;
            lastHandle = handle;
            lastResult = handle;
            lastExact = true;
            return handle;
        }
        return miss(p, key);
    }

    /**
     * False when the handle returned by the latest {@link #get} is a fallback (another key's pipeline); true for exact pipelines and for
     * any handle that was not just returned as a fallback. A fallback handle can also be the exact pipeline of another key, hence the
     * dependence on the latest call.
     */
    public static boolean isExact(long pipeline) {
        return !(pipeline == lastResult && !lastExact);
    }

    /** Stores the pipeline cache file and the journal; also called at shutdown and every 5 minutes when the cache grew. */
    public static void saveCache() {
        if (!initialized) {
            return;
        }
        try {
            saveCacheNow(RenderCore.device());
        } catch (Throwable t) {
            VkLog.LOGGER.warn("Could not save the native pipeline cache", t);
        }
    }

    /**
     * The pipeline layout (identical for every program: set 0 pass uniforms, set 1 texture, set 2 lightmap, 128 B push constants). Sets 1
     * and 2 must always hold valid descriptors at draw time, see {@link #get}.
     */
    public static long layout(Program p) {
        return pipelineLayout;
    }

    /** Path of the persistent {@code VkPipelineCache} file. */
    public static Path cacheFile() {
        return VulkanConfig.gameDir().resolve(".fml").resolve("vulkan").resolve("native-pipeline-cache.bin");
    }

    /** Test hook: false stops run-time misses from being written to the user's journal (pipelineTest uses random keys). */
    public static void setJournalEnabled(boolean enabled) {
        journalEnabled = enabled;
    }

    /** Path of the per-user journal of pipeline keys seen at run time. */
    public static Path journalFile() {
        return VulkanConfig.gameDir().resolve(".fml").resolve("vulkan").resolve("native-pipelines.txt");
    }

    /** The key {@link #get} looks up for {@code (p, key)} on this device (normalisation and dynamic-state masking applied). */
    public static long normalizedKey(Program p, long key) {
        return normalize(p, key);
    }

    /** True when the exact pipeline of the normalised key exists (render thread). */
    public static boolean hasExact(Program p, long key) {
        if (!completed.isEmpty()) {
            drainCompleted();
        }
        return created.get(normalize(p, key)) != 0L;
    }

    /** Pipelines created so far (bootstrap, seed, journal, misses). */
    public static int createdCount() {
        return createdCount;
    }

    /** Creations that happened on the render thread after the GUI bootstrap (target 0; only with pipelineAsync=false). */
    public static long renderThreadCreations() {
        return renderThreadCreations;
    }

    /** {@code pipelineMissesFallback}: draws served by a fallback pipeline since start (cumulative). */
    public static long fallbackDraws() {
        return fallbackDraws;
    }

    /** {@code pipelineMissesSkipped}: draws skipped for lack of any fallback since start (cumulative). */
    public static long skippedDraws() {
        return skippedDraws;
    }

    /** Keys whose creation was requested but not yet integrated into the render-thread table. */
    public static int pendingCount() {
        return Math.max(0, claimed.size() - createdCount - FAILURES.get());
    }

    /** True once the seed list and the journal were read and every listed key was created (or failed). */
    public static boolean isWarm() {
        return warmListed && warmIntegrated.get() >= warmTotal.get();
    }

    /** Number of seed + journal entries queued at start-up (valid once {@link #isWarm()} or the list was read). */
    public static int warmUpTotal() {
        return warmTotal.get();
    }

    /** Milliseconds from {@link #init()} to the end of the warm-up (to now while it is running). */
    public static double warmUpMillis() {
        long start = warmStartNanos;
        long end = warmEndNanos != 0L ? warmEndNanos : System.nanoTime();
        return start == 0L ? 0.0 : (end - start) / 1.0e6;
    }

    /** True when {@link #init()} found and loaded a valid cache file. */
    public static boolean cacheWasLoaded() {
        return cacheLoaded;
    }

    /** Creation failures (shaders the driver refused, missing optional modules). */
    public static int failureCount() {
        return FAILURES.get();
    }

    // ------------------------------------------------------------------------------------------------------------
    // miss path
    // ------------------------------------------------------------------------------------------------------------

    private static long miss(Program p, long key) {
        if (!VulkanConfig.pipelineAsync() && warmDone.get() >= warmTotal.get()) {
            long handle = createSynchronously(key);
            if (handle != 0L) {
                lastKey = key;
                lastHandle = handle;
                lastResult = handle;
                lastExact = true;
                return handle;
            }
        }
        long fb = fallbacks.get(key);
        if (fb == 0L) {
            final int gen = generation;
            if (claimed.add(key)) {
                recordJournal(key);
                missPool.execute(() -> createOnWorker(gen, key, false));
            } else if (warmPending.remove(key)) {
                // listed by the warm-up but not started yet: promote it to the priority lane (the warm-up task skips it)
                missPool.execute(() -> createOnWorker(gen, key, true));
            }
            fb = findFallback(p, key);
            fallbacks.put(key, fb == 0L ? SKIP : fb);
        }
        lastResult = fb == SKIP ? 0L : fb;
        lastExact = false;
        if (fb == SKIP) {
            skippedDraws++;
            return 0L;
        }
        fallbackDraws++;
        return fb;
    }

    private static long createSynchronously(long key) {
        if (!claimed.add(key)) {
            return 0L;
        }
        recordJournal(key);
        long handle;
        try {
            handle = createPipeline(key);
        } catch (IllegalArgumentException e) {
            logFailure(key, e.getMessage());
            handle = 0L;
        }
        renderThreadCreations++;
        if (handle != 0L) {
            addCreated(key, handle);
        } else {
            FAILURES.incrementAndGet();
        }
        return handle;
    }

    /**
     * Nearest created pipeline for a key that does not exist yet (1.9): (1) the same key with the dynamic depth/cull state masked, (2) the
     * same program, blend setup and alpha mode, (3) for opaque keys the same program without regard to fog and lightmap. The candidate with
     * the fewest differing key bits wins inside a step; 0 when nothing qualifies.
     */
    private static long findFallback(Program p, long key) {
        long dynMask = BIT_DEPTH_TEST | DEPTH_FUNC_BITS | BIT_DEPTH_WRITE | BIT_CULL | BIT_CULL_FRONT;
        long blendMask = BIT_BLEND | BLEND_FACTOR_BITS | ALPHA_MODE_BITS;
        long mustMatch = BIT_TEXTURED | BIT_LOGIC_OP | BIT_POLYGON_OFFSET;
        boolean opaque = (key & BIT_BLEND) == 0L;
        long step1 = 0L;
        long step2 = 0L;
        long step3 = 0L;
        int best1 = 65;
        int best2 = 65;
        int best3 = 65;
        for (int i = 0; i < createdCount; i++) {
            long k = createdKeys[i];
            if (Program.ofOrdinal(MaterialKey.program(k)) != p) {
                continue;
            }
            int distance = Long.bitCount(k ^ key);
            if ((k & ~dynMask) == (key & ~dynMask)) {
                if (distance < best1) {
                    best1 = distance;
                    step1 = createdHandles[i];
                }
            } else if ((k & mustMatch) != (key & mustMatch) || (k & COLOR_MASK_BITS) == 0L != ((key & COLOR_MASK_BITS) == 0L)) {
                continue;       // never serve a draw with a pipeline that is textured differently, depth-only, logic-op or offset
            } else if ((k & blendMask) == (key & blendMask)) {
                if (distance < best2) {
                    best2 = distance;
                    step2 = createdHandles[i];
                }
            } else if (opaque && (k & BIT_BLEND) == 0L 
                    && (k & COLOR_MASK_BITS) == (key & COLOR_MASK_BITS)) {
                if (distance < best3) {
                    best3 = distance;
                    step3 = createdHandles[i];
                }
            }
        }
        return step1 != 0L ? step1 : step2 != 0L ? step2 : step3;
    }

    // ------------------------------------------------------------------------------------------------------------
    // key normalisation
    // ------------------------------------------------------------------------------------------------------------

    private static long normalize(Program p, long key) {
        long k = (key & USED_BITS & ~0xFL) | p.ordinal();
        if (dynamicDepthCull) {
            k = MaterialKey.pipelineKey(k, true);
        }
        if (p == Program.BLIT) {
            return Program.BLIT.ordinal() | (k & BIT_LIGHTMAP) | COLOR_MASK_BITS;
        }
        if ((k & BIT_LOGIC_OP) != 0L) {
            if (logicOpSupported) {
                k &= ~(BIT_BLEND | BLEND_FACTOR_BITS);       // a logic op replaces blending
            } else {
                k &= ~BIT_LOGIC_OP;
            }
        }
        if ((k & BIT_BLEND) == 0L) {
            k &= ~BLEND_FACTOR_BITS;
        }
        if ((k & ALPHA_MODE_BITS) != (1L << 8)) {
            k &= ~ALPHA_REF_BITS;
        }
        if ((k & BIT_CULL) == 0L) {
            k &= ~BIT_CULL_FRONT;
        }
        if (!dynamicDepthCull && (k & BIT_DEPTH_TEST) == 0L) {
            k &= ~(DEPTH_FUNC_BITS | BIT_DEPTH_WRITE);
        }
        if (p == Program.LINES) {
            k &= ~(BIT_TEXTURED | BIT_LIGHTMAP | BIT_OVERLAY | ALPHA_MODE_BITS | ALPHA_REF_BITS);
        }
        if ((k & COLOR_MASK_BITS) == 0L) {
            k &= ~(BIT_FOG | BIT_LIGHTMAP | BIT_OVERLAY);     // depth-only draw: the colour is never written
        }
        return k;
    }

    private static float alphaRef(int index) {
        if (index < BASE_ALPHA_REFS.length) {
            return BASE_ALPHA_REFS[index];
        }
        if (!alphaRefLookupResolved) {
            alphaRefLookupResolved = true;
            try {
                alphaRefLookup = Class.forName("net.xiaoyu233.fml.vulkan.render.state.AlphaRefs").getMethod("value", int.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                alphaRefLookup = null;
            }
        }
        if (alphaRefLookup != null) {
            try {
                return ((Number) alphaRefLookup.invoke(null, index)).floatValue();
            } catch (ReflectiveOperationException | RuntimeException e) {
                alphaRefLookup = null;
            }
        }
        throw new IllegalArgumentException("alpha reference index " + index
                + " needs render.state.AlphaRefs.value(int), which is not available");
    }

    // ------------------------------------------------------------------------------------------------------------
    // creation (any thread)
    // ------------------------------------------------------------------------------------------------------------

    /** {@code warmUp}: the creation belongs to the start-up seed/journal set (counted for {@link #isWarm()}). */
    private static void createOnWorker(int gen, long key, boolean warmUp) {
        if (gen != generation) {
            return;
        }
        long start = System.nanoTime();
        long handle = 0L;
        int result = VK10.VK_SUCCESS;
        try {
            handle = createPipeline(key);
        } catch (Throwable t) {
            result = VK10.VK_ERROR_UNKNOWN;
            logFailure(key, t.toString());
        }
        if (gen != generation) {
            if (handle != 0L) {
                VK10.vkDestroyPipeline(RenderCore.device(), handle, null);
            }
            return;
        }
        completed.add(new Created(key, handle, System.nanoTime() - start, result, warmUp));
        if (warmUp) {
            warmDone.incrementAndGet();
        }
    }

    /** Creates the pipeline of a normalised key; 0 on failure (logged). Thread safe: the pipeline cache is internally synchronised. */
    private static long createPipeline(long key) {
        Program p = Program.ofOrdinal(MaterialKey.program(key));
        if (p == null || VERT_MODULES[p.ordinal()] == 0L || FRAG_MODULES[p.ordinal()] == 0L) {
            logFailure(key, "program " + p + " has no shader modules");
            return 0L;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            VkSpecializationInfo spec = specialization(stack, p, key);
            java.nio.ByteBuffer entry = stack.UTF8("main");
            stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(VERT_MODULES[p.ordinal()]).pName(entry)
                    .pSpecializationInfo(spec);
            stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(FRAG_MODULES[p.ordinal()]).pName(entry)
                    .pSpecializationInfo(spec);

            VkPipelineVertexInputStateCreateInfo vertexInput = vertexInput(stack, p);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);

            int cullMode = VK10.VK_CULL_MODE_NONE;
            if (!dynamicDepthCull && (key & BIT_CULL) != 0L) {
                cullMode = (key & BIT_CULL_FRONT) != 0L ? VK10.VK_CULL_MODE_FRONT_BIT : VK10.VK_CULL_MODE_BACK_BIT;
            }
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .depthClampEnable(false).rasterizerDiscardEnable(false).polygonMode(VK10.VK_POLYGON_MODE_FILL).cullMode(cullMode)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable((key & (1L << 36)) != 0L).lineWidth(1.0F);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

            boolean depthTest = !dynamicDepthCull && (key & BIT_DEPTH_TEST) != 0L;
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(depthTest)
                    .depthWriteEnable(depthTest && (key & BIT_DEPTH_WRITE) != 0L)
                    .depthCompareOp(depthTest ? MaterialKey.depthFunc(key) : VK10.VK_COMPARE_OP_ALWAYS)
                    .depthBoundsTestEnable(false).stencilTestEnable(false);

            boolean logic = (key & BIT_LOGIC_OP) != 0L;
            boolean blend = (key & BIT_BLEND) != 0L;
            VkPipelineColorBlendAttachmentState.Buffer attachment = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            attachment.get(0).blendEnable(blend)
                    .srcColorBlendFactor(VK_BLEND[blend ? MaterialKey.srcFactor(key) : 1])
                    .dstColorBlendFactor(VK_BLEND[blend ? MaterialKey.dstFactor(key) : 0])
                    .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND[blend ? MaterialKey.srcFactor(key) : 1])
                    .dstAlphaBlendFactor(VK_BLEND[blend ? MaterialKey.dstFactor(key) : 0])
                    .alphaBlendOp(VK10.VK_BLEND_OP_ADD)
                    .colorWriteMask(MaterialKey.colorMask(key));      // R, G, B only: alpha is never written (1.3)
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .logicOpEnable(logic).logicOp(VK10.VK_LOGIC_OP_OR_REVERSE).pAttachments(attachment);

            java.nio.IntBuffer dynamicStates = dynamicDepthCull
                    ? stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR, VK10.VK_DYNAMIC_STATE_DEPTH_BIAS,
                            EXTExtendedDynamicState.VK_DYNAMIC_STATE_CULL_MODE_EXT, EXTExtendedDynamicState.VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE_EXT,
                            EXTExtendedDynamicState.VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE_EXT,
                            EXTExtendedDynamicState.VK_DYNAMIC_STATE_DEPTH_COMPARE_OP_EXT)
                    : stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR, VK10.VK_DYNAMIC_STATE_DEPTH_BIAS);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(dynamicStates);

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly)
                    .pViewportState(viewport).pRasterizationState(raster).pMultisampleState(multisample).pDepthStencilState(depth)
                    .pColorBlendState(colorBlend).pDynamicState(dynamic).layout(pipelineLayout).renderPass(RenderCore.renderPass()).subpass(0);
            java.nio.LongBuffer pPipeline = stack.longs(0L);
            int result = VK10.vkCreateGraphicsPipelines(RenderCore.device(), pipelineCache, info, null, pPipeline);
            if (result != VK10.VK_SUCCESS) {
                if (result == VK10.VK_ERROR_DEVICE_LOST) {
                    throw new IllegalStateException("vkCreateGraphicsPipelines: device lost (key 0x" + Long.toHexString(key) + ")");
                }
                logFailure(key, "VkResult " + result);
                return 0L;
            }
            return pPipeline.get(0);
        }
    }

    private static VkSpecializationInfo specialization(MemoryStack stack, Program p, long key) {
        int alphaMode = MaterialKey.alphaMode(key);
        boolean depthOnly = MaterialKey.colorMask(key) == 0;
        int[] values = {
                alphaMode,
                Float.floatToRawIntBits(alphaMode == 1 ? alphaRef(MaterialKey.alphaRefIndex(key)) : 0.0F),
                MaterialKey.textured(key) ? 1 : 0,
                MaterialKey.lightmap(key) ? 1 : 0,
                MaterialKey.fog(key) ? 1 : 0,
                MaterialKey.overlay(key) ? 1 : 0,
                p == Program.TERRAIN && alphaMode == 1 ? 1 : 0,
                depthOnly ? 1 : 0
        };
        java.nio.ByteBuffer data = stack.malloc(values.length * 4);
        VkSpecializationMapEntry.Buffer entries = VkSpecializationMapEntry.calloc(values.length, stack);
        for (int i = 0; i < values.length; i++) {
            data.putInt(i * 4, values[i]);
            entries.get(i).constantID(i).offset(i * 4).size(4);
        }
        return VkSpecializationInfo.calloc(stack).pMapEntries(entries).pData(data);
    }

    private static VkPipelineVertexInputStateCreateInfo vertexInput(MemoryStack stack, Program p) {
        VkPipelineVertexInputStateCreateInfo info = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
        switch (p) {
            case TERRAIN -> {
                VkVertexInputBindingDescription.Buffer b = VkVertexInputBindingDescription.calloc(2, stack);
                b.get(0).binding(0).stride(VertexFormat.TERRAIN.stride()).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                b.get(1).binding(1).stride(16).inputRate(VK10.VK_VERTEX_INPUT_RATE_INSTANCE);
                VkVertexInputAttributeDescription.Buffer a = VkVertexInputAttributeDescription.calloc(5, stack);
                a.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                a.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                a.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(16);
                a.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R16G16_UINT).offset(24);
                a.get(4).location(4).binding(1).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                info.pVertexBindingDescriptions(b).pVertexAttributeDescriptions(a);
            }
            case STREAM, STATIC -> {
                VkVertexInputBindingDescription.Buffer b = VkVertexInputBindingDescription.calloc(1, stack);
                b.get(0).binding(0).stride(VertexFormat.UNIVERSAL.stride()).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer a = VkVertexInputAttributeDescription.calloc(5, stack);
                a.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                a.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                a.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(16);
                a.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R16G16_UINT).offset(24);
                a.get(4).location(4).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(28);
                info.pVertexBindingDescriptions(b).pVertexAttributeDescriptions(a);
            }
            case LINES -> {
                VkVertexInputBindingDescription.Buffer b = VkVertexInputBindingDescription.calloc(1, stack);
                b.get(0).binding(0).stride(VertexFormat.LINE.stride()).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer a = VkVertexInputAttributeDescription.calloc(4, stack);
                a.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                a.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                a.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(16);
                a.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R8_SINT).offset(28);
                info.pVertexBindingDescriptions(b).pVertexAttributeDescriptions(a);
            }
            case PORTAL -> {
                VkVertexInputBindingDescription.Buffer b = VkVertexInputBindingDescription.calloc(1, stack);
                b.get(0).binding(0).stride(VertexFormat.PORTAL.stride()).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer a = VkVertexInputAttributeDescription.calloc(3, stack);
                a.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                a.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(12);
                a.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(24);
                info.pVertexBindingDescriptions(b).pVertexAttributeDescriptions(a);
            }
            case BLIT -> {
                // no vertex input: the vertex shader generates a full-screen triangle from gl_VertexIndex
            }
        }
        return info;
    }

    private static void logFailure(long key, String why) {
        int n = FAILURES.incrementAndGet();
        if (n <= MAX_FAILURE_LOGS) {
            VkLog.LOGGER.warn("Native pipeline 0x{} ({}) could not be created: {}", Long.toHexString(key),
                    Program.ofOrdinal(MaterialKey.program(key)), why);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // integration of finished creations (render thread)
    // ------------------------------------------------------------------------------------------------------------

    private static void drainCompleted() {
        Created c;
        while ((c = completed.poll()) != null) {
            if (c.handle() != 0L) {
                if (created.get(c.key()) == 0L) {
                    addCreated(c.key(), c.handle());
                } else {
                    VK10.vkDestroyPipeline(RenderCore.device(), c.handle(), null);
                }
            }
            if (c.warmUp() && warmIntegrated.incrementAndGet() >= warmTotal.get() && warmListed && warmEndNanos == 0L) {
                warmEndNanos = System.nanoTime();
            }
        }
    }

    private static void addCreated(long key, long handle) {
        created.put(key, handle);
        if (createdCount == createdKeys.length) {
            createdKeys = Arrays.copyOf(createdKeys, createdCount * 2);
            createdHandles = Arrays.copyOf(createdHandles, createdCount * 2);
        }
        createdKeys[createdCount] = key;
        createdHandles[createdCount] = handle;
        createdCount++;
        createdTotal++;
        lastKey = Long.MIN_VALUE;
    }

    // ------------------------------------------------------------------------------------------------------------
    // layout, modules, bootstrap
    // ------------------------------------------------------------------------------------------------------------

    private static void createLayout(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer sets = stack.longs(Pipelines.uniformSetLayout(), Pipelines.textureSetLayout(), Pipelines.textureSetLayout());
            VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack);
            ranges.get(0).stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT).offset(0)
                    .size(ShaderInterface.PUSH_CONSTANT_SIZE);
            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(sets)
                    .pPushConstantRanges(ranges);
            java.nio.LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(device, info, null, pLayout), "vkCreatePipelineLayout (native)");
            pipelineLayout = pLayout.get(0);
        }
    }

    private static void loadModules(VkDevice device) {
        for (Program p : Program.values()) {
            byte[] vert = readResource(SHADER_DIR + p.shaderName() + ".vert.spv");
            byte[] frag = readResource(SHADER_DIR + p.shaderName() + ".frag.spv");
            if (vert == null || frag == null) {
                if (p == Program.PORTAL) {
                    VkLog.LOGGER.debug("No shader modules for program {} (owned by another package); its draws are skipped", p);
                    continue;
                }
                throw new IllegalStateException("Native shader modules of program " + p + " not found under " + SHADER_DIR
                        + " (did the compileShaders task run?)");
            }
            VERT_MODULES[p.ordinal()] = createModule(device, vert, p + ".vert");
            FRAG_MODULES[p.ordinal()] = createModule(device, frag, p + ".frag");
        }
    }

    private static byte[] readResource(String path) {
        try (InputStream in = PipelineLibrary.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0 || (bytes.length & 3) != 0) {
                throw new IllegalStateException("Shader resource " + path + " is not a SPIR-V module (" + bytes.length + " bytes)");
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the shader resource " + path, e);
        }
    }

    private static long createModule(VkDevice device, byte[] spirv, String name) {
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
        try {
            code.put(spirv).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
                java.nio.LongBuffer pModule = stack.mallocLong(1);
                check(VK10.vkCreateShaderModule(device, info, null, pModule), "vkCreateShaderModule(" + name + ")");
                return pModule.get(0);
            }
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    /** The six pipelines the loading screen and the first GUI frames need before the workers have produced anything. */
    private static void createBootstrapSet() {
        long depthOn = BIT_DEPTH_TEST | (3L << 28) | BIT_DEPTH_WRITE;     // LEQUAL, write
        long mask = COLOR_MASK_BITS;
        long alphaTest = 1L << 8;                                          // fixed GREATER, reference index 0 (0.1)
        long blend = BIT_BLEND | ((long) BlendFactor.SRC_ALPHA.ordinal() << 19) | ((long) BlendFactor.ONE_MINUS_SRC_ALPHA.ordinal() << 23);
        long[] keys = {
                Program.STREAM.ordinal() | BIT_TEXTURED | mask | depthOn,
                Program.STREAM.ordinal() | BIT_TEXTURED | mask | depthOn | alphaTest,
                Program.STREAM.ordinal() | BIT_TEXTURED | mask | blend,
                Program.STREAM.ordinal() | BIT_TEXTURED | mask | blend | alphaTest,
                Program.STREAM.ordinal() | mask | blend,
                Program.STREAM.ordinal() | mask | depthOn,
        };
        for (long raw : keys) {
            long key = normalize(Program.STREAM, raw);
            if (claimed.add(key)) {
                long handle = createPipeline(key);
                if (handle != 0L) {
                    addCreated(key, handle);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // seed list and journal
    // ------------------------------------------------------------------------------------------------------------

    private static void queueWarmUp(int gen) {
        pool.execute(() -> {
            List<long[]> entries = new ArrayList<>();
            readKeyLines(PipelineLibrary.class.getResourceAsStream(SEED_RESOURCE), entries);
            try {
                Path journal = journalFile();
                if (Files.isRegularFile(journal)) {
                    try (InputStream in = Files.newInputStream(journal)) {
                        readKeyLines(in, entries);
                    }
                }
            } catch (IOException e) {
                VkLog.LOGGER.warn("Could not read the native pipeline journal", e);
            }
            if (gen != generation) {
                return;
            }
            List<Long> todo = new ArrayList<>(entries.size());
            for (long[] e : entries) {
                Program p = Program.ofOrdinal((int) e[0]);
                if (p == null) {
                    continue;
                }
                boolean masked = e[2] != 0L;
                if (masked && !dynamicDepthCull) {
                    continue;       // a key without depth/cull bits cannot describe a statically baked pipeline
                }
                long key = normalize(p, e[1]);
                if (claimed.add(key)) {
                    todo.add(key);
                    warmPending.add(key);
                }
            }
            warmTotal.set(todo.size());
            warmListed = true;
            if (todo.isEmpty()) {
                warmEndNanos = System.nanoTime();
                return;
            }
            for (long key : todo) {
                pool.execute(() -> {
                    if (warmPending.remove(key)) {      // else a miss already took the key over
                        createOnWorker(gen, key, true);
                    }
                });
            }
        });
    }

    /** Parses {@code <PROGRAM> <hex key> [d]} lines; {@code d} marks a key recorded with dynamic depth/cull state. */
    private static void readKeyLines(InputStream in, List<long[]> out) {
        if (in == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines++ < MAX_JOURNAL_LINES * 2) {
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] w = line.split("\\s+");
                if (w.length < 2) {
                    continue;
                }
                try {
                    Program p = Program.valueOf(w[0].toUpperCase(Locale.ROOT));
                    String hex = w[1].startsWith("0x") ? w[1].substring(2) : w[1];
                    out.add(new long[] {p.ordinal(), Long.parseUnsignedLong(hex, 16), w.length > 2 && w[2].equals("d") ? 1L : 0L});
                } catch (IllegalArgumentException e) {
                    // an unknown program or a damaged line: skip it
                }
            }
        } catch (IOException e) {
            VkLog.LOGGER.warn("Could not read a native pipeline key list", e);
        }
    }

    private static void recordJournal(long key) {
        if (!journalEnabled) {
            return;
        }
        if (journalNewCount == journalNew.length) {
            journalNew = Arrays.copyOf(journalNew, journalNewCount * 2);
        }
        journalNew[journalNewCount++] = key;
    }

    private static void flushJournal() throws IOException {
        int count = journalNewCount;
        if (count == journalFlushed) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = journalFlushed; i < count; i++) {
            long k = journalNew[i];
            sb.append(Program.ofOrdinal(MaterialKey.program(k))).append(" 0x").append(String.format("%016x", k))
                    .append(dynamicDepthCull ? " d" : "").append('\n');
        }
        Path file = journalFile();
        synchronized (FILE_LOCK) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        journalFlushed = count;
    }

    // ------------------------------------------------------------------------------------------------------------
    // pool
    // ------------------------------------------------------------------------------------------------------------

    private static void startPool() {
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "FML-Vulkan pipelines " + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        AtomicInteger m = new AtomicInteger();
        missPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "FML-Vulkan pipeline misses " + m.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    private static void stopPool() {
        ExecutorService p = pool;
        ExecutorService mp = missPool;
        pool = null;
        missPool = null;
        if (p == null) {
            return;
        }
        p.shutdownNow();
        if (mp != null) {
            mp.shutdownNow();
        }
        try {
            if (!p.awaitTermination(30, TimeUnit.SECONDS) || mp != null && !mp.awaitTermination(30, TimeUnit.SECONDS)) {
                VkLog.LOGGER.warn("Native pipeline workers did not stop within 30 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // persistent VkPipelineCache
    // ------------------------------------------------------------------------------------------------------------

    private static long createPipelineCache(VkDevice device) {
        Path file = cacheFile();
        ByteBuffer stored = readStoredCache(file);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer pCache = stack.mallocLong(1);
            if (stored != null) {
                VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default().pInitialData(stored);
                int result = VK10.vkCreatePipelineCache(device, info, null, pCache);
                if (result == VK10.VK_SUCCESS) {
                    cacheLoaded = true;
                    cacheLoadedBytes = stored.remaining();
                    return pCache.get(0);
                }
                VkLog.LOGGER.warn("The driver rejected the stored native pipeline cache {} (VkResult {}), starting empty", file, result);
                deleteQuietly(file);
            }
            VkPipelineCacheCreateInfo empty = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            check(VK10.vkCreatePipelineCache(device, empty, null, pCache), "vkCreatePipelineCache (native)");
            return pCache.get(0);
        } finally {
            if (stored != null) {
                MemoryUtil.memFree(stored);
            }
        }
    }

    private static ByteBuffer readStoredCache(Path file) {
        ByteBuffer data = null;
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            long size = Files.size(file);
            if (size < CACHE_HEADER_SIZE || size > MAX_CACHE_FILE_BYTES) {
                VkLog.LOGGER.warn("Ignoring the native pipeline cache {}: unexpected size of {} bytes", file, size);
                deleteQuietly(file);
                return null;
            }
            data = MemoryUtil.memAlloc((int) size);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                while (data.hasRemaining()) {
                    if (channel.read(data) < 0) {
                        break;
                    }
                }
            }
            if (data.hasRemaining()) {
                VkLog.LOGGER.warn("Ignoring the native pipeline cache {}: it changed while it was read", file);
                return null;
            }
            data.flip();
            String mismatch = headerMismatch(data);
            if (mismatch != null) {
                VkLog.LOGGER.info("Discarding the stored native pipeline cache: {}", mismatch);
                deleteQuietly(file);
                return null;
            }
            ByteBuffer ok = data;
            data = null;
            return ok;
        } catch (IOException | RuntimeException e) {
            VkLog.LOGGER.warn("Could not read the native pipeline cache {}, starting empty", file, e);
            return null;
        } finally {
            if (data != null) {
                MemoryUtil.memFree(data);
            }
        }
    }

    private static String headerMismatch(ByteBuffer data) {
        ByteBuffer header = data.duplicate().order(ByteOrder.nativeOrder());
        int headerSize = header.getInt(0);
        int version = header.getInt(4);
        if (headerSize < CACHE_HEADER_SIZE || headerSize > header.limit()) {
            return "invalid header size " + headerSize;
        }
        if (version != VK10.VK_PIPELINE_CACHE_HEADER_VERSION_ONE) {
            return "unknown header version " + version;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            VK10.vkGetPhysicalDeviceProperties(RenderCore.physicalDevice(), props);
            if (header.getInt(8) != props.vendorID() || header.getInt(12) != props.deviceID()) {
                return "it was written by another GPU";
            }
            for (int i = 0; i < VK10.VK_UUID_SIZE; i++) {
                if (header.get(16 + i) != props.pipelineCacheUUID(i)) {
                    return "it was written by another driver version";
                }
            }
        }
        return null;
    }

    private static void saveCacheNow(VkDevice device) throws IOException {
        flushJournal();
        if (pipelineCache == 0L || createdTotal == savedPipelineCount && cacheLoaded) {
            return;
        }
        ByteBuffer data = snapshotCache(device);
        if (data != null) {
            writeCache(data, createdTotal);
        }
    }

    /** Native copy of the cache data; the caller frees it (or hands it to {@link #writeCache}). Null when there is none. */
    private static ByteBuffer snapshotCache(VkDevice device) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pSize = stack.callocPointer(1);
                int result = VK10.vkGetPipelineCacheData(device, pipelineCache, pSize, null);
                long size = pSize.get(0);
                if (result != VK10.VK_SUCCESS || size < CACHE_HEADER_SIZE || size > MAX_CACHE_FILE_BYTES) {
                    return null;
                }
                // a worker may grow the cache between the two calls: ask for some headroom and retry on VK_INCOMPLETE
                ByteBuffer data = MemoryUtil.memAlloc((int) Math.min(MAX_CACHE_FILE_BYTES, size + (size >> 3) + 4096));
                pSize.put(0, data.capacity());
                result = VK10.vkGetPipelineCacheData(device, pipelineCache, pSize, data);
                if (result == VK10.VK_SUCCESS) {
                    data.limit((int) pSize.get(0));
                    return data;
                }
                MemoryUtil.memFree(data);
                if (result != VK10.VK_INCOMPLETE) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Writes next to the target and renames so that a crash cannot leave a truncated cache; frees the buffer. */
    private static void writeCache(ByteBuffer data, int count) throws IOException {
        Path file = cacheFile();
        try {
            synchronized (FILE_LOCK) {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    while (data.hasRemaining()) {
                        channel.write(data);
                    }
                    channel.force(true);
                }
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            savedPipelineCount = count;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            VkLog.LOGGER.debug("Could not delete {}: {}", file, e.toString());
        }
    }

    /** Periodic save (every 5 minutes, only when pipelines were created since the last save); the file is written off the render thread. */
    private static final class Saver implements RenderCore.FrameListener {
        private final int gen;

        Saver(int gen) {
            this.gen = gen;
        }

        @Override
        public void onFrameBegin() {
            if (gen != generation || !initialized) {
                return;
            }
            if (!completed.isEmpty()) {
                drainCompleted();       // integrates finished creations even when nothing calls get()
            }
            if (createdTotal == savedPipelineCount) {
                return;
            }
            long now = System.nanoTime();
            if (now - lastSaveNanos < CACHE_SAVE_INTERVAL_NANOS || !warmListed) {
                return;
            }
            lastSaveNanos = now;
            try {
                flushJournal();
                // the snapshot (serialising hundreds of MSL libraries can take long) and the write both run on a pool thread
                final int count = createdTotal;
                final VkDevice device = RenderCore.device();
                ExecutorService p = pool;
                if (p == null) {
                    return;
                }
                p.execute(() -> {
                    if (gen != generation) {
                        return;
                    }
                    ByteBuffer data = snapshotCache(device);
                    if (data == null) {
                        lastSaveNanos = 0L;     // retry at the next frame instead of waiting another interval
                        return;
                    }
                    try {
                        writeCache(data, count);    // frees the buffer
                    } catch (IOException e) {
                        VkLog.LOGGER.warn("Could not save the native pipeline cache", e);
                    }
                });
            } catch (IOException | RuntimeException e) {
                VkLog.LOGGER.warn("Could not save the native pipeline cache", e);
            }
        }
    }

    private static void check(int result, String what) {
        if (result < 0) {
            throw new IllegalStateException(what + " failed (VkResult " + result + ")");
        }
    }

    /** Open-addressing long to long map without boxing; 0 stands for absent. Render thread only. */
    private static final class HandleMap {
        private long[] keys = new long[64];
        private long[] values = new long[64];
        private int size;

        long get(long key) {
            int mask = keys.length - 1;
            for (int i = slot(key, mask); values[i] != 0L; i = (i + 1) & mask) {
                if (keys[i] == key) {
                    return values[i];
                }
            }
            return 0L;
        }

        void put(long key, long value) {
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            int i = slot(key, mask);
            while (values[i] != 0L) {
                if (keys[i] == key) {
                    values[i] = value;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            values[i] = value;
            size++;
        }

        private void grow() {
            long[] oldKeys = keys;
            long[] oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new long[oldKeys.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldValues[i] != 0L) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }

        private static int slot(long key, int mask) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32)) & mask;
        }
    }
}

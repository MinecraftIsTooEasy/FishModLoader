package net.xiaoyu233.fml.vulkan.pipeline;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import org.apache.logging.log4j.Level;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
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
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Descriptor set layouts, the pipeline layout, the SPIR-V modules, the {@code VkPipeline} cache and the uniform descriptor sets
 * (module C). Signatures are the binding contract of ../docs/ARCHITECTURE.md (outside the repo) section 5.7. Render thread only.
 *
 * <p>Pipelines are created on demand by {@link #get} and persist in a {@code VkPipelineCache} that is stored in
 * {@code <gameDir>/.fml/vulkan/pipeline-cache.bin}: by {@link #shutdown()}, and while the game runs (at most every 30 seconds,
 * only when pipelines were created since the last save) so that a session that ends without {@code Display.destroy()} (the game
 * calls {@code System.exit} after a crash) does not lose what it compiled.
 *
 * <p>A pipeline that the driver cannot create is not fatal: {@link #get} reports the failure once and returns 0, and
 * {@link CommandRecorder#bindPipeline} then drops the draws that need it (GL skips what it cannot draw as well). Only a lost
 * device still throws.
 */
public final class Pipelines {
    private static final String SHADER_RESOURCE_DIR = "/assets/fml_vulkan/shaders/";

    /** Bits 47..63 of a pipeline key are reserved (0): the vertex layout id goes there in the pipeline map key. */
    private static final int LAYOUT_ID_SHIFT = 47;
    private static final long KEY_MASK = (1L << LAYOUT_ID_SHIFT) - 1;

    /** Descriptor sets per uniform descriptor pool; a full pool is followed by a new one. */
    private static final int UNIFORM_POOL_SETS = 256;

    /** VkPipelineCacheHeaderVersionOne: headerSize, headerVersion, vendorID, deviceID (uint32 each), pipelineCacheUUID[16]. */
    private static final int CACHE_HEADER_SIZE = 32;
    private static final long MAX_CACHE_FILE_BYTES = 256L << 20;
    /** The stored cache is refreshed no more often than this while new pipelines keep appearing. */
    private static final long CACHE_SAVE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** How long shutdown waits for a periodic save that is still being written. */
    private static final long CACHE_WRITER_JOIN_MILLIS = 10_000L;

    /** A pipeline that failed for a transient reason (out of memory) is tried again after this many frames. */
    private static final int PIPELINE_RETRY_FRAMES = 600;
    /** Failures beyond this many are only logged at debug level (a broken driver must not flood the log). */
    private static final int MAX_FAILURE_LOGS = 16;
    /** {@link #failedPipelines} value of a failure that is never retried (0 is "absent"). */
    private static final long NEVER_RETRY = Long.MAX_VALUE;

    private static boolean initialized;
    /** Bumped by every init/shutdown: deferred callbacks of an earlier life must not touch objects that no longer exist. */
    private static volatile int generation;

    private static long uniformSetLayout;
    private static long textureSetLayout;
    private static long pipelineLayout;

    private static long vertModule;
    private static long fragModule;
    private static long vertFlatModule;
    private static long fragFlatModule;

    private static long pipelineCache;
    private static int pipelineCount;

    // the stored cache: what the file holds, and the thread that is writing it
    private static volatile int savedPipelineCount;         // pipelines in the snapshot that the file holds
    private static long lastCacheSaveNanos;
    private static long cacheSnapshotSeq;                   // number of the newest snapshot
    private static long cacheFileSeq;                       // number of the snapshot the file holds (guarded by CACHE_FILE_LOCK)
    private static final Object CACHE_FILE_LOCK = new Object();
    private static Thread cacheWriter;                      // writes the newest periodic snapshot; null or dead when idle
    private static CacheSaver cacheSaver;
    private static volatile boolean warnedCacheSave;

    // capabilities that decide how a pipeline key is realized
    private static boolean fillModeNonSolid;
    private static boolean moltenVk;
    private static boolean warnedPolygonModeFallback;

    private static final LongLongMap pipelines = new LongLongMap();       // normalized key | layout id << 47 -> VkPipeline
    private static long lastKey = -1L;                                     // one-entry memo of get()
    private static int lastLayoutId = -1;
    private static long lastPipeline;

    // pipelines the driver refused: map key -> first frame (+1) at which a new attempt is made, or NEVER_RETRY
    private static final LongLongMap failedPipelines = new LongLongMap();
    private static int pipelineFailures;
    private static int createResult;                                       // VkResult of the last createPipeline call

    private static final LongLongMap uniformSets = new LongLongMap();     // VkBuffer -> VkDescriptorSet
    private static final LongLongMap uniformSetPools = new LongLongMap(); // VkDescriptorSet -> VkDescriptorPool it came from
    private static long[] uniformPools = new long[4];
    private static int uniformPoolCount;
    private static int uniformPoolSets = UNIFORM_POOL_SETS;                // sets handed out by the newest pool (full: none yet)
    private static long lastUniformBuffer;                                  // one-entry memo of uniformSet()
    private static long lastUniformSet;

    private Pipelines() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------------------------------------

    /** Set layouts, pipeline layout (push constants 128 bytes VERTEX|FRAGMENT), SPIR-V modules, pipeline cache; starts the recorder. */
    public static void init() {
        if (initialized) {
            VkLog.LOGGER.warn("Pipelines.init() called while the pipelines are already initialized, ignored");
            return;
        }
        try {
            create();
            initialized = true;
        } catch (Throwable t) {
            destroy(false);     // VulkanBackend does not call shutdown() for a step that failed
            throw t;
        }
    }

    /** Persists the pipeline cache to {@code <gameDir>/.fml/vulkan/pipeline-cache.bin}, then destroys everything created here. */
    public static void shutdown() {
        if (!initialized && pipelineLayout == 0L && pipelineCache == 0L) {
            return;
        }
        initialized = false;
        destroy(true);
    }

    private static void create() {
        generation++;
        VkDevice device = RenderCore.device();
        DeviceInfo info = RenderCore.deviceInfo();
        fillModeNonSolid = info.fillModeNonSolid();
        moltenVk = info.isMoltenVK();
        warnedPolygonModeFallback = false;

        uniformSetLayout = createSetLayout(device, VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC,
                VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, ShaderInterface.BINDING_UNIFORMS);
        textureSetLayout = createSetLayout(device, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                VK10.VK_SHADER_STAGE_FRAGMENT_BIT, ShaderInterface.BINDING_TEXTURE);
        pipelineLayout = createPipelineLayout(device);

        vertModule = loadShaderModule(device, "ff.vert.spv");
        fragModule = loadShaderModule(device, "ff.frag.spv");
        vertFlatModule = loadShaderModule(device, "ff_flat.vert.spv");
        fragFlatModule = loadShaderModule(device, "ff_flat.frag.spv");

        pipelineCache = createPipelineCache(device);
        savedPipelineCount = 0;
        lastCacheSaveNanos = System.nanoTime();
        warnedCacheSave = false;

        CommandRecorder.onPipelinesInit();
        cacheSaver = new CacheSaver();
        RenderCore.addListener(cacheSaver);
        VkLog.LOGGER.info("Pipelines initialized (fillModeNonSolid={}, wideLines={})", fillModeNonSolid, info.wideLines());
    }

    /** Destroys what exists, every step on its own so that one failure does not skip the rest (all handles are optional). */
    private static void destroy(boolean persistCache) {
        VkDevice device = null;
        try {
            device = RenderCore.device();
        } catch (RuntimeException e) {
            // no device any more: nothing of ours can be destroyed through it
        }
        if (cacheSaver != null) {
            cacheSaver.active = false;      // a listener cannot be removed from RenderCore: switch it off
            cacheSaver = null;
        }
        if (persistCache && pipelineCache != 0L && device != null) {
            try {
                saveCache(device);          // before the generation changes: a periodic writer that is still running counts
            } catch (Throwable t) {
                warnCacheSave("Could not save the pipeline cache", t);
            }
        }
        cacheWriter = null;
        generation++;

        try {
            CommandRecorder.onPipelinesShutdown();
        } catch (Throwable t) {
            VkLog.LOGGER.warn("Command recorder shutdown failed", t);
        }

        if (device != null) {
            final VkDevice dev = device;
            try {
                pipelines.forEachValue(pipeline -> VK10.vkDestroyPipeline(dev, pipeline, null));
            } catch (Throwable t) {
                VkLog.LOGGER.warn("Destroying the pipelines failed", t);
            }
            for (int i = 0; i < uniformPoolCount; i++) {
                try {
                    VK10.vkDestroyDescriptorPool(dev, uniformPools[i], null);     // frees its sets as well
                } catch (Throwable t) {
                    VkLog.LOGGER.warn("Destroying a uniform descriptor pool failed", t);
                }
            }
            pipelineCache = destroyHandle(pipelineCache, h -> VK10.vkDestroyPipelineCache(dev, h, null));
            vertModule = destroyHandle(vertModule, h -> VK10.vkDestroyShaderModule(dev, h, null));
            fragModule = destroyHandle(fragModule, h -> VK10.vkDestroyShaderModule(dev, h, null));
            vertFlatModule = destroyHandle(vertFlatModule, h -> VK10.vkDestroyShaderModule(dev, h, null));
            fragFlatModule = destroyHandle(fragFlatModule, h -> VK10.vkDestroyShaderModule(dev, h, null));
            pipelineLayout = destroyHandle(pipelineLayout, h -> VK10.vkDestroyPipelineLayout(dev, h, null));
            uniformSetLayout = destroyHandle(uniformSetLayout, h -> VK10.vkDestroyDescriptorSetLayout(dev, h, null));
            textureSetLayout = destroyHandle(textureSetLayout, h -> VK10.vkDestroyDescriptorSetLayout(dev, h, null));
        }
        pipelineCache = 0L;
        vertModule = fragModule = vertFlatModule = fragFlatModule = 0L;
        pipelineLayout = uniformSetLayout = textureSetLayout = 0L;

        pipelines.clear();
        failedPipelines.clear();
        pipelineCount = 0;
        savedPipelineCount = 0;
        pipelineFailures = 0;
        lastKey = -1L;
        lastLayoutId = -1;
        lastPipeline = 0L;
        uniformSets.clear();
        uniformSetPools.clear();
        uniformPoolCount = 0;
        uniformPoolSets = UNIFORM_POOL_SETS;
        lastUniformBuffer = 0L;
        lastUniformSet = 0L;
    }

    private static long destroyHandle(long handle, java.util.function.LongConsumer destroyer) {
        if (handle != 0L) {
            try {
                destroyer.accept(handle);
            } catch (Throwable t) {
                VkLog.LOGGER.warn("Destroying a Vulkan object failed", t);
            }
        }
        return 0L;
    }

    // ---------------------------------------------------------------------------------------------------------
    // public API
    // ---------------------------------------------------------------------------------------------------------

    /**
     * VkPipeline for the key and the vertex layout; created on a miss and cached. Returns 0 when the driver cannot create the
     * pipeline (reported once, then remembered: such a key is not tried again on every draw): hand the 0 to
     * {@link CommandRecorder#bindPipeline}, which drops the draws that follow. A lost device still throws.
     */
    public static long get(long pipelineKey, VertexLayout layout) {
        int layoutId = layout.id();
        if (pipelineKey == lastKey && layoutId == lastLayoutId) {
            return lastPipeline;
        }
        long key = normalize(pipelineKey);
        long mapKey = key | ((long) layoutId << LAYOUT_ID_SHIFT);
        long pipeline = pipelines.get(mapKey);
        if (pipeline == 0L) {
            pipeline = createOnMiss(mapKey, key, layout);
            if (pipeline == 0L) {
                return 0L;      // not memoized: the next call looks at the failure (and its retry time) again
            }
        }
        lastKey = pipelineKey;
        lastLayoutId = layoutId;
        lastPipeline = pipeline;
        return pipeline;
    }

    /** A pipeline that is not in the map yet: created, or 0 when it cannot be created (a failure is remembered). */
    private static long createOnMiss(long mapKey, long key, VertexLayout layout) {
        if (!initialized) {
            return 0L;      // before init and after shutdown there is no device to create anything on
        }
        long failed = failedPipelines.get(mapKey);
        if (failed != 0L && !retryDue(failed)) {
            return 0L;
        }
        long start = System.nanoTime();
        long pipeline = createPipeline(key, layout);
        if (pipeline == 0L) {
            recordFailure(mapKey, key, layout, createResult, failed != 0L);
            return 0L;
        }
        if (failed != 0L) {
            failedPipelines.remove(mapKey);
            VkLog.LOGGER.info("The pipeline that could not be created earlier was created now (key 0x{}, {})",
                    Long.toHexString(key), layout);
        }
        pipelines.put(mapKey, pipeline);
        pipelineCount++;
        if (VulkanConfig.debug()) {
            long micros = (System.nanoTime() - start) / 1000L;
            // a pipeline built on the render thread is a frame hitch: the slow ones are worth seeing in the normal log
            VkLog.LOGGER.log(micros > 5000L ? Level.INFO : Level.DEBUG, "Created pipeline #{} in {} us (frame {}, key 0x{}, {})", pipelineCount,
                    micros, RenderCore.frameNumber(), Long.toHexString(key), layout);
        }
        return pipeline;
    }

    /**
     * Remembers a failed creation. The failure is a skipped draw, not a crash (GL does not draw what it cannot draw either), so
     * only a lost device is fatal. Out of memory may pass, so it is retried after {@link #PIPELINE_RETRY_FRAMES} frames; every
     * other result is a verdict of the driver on this exact state and is final.
     */
    private static void recordFailure(long mapKey, long key, VertexLayout layout, int result, boolean retried) {
        if (result == VK10.VK_ERROR_DEVICE_LOST) {
            throw new IllegalStateException("vkCreateGraphicsPipelines failed: " + resultName(result) + " (device lost) (key 0x"
                    + Long.toHexString(key) + ", " + layout + ")");
        }
        long retryStamp = retryStamp(result, RenderCore.frameNumber());
        failedPipelines.put(mapKey, retryStamp);
        boolean report = !retried && ++pipelineFailures <= MAX_FAILURE_LOGS;     // a retry that fails again is not news
        if (report || VkLog.LOGGER.isDebugEnabled()) {
            VkLog.LOGGER.log(report ? Level.ERROR : Level.DEBUG,
                    "vkCreateGraphicsPipelines failed with {}: the draws that need this pipeline are {} [{}, {}]",
                    resultName(result), retryStamp == NEVER_RETRY ? "skipped" : "skipped for now (retried in " + PIPELINE_RETRY_FRAMES + " frames)",
                    describeKey(key), layout);
        }
        if (report && pipelineFailures == MAX_FAILURE_LOGS) {
            VkLog.LOGGER.error("Further pipeline failures are only logged at debug level");
        }
    }

    /** {@link #failedPipelines} value for a failure with this result at this frame: the first frame (+1) of the next attempt. */
    private static long retryStamp(int result, long frame) {
        boolean transientResult = result == VK10.VK_ERROR_OUT_OF_HOST_MEMORY || result == VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY;
        return transientResult ? frame + PIPELINE_RETRY_FRAMES + 1L : NEVER_RETRY;
    }

    private static boolean retryDue(long retryStamp) {
        return retryStamp != NEVER_RETRY && RenderCore.frameNumber() + 1L >= retryStamp;
    }

    private static String resultName(int result) {
        return switch (result) {
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK10.VK_ERROR_TOO_MANY_OBJECTS -> "VK_ERROR_TOO_MANY_OBJECTS";
            case VK10.VK_ERROR_FORMAT_NOT_SUPPORTED -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case VK10.VK_ERROR_UNKNOWN -> "VK_ERROR_UNKNOWN";
            default -> "VkResult " + result;
        };
    }

    /** The state fields of a key in words (failure log, draw trace). */
    public static String describe(long key) {
        return describeKey(key);
    }

    /** The state fields of a normalized key in words, for the failure log. */
    private static String describeKey(long key) {
        StringBuilder sb = new StringBuilder("key 0x").append(Long.toHexString(key));
        sb.append(": topology ").append(PipelineKey.get(key, PipelineKey.TOPOLOGY));
        if (PipelineKey.get(key, PipelineKey.BLEND_ENABLE) != 0) {
            sb.append(", blend ").append(PipelineKey.get(key, PipelineKey.SRC_COLOR_BLEND_FACTOR)).append('/')
                    .append(PipelineKey.get(key, PipelineKey.DST_COLOR_BLEND_FACTOR)).append(" (alpha ")
                    .append(PipelineKey.get(key, PipelineKey.SRC_ALPHA_BLEND_FACTOR)).append('/')
                    .append(PipelineKey.get(key, PipelineKey.DST_ALPHA_BLEND_FACTOR)).append("), ops ")
                    .append(PipelineKey.get(key, PipelineKey.COLOR_BLEND_OP)).append('/')
                    .append(PipelineKey.get(key, PipelineKey.ALPHA_BLEND_OP));
        } else {
            sb.append(", no blend");
        }
        sb.append(", depth test ").append(PipelineKey.get(key, PipelineKey.DEPTH_TEST_ENABLE))
                .append(" write ").append(PipelineKey.get(key, PipelineKey.DEPTH_WRITE_ENABLE))
                .append(" op ").append(PipelineKey.get(key, PipelineKey.DEPTH_COMPARE_OP))
                .append(", cull ").append(PipelineKey.get(key, PipelineKey.CULL_MODE))
                .append(" clockwise ").append(PipelineKey.get(key, PipelineKey.FRONT_FACE_CLOCKWISE))
                .append(", write mask ").append(PipelineKey.get(key, PipelineKey.COLOR_WRITE_MASK))
                .append(", polygon mode ").append(PipelineKey.get(key, PipelineKey.POLYGON_MODE))
                .append(", depth bias ").append(PipelineKey.get(key, PipelineKey.DEPTH_BIAS_ENABLE))
                .append(", flat ").append(PipelineKey.get(key, PipelineKey.FLAT_SHADING))
                .append(", alpha test ").append(PipelineKey.get(key, PipelineKey.ALPHA_TEST));
        return sb.toString();
    }

    public static long pipelineLayout() {
        return pipelineLayout;
    }

    /** Layout of set 0 (dynamic uniform buffer). */
    public static long uniformSetLayout() {
        return uniformSetLayout;
    }

    /** Layout of sets 1 and 2 (combined image sampler, shared by both texture units). */
    public static long textureSetLayout() {
        return textureSetLayout;
    }

    /**
     * Descriptor set for a uniform stream buffer (dynamic UBO, range {@link ShaderInterface#UBO_SIZE}); created on the first
     * request and cached. The set lives until {@link #forgetUniformBuffer} or {@link #shutdown()}: a stream buffer is reused
     * by every frame that gets its slot, so the set must not be tied to a single frame.
     */
    public static long uniformSet(long uniformBuffer) {
        if (uniformBuffer == lastUniformBuffer && lastUniformSet != 0L) {
            return lastUniformSet;
        }
        long set = uniformSets.get(uniformBuffer);
        if (set == 0L) {
            set = allocateUniformSet(uniformBuffer);
        }
        lastUniformBuffer = uniformBuffer;
        lastUniformSet = set;
        return set;
    }

    /**
     * To be called by the owner of a uniform stream buffer when it destroys the buffer: drops the cached descriptor set, so a
     * later buffer that reuses the same handle value gets a fresh set. The set is freed once the frames that may still use it
     * have completed.
     */
    public static void forgetUniformBuffer(long uniformBuffer) {
        if (lastUniformBuffer == uniformBuffer) {
            lastUniformBuffer = 0L;
            lastUniformSet = 0L;
        }
        long set = uniformSets.remove(uniformBuffer);
        if (set == 0L) {
            return;
        }
        long pool = uniformSetPools.remove(set);
        if (pool == 0L) {
            return;
        }
        final int gen = generation;
        final VkDevice device = RenderCore.device();
        RenderCore.deferDestroy(() -> {
            if (gen == generation) {        // otherwise the pool is gone and took the set with it
                VK10.vkFreeDescriptorSets(device, pool, set);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // layouts
    // ---------------------------------------------------------------------------------------------------------

    private static long createSetLayout(VkDevice device, int descriptorType, int stages, int binding) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0)
                    .binding(binding)
                    .descriptorType(descriptorType)
                    .descriptorCount(1)
                    .stageFlags(stages);
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(device, info, null, pLayout), "vkCreateDescriptorSetLayout");
            return pLayout.get(0);
        }
    }

    private static long createPipelineLayout(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // set 0 = uniforms, set 1 = texture unit 0, set 2 = texture unit 1 (sets 1 and 2 share one layout)
            LongBuffer setLayouts = stack.longs(uniformSetLayout, textureSetLayout, textureSetLayout);
            VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack);
            ranges.get(0)
                    .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .offset(0)
                    .size(ShaderInterface.PUSH_CONSTANT_SIZE);
            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(setLayouts)
                    .pPushConstantRanges(ranges);
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(device, info, null, pLayout), "vkCreatePipelineLayout");
            return pLayout.get(0);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // shader modules
    // ---------------------------------------------------------------------------------------------------------

    private static long loadShaderModule(VkDevice device, String file) {
        String path = SHADER_RESOURCE_DIR + file;
        byte[] spirv;
        try (InputStream in = Pipelines.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Shader resource not found: " + path + " (did the compileShaders task run?)");
            }
            spirv = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the shader resource " + path, e);
        }
        if (spirv.length == 0 || (spirv.length & 3) != 0) {
            throw new IllegalStateException("Shader resource " + path + " is not a SPIR-V module (" + spirv.length + " bytes)");
        }
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
        try {
            code.put(spirv).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(code);
                LongBuffer pModule = stack.mallocLong(1);
                check(VK10.vkCreateShaderModule(device, info, null, pModule), "vkCreateShaderModule(" + file + ")");
                return pModule.get(0);
            }
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // pipeline cache
    // ---------------------------------------------------------------------------------------------------------

    private static Path cacheFile() {
        return VulkanConfig.gameDir().resolve(".fml").resolve("vulkan").resolve("pipeline-cache.bin");
    }

    /**
     * Creates the VkPipelineCache, seeded with the stored data when that data was written by this device and driver. A stored
     * cache is only an optimization: anything wrong with it (unreadable, another GPU or driver, rejected by the driver) is
     * logged and dropped, and never prevents the game from starting.
     */
    private static long createPipelineCache(VkDevice device) {
        Path file = cacheFile();
        ByteBuffer stored = readStoredCache(file);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pCache = stack.mallocLong(1);
            if (stored != null) {
                VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default().pInitialData(stored);
                int result = VK10.vkCreatePipelineCache(device, info, null, pCache);
                if (result == VK10.VK_SUCCESS) {
                    VkLog.LOGGER.debug("Loaded the pipeline cache {} ({} bytes)", file, stored.remaining());
                    return pCache.get(0);
                }
                VkLog.LOGGER.warn("The driver rejected the stored pipeline cache {} (VkResult {}), starting with an empty cache", file, result);
                discardCacheFile(file);
            }
            VkPipelineCacheCreateInfo empty = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            check(VK10.vkCreatePipelineCache(device, empty, null, pCache), "vkCreatePipelineCache");
            return pCache.get(0);
        } finally {
            if (stored != null) {
                MemoryUtil.memFree(stored);
            }
        }
    }

    /** The stored cache as native memory (caller frees), or null when there is none or it does not belong to this device. */
    private static ByteBuffer readStoredCache(Path file) {
        ByteBuffer data = null;
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            long size = Files.size(file);
            if (size < CACHE_HEADER_SIZE || size > MAX_CACHE_FILE_BYTES) {
                VkLog.LOGGER.warn("Ignoring the pipeline cache {}: unexpected size of {} bytes", file, size);
                discardCacheFile(file);
                return null;
            }
            data = MemoryUtil.memAlloc((int) size);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                while (data.hasRemaining() && channel.read(data) >= 0) {
                    // keep reading
                }
            }
            if (data.hasRemaining()) {
                VkLog.LOGGER.warn("Ignoring the pipeline cache {}: it changed while it was read", file);
                MemoryUtil.memFree(data);
                return null;
            }
            data.flip();
            String mismatch = headerMismatch(data);
            if (mismatch != null) {
                VkLog.LOGGER.info("Discarding the stored pipeline cache: {}", mismatch);
                MemoryUtil.memFree(data);
                discardCacheFile(file);
                return null;
            }
            return data;
        } catch (IOException | RuntimeException e) {
            VkLog.LOGGER.warn("Could not read the pipeline cache {}, starting with an empty one", file, e);
            if (data != null) {
                MemoryUtil.memFree(data);
            }
            return null;
        }
    }

    /**
     * Checks the VkPipelineCacheHeaderVersionOne header (headerSize, version, vendorID, deviceID, pipelineCacheUUID) against the
     * physical device; returns what differs, or null when the cache was written by this device and driver.
     */
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
        VkPhysicalDevice physicalDevice = RenderCore.physicalDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, props);
            if (header.getInt(8) != props.vendorID() || header.getInt(12) != props.deviceID()) {
                return "it was written by another GPU (vendor 0x" + Integer.toHexString(header.getInt(8)) + ", device 0x"
                        + Integer.toHexString(header.getInt(12)) + ")";
            }
            for (int i = 0; i < VK10.VK_UUID_SIZE; i++) {
                if (header.get(16 + i) != props.pipelineCacheUUID(i)) {
                    return "it was written by another driver version (pipelineCacheUUID differs)";
                }
            }
        }
        return null;
    }

    private static void discardCacheFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            VkLog.LOGGER.debug("Could not delete the pipeline cache {}: {}", file, e.toString());
        }
    }

    /**
     * The final save of {@link #shutdown()}: waits for the writer of a periodic save, then writes what is still missing from the
     * stored cache, synchronously.
     */
    private static void saveCache(VkDevice device) throws IOException {
        Thread running = cacheWriter;
        if (running != null) {
            try {
                running.join(CACHE_WRITER_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (pipelineCount == savedPipelineCount) {
            return;     // nothing new since the file was written (or nothing was created this session: a loaded cache is complete)
        }
        ByteBuffer data = snapshotCache(device);
        if (data != null) {
            persist(++cacheSnapshotSeq, generation, pipelineCount, data, cacheFile());
        }
    }

    /** One frame of the periodic save: refreshes the stored cache when pipelines were created since it was written. */
    private static void periodicSave() {
        if (pipelineCache == 0L || pipelineCount == savedPipelineCount) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastCacheSaveNanos < CACHE_SAVE_INTERVAL_NANOS) {
            return;
        }
        Thread running = cacheWriter;
        if (running != null && running.isAlive()) {
            return;     // the previous snapshot is still being written (slow disk): look again at a later frame
        }
        lastCacheSaveNanos = now;
        // The snapshot is taken here, on the render thread, where the cache is not in use (the call takes a fraction of a
        // millisecond); only the file I/O, which can stall for tens of milliseconds, moves to a writer thread.
        ByteBuffer data = null;
        try {
            data = snapshotCache(RenderCore.device());
            if (data == null) {
                return;     // reported; the next attempt is one interval away
            }
            final ByteBuffer snapshot = data;
            final long seq = ++cacheSnapshotSeq;
            final int gen = generation;
            final int count = pipelineCount;
            final Path file = cacheFile();
            Thread writer = new Thread(() -> {
                try {
                    persist(seq, gen, count, snapshot, file);
                } catch (Throwable t) {
                    warnCacheSave("Could not save the pipeline cache", t);
                }
            }, "FML-Vulkan pipeline cache writer");
            writer.setDaemon(true);
            writer.start();
            cacheWriter = writer;
            data = null;    // the writer owns the buffer now
        } catch (Throwable t) {
            warnCacheSave("Could not save the pipeline cache", t);
        } finally {
            if (data != null) {
                MemoryUtil.memFree(data);
            }
        }
    }

    /** Per-frame hook of the periodic save (a listener cannot be removed from RenderCore, so a dead one is switched off). */
    private static final class CacheSaver implements RenderCore.FrameListener {
        private boolean active = true;

        @Override
        public void onFrameBegin() {
            if (active) {
                periodicSave();
            }
        }
    }

    /** The cache data as native memory that the caller frees (or hands to {@link #persist}); null, reported, when there is none. */
    private static ByteBuffer snapshotCache(VkDevice device) {
        ByteBuffer data = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // the cache of a game session is far bigger than the 64 KiB thread stack: native memory, not stack memory
            PointerBuffer pSize = stack.callocPointer(1);
            int result = VK10.vkGetPipelineCacheData(device, pipelineCache, pSize, null);
            long size = pSize.get(0);
            if (result != VK10.VK_SUCCESS || size < CACHE_HEADER_SIZE || size > MAX_CACHE_FILE_BYTES) {
                warnCacheSave("Not saving the pipeline cache: vkGetPipelineCacheData -> VkResult " + result + ", " + size + " bytes", null);
                return null;
            }
            data = MemoryUtil.memAlloc((int) size);
            result = VK10.vkGetPipelineCacheData(device, pipelineCache, pSize, data);
            if (result != VK10.VK_SUCCESS) {
                warnCacheSave("Not saving the pipeline cache: vkGetPipelineCacheData -> VkResult " + result, null);
                return null;
            }
            data.limit((int) pSize.get(0));
            ByteBuffer snapshot = data;
            data = null;
            return snapshot;
        } finally {
            if (data != null) {
                MemoryUtil.memFree(data);
            }
        }
    }

    /**
     * Writes a snapshot to the cache file unless a newer one was written already (the writer thread of a periodic save and the
     * final save of shutdown can overlap), and frees the buffer. {@code count} is the number of pipelines the snapshot holds.
     */
    private static void persist(long seq, int gen, int count, ByteBuffer data, Path file) throws IOException {
        int size = data.remaining();
        try {
            synchronized (CACHE_FILE_LOCK) {
                if (seq <= cacheFileSeq) {
                    return;
                }
                writeAtomically(file, data);
                cacheFileSeq = seq;
                if (gen == generation) {        // not a writer that outlived a shutdown
                    savedPipelineCount = count;
                }
            }
            VkLog.LOGGER.debug("Pipeline cache saved to {} ({} bytes, {} pipelines)", file, size, count);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /** The first failure is a warning, the repeats (the next interval, the next frame) are debug output. */
    private static void warnCacheSave(String what, Throwable cause) {
        if (warnedCacheSave) {
            VkLog.LOGGER.debug(what, cause);
        } else {
            warnedCacheSave = true;
            VkLog.LOGGER.warn(what, cause);
        }
    }

    /** Writes next to the target and renames, so a crash cannot leave a truncated cache behind. */
    private static void writeAtomically(Path file, ByteBuffer data) throws IOException {
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

    // ---------------------------------------------------------------------------------------------------------
    // graphics pipelines
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Brings keys that describe the same Vulkan pipeline to one value, so GL state that has no effect (blend factors while
     * blending is off, the compare op while the depth test is off, ...) does not multiply pipelines, and applies the device
     * limits (polygon mode). The result is what {@link #createPipeline} realizes.
     */
    private static long normalize(long key) {
        key &= KEY_MASK;
        if (PipelineKey.get(key, PipelineKey.BLEND_ENABLE) == 0) {
            key = PipelineKey.set(key, PipelineKey.SRC_COLOR_BLEND_FACTOR, VK10.VK_BLEND_FACTOR_ONE);
            key = PipelineKey.set(key, PipelineKey.DST_COLOR_BLEND_FACTOR, VK10.VK_BLEND_FACTOR_ZERO);
            key = PipelineKey.set(key, PipelineKey.SRC_ALPHA_BLEND_FACTOR, VK10.VK_BLEND_FACTOR_ONE);
            key = PipelineKey.set(key, PipelineKey.DST_ALPHA_BLEND_FACTOR, VK10.VK_BLEND_FACTOR_ZERO);
            key = PipelineKey.set(key, PipelineKey.COLOR_BLEND_OP, VK10.VK_BLEND_OP_ADD);
            key = PipelineKey.set(key, PipelineKey.ALPHA_BLEND_OP, VK10.VK_BLEND_OP_ADD);
        }
        if (PipelineKey.get(key, PipelineKey.DEPTH_TEST_ENABLE) == 0) {
            // Vulkan (like GL) never writes depth while the depth test is off, and the compare op is unused then
            key = PipelineKey.set(key, PipelineKey.DEPTH_WRITE_ENABLE, 0);
            key = PipelineKey.set(key, PipelineKey.DEPTH_COMPARE_OP, VK10.VK_COMPARE_OP_ALWAYS);
        }
        int topology = PipelineKey.get(key, PipelineKey.TOPOLOGY);
        boolean triangles = topology == VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
                || topology == VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
                || topology == VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
        if (!triangles) {
            // polygon mode, face culling and the winding order only apply to triangles
            key = PipelineKey.set(key, PipelineKey.POLYGON_MODE, VK10.VK_POLYGON_MODE_FILL);
            key = PipelineKey.set(key, PipelineKey.CULL_MODE, VK10.VK_CULL_MODE_NONE);
        } else {
            int mode = PipelineKey.get(key, PipelineKey.POLYGON_MODE);
            if (mode != VK10.VK_POLYGON_MODE_FILL
                    && (!fillModeNonSolid || (mode == VK10.VK_POLYGON_MODE_POINT && moltenVk))) {
                // wireframe/point fill needs fillModeNonSolid; Metal cannot rasterize triangles as points (pointPolygons)
                if (!warnedPolygonModeFallback) {
                    warnedPolygonModeFallback = true;
                    VkLog.LOGGER.warn("glPolygonMode({}) is not supported by this device, triangles are filled",
                            mode == VK10.VK_POLYGON_MODE_LINE ? "GL_LINE" : "GL_POINT");
                }
                key = PipelineKey.set(key, PipelineKey.POLYGON_MODE, VK10.VK_POLYGON_MODE_FILL);
            }
        }
        if (PipelineKey.get(key, PipelineKey.CULL_MODE) == VK10.VK_CULL_MODE_NONE) {
            key = PipelineKey.set(key, PipelineKey.FRONT_FACE_CLOCKWISE, 0);
        }
        return key;
    }

    /** Creates the pipeline of a normalized key; 0 when the driver refuses it ({@link #createResult} holds the VkResult then). */
    private static long createPipeline(long key, VertexLayout layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean flat = PipelineKey.get(key, PipelineKey.FLAT_SHADING) != 0;

            // stages; the alpha test switch is a specialization constant of the fragment shader (no discard without it)
            ByteBuffer specData = stack.malloc(4);
            specData.putInt(0, PipelineKey.get(key, PipelineKey.ALPHA_TEST));
            VkSpecializationMapEntry.Buffer specEntries = VkSpecializationMapEntry.calloc(1, stack);
            specEntries.get(0)
                    .constantID(ShaderInterface.SPEC_ALPHA_TEST)
                    .offset(0)
                    .size(4);
            VkSpecializationInfo specInfo = VkSpecializationInfo.calloc(stack)
                    .pMapEntries(specEntries)
                    .pData(specData);

            ByteBuffer entryPoint = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                    .sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_VERTEX_BIT)
                    .module(flat ? vertFlatModule : vertModule)
                    .pName(entryPoint);
            stages.get(1)
                    .sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(flat ? fragFlatModule : fragModule)
                    .pName(entryPoint)
                    .pSpecializationInfo(specInfo);

            // vertex input: one binding per binding some present attribute uses, plus the dummy binding 7 (instance rate,
            // stride 16) when an attribute is absent. Binding numbers must be unique and only used bindings may be listed.
            int realBindings = layout.bindingMask();
            boolean needsDummy = false;
            for (int a = 0; a < VertexLayout.ATTR_COUNT; a++) {
                needsDummy |= layout.format(a) == 0;
            }
            VkVertexInputBindingDescription.Buffer bindings =
                    VkVertexInputBindingDescription.calloc(Integer.bitCount(realBindings) + (needsDummy ? 1 : 0), stack);
            int next = 0;
            for (int b = 0; b < VertexLayout.MAX_BINDINGS; b++) {
                if ((realBindings & (1 << b)) != 0) {
                    bindings.get(next++)
                            .binding(b)
                            .stride(layout.bindingStride(b))
                            .inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                }
            }
            if (needsDummy) {
                bindings.get(next)
                        .binding(VertexLayout.DUMMY_BINDING)
                        .stride(ShaderInterface.DUMMY_BINDING_STRIDE)
                        .inputRate(VK10.VK_VERTEX_INPUT_RATE_INSTANCE);
            }
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(VertexLayout.ATTR_COUNT, stack);
            for (int a = 0; a < VertexLayout.ATTR_COUNT; a++) {
                boolean present = layout.format(a) != 0;
                attributes.get(a)
                        .location(a)
                        .binding(present ? layout.binding(a) : VertexLayout.DUMMY_BINDING)
                        .format(present ? layout.format(a) : VK10.VK_FORMAT_R32G32B32A32_SFLOAT)
                        .offset(present ? layout.offset(a) : 0);
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pVertexBindingDescriptions(bindings)
                    .pVertexAttributeDescriptions(attributes);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .topology(PipelineKey.get(key, PipelineKey.TOPOLOGY))
                    .primitiveRestartEnable(false);

            // viewport and scissor are dynamic: only the counts matter
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1)
                    .scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(PipelineKey.get(key, PipelineKey.POLYGON_MODE))
                    .cullMode(PipelineKey.get(key, PipelineKey.CULL_MODE))
                    .frontFace(PipelineKey.get(key, PipelineKey.FRONT_FACE_CLOCKWISE) != 0
                            ? VK10.VK_FRONT_FACE_CLOCKWISE : VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(PipelineKey.get(key, PipelineKey.DEPTH_BIAS_ENABLE) != 0)
                    .lineWidth(1.0f);       // dynamic (VK_DYNAMIC_STATE_LINE_WIDTH)

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(PipelineKey.get(key, PipelineKey.DEPTH_TEST_ENABLE) != 0)
                    .depthWriteEnable(PipelineKey.get(key, PipelineKey.DEPTH_WRITE_ENABLE) != 0)
                    .depthCompareOp(PipelineKey.get(key, PipelineKey.DEPTH_COMPARE_OP))
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            VkPipelineColorBlendAttachmentState.Buffer attachments = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            attachments.get(0)
                    .blendEnable(PipelineKey.get(key, PipelineKey.BLEND_ENABLE) != 0)
                    .srcColorBlendFactor(PipelineKey.get(key, PipelineKey.SRC_COLOR_BLEND_FACTOR))
                    .dstColorBlendFactor(PipelineKey.get(key, PipelineKey.DST_COLOR_BLEND_FACTOR))
                    .colorBlendOp(PipelineKey.get(key, PipelineKey.COLOR_BLEND_OP))
                    .srcAlphaBlendFactor(PipelineKey.get(key, PipelineKey.SRC_ALPHA_BLEND_FACTOR))
                    .dstAlphaBlendFactor(PipelineKey.get(key, PipelineKey.DST_ALPHA_BLEND_FACTOR))
                    .alphaBlendOp(PipelineKey.get(key, PipelineKey.ALPHA_BLEND_OP))
                    .colorWriteMask(PipelineKey.get(key, PipelineKey.COLOR_WRITE_MASK));
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(attachments);

            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR,
                            VK10.VK_DYNAMIC_STATE_DEPTH_BIAS, VK10.VK_DYNAMIC_STATE_LINE_WIDTH,
                            VK10.VK_DYNAMIC_STATE_BLEND_CONSTANTS));

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0)
                    .sType$Default()
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewport)
                    .pRasterizationState(rasterization)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout)
                    .renderPass(RenderCore.renderPass())
                    .subpass(0);

            LongBuffer pPipeline = stack.longs(0L);
            int result = VK10.vkCreateGraphicsPipelines(RenderCore.device(), pipelineCache, info, null, pPipeline);
            createResult = result;
            return result == VK10.VK_SUCCESS ? pPipeline.get(0) : 0L;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // uniform descriptor sets
    // ---------------------------------------------------------------------------------------------------------

    private static long allocateUniformSet(long uniformBuffer) {
        VkDevice device = RenderCore.device();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSet = stack.mallocLong(1);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(uniformSetLayout));

            int result = VK11.VK_ERROR_OUT_OF_POOL_MEMORY;
            if (uniformPoolCount > 0 && uniformPoolSets < UNIFORM_POOL_SETS) {
                allocInfo.descriptorPool(uniformPools[uniformPoolCount - 1]);
                result = VK10.vkAllocateDescriptorSets(device, allocInfo, pSet);
            }
            if (result == VK11.VK_ERROR_OUT_OF_POOL_MEMORY || result == VK10.VK_ERROR_FRAGMENTED_POOL) {
                // the newest pool is full (or there is none yet): all sets stay alive, so just start another one
                allocInfo.descriptorPool(createUniformPool(device));
                uniformPoolSets = 0;
                result = VK10.vkAllocateDescriptorSets(device, allocInfo, pSet);
            }
            check(result, "vkAllocateDescriptorSets(uniform set)");
            uniformPoolSets++;
            long set = pSet.get(0);

            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.get(0)
                    .buffer(uniformBuffer)
                    .offset(0)
                    .range(ShaderInterface.UBO_SIZE);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                    .sType$Default()
                    .dstSet(set)
                    .dstBinding(ShaderInterface.BINDING_UNIFORMS)
                    .descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .pBufferInfo(bufferInfo);
            VK10.vkUpdateDescriptorSets(device, write, null);

            uniformSets.put(uniformBuffer, set);
            uniformSetPools.put(set, uniformPools[uniformPoolCount - 1]);
            return set;
        }
    }

    private static long createUniformPool(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0)
                    .type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .descriptorCount(UNIFORM_POOL_SETS);
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(UNIFORM_POOL_SETS)
                    .pPoolSizes(sizes);
            LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(device, info, null, pPool), "vkCreateDescriptorPool(uniform sets)");
            if (uniformPoolCount == uniformPools.length) {
                uniformPools = Arrays.copyOf(uniformPools, uniformPoolCount * 2);
            }
            uniformPools[uniformPoolCount++] = pPool.get(0);
            return pPool.get(0);
        }
    }

    private static void check(int result, String what) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + result
                    + (result == VK10.VK_ERROR_DEVICE_LOST ? " (device lost)" : ""));
        }
    }
}

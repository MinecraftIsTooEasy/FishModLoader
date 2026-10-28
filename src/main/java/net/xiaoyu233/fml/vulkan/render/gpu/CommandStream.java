package net.xiaoyu233.fml.vulkan.render.gpu;

import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.core.DeviceInfo;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StaticBuffer;
import net.xiaoyu233.fml.vulkan.pipeline.Pipelines;
import net.xiaoyu233.fml.vulkan.pipeline.ShaderInterface;
import net.xiaoyu233.fml.vulkan.pipeline.VertexLayout;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTExtendedDynamicState;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/**
 * Command recording facade shared by the native renderer and the safety net (ARCHITECTURE-NATIVE 5.6): the phase-1 command recorder
 * generalised. It keeps redundant-bind elimination for the CURRENT main command buffer and resets itself in
 * {@code RenderCore.FrameListener.onCommandBufferReset}. Every draw and bind call first calls {@code RenderCore.ensureRenderPass()},
 * draws also (re)apply the dirty dynamic state, and the dummy vertex binding 7 is bound with every call that can precede a draw.
 * {@link #bindPipeline} accepts 0 (what {@link Pipelines#get} returns for a pipeline the driver cannot create): the draws that
 * follow are dropped until a real pipeline is bound. The phase-1 {@code pipeline.CommandRecorder} forwards to this class, so the
 * emulation and the native batches share one set of bind caches; whoever records raw {@code vkCmd*} calls calls {@link #invalidate()}.
 *
 * <p>Render thread only. Nothing here allocates on the Java heap per call: command arguments go through a small native scratch
 * block (the {@code nvk*} entry points) and the push constants are compared as 32-bit words against a shadow copy.
 */
public final class CommandStream {
    private static final int GRAPHICS = VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
    private static final int PUSH_STAGES = VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
    private static final int BINDINGS = VertexLayout.DUMMY_BINDING + 1;      // real bindings 0..6 and the dummy binding 7
    private static final int PUSH_WORDS = ShaderInterface.PUSH_CONSTANT_SIZE / Integer.BYTES;
    private static final int SETS = 4;                                   // descriptor sets 0..3 are tracked

    // Native scratch memory for command arguments. Allocated once for the life of the process (a few bytes) so that a stray
    // call after shutdown can never write through a dangling pointer.
    private static final long SCRATCH = MemoryUtil.nmemAllocChecked(128);
    private static final long S_HANDLE = SCRATCH;                 // long: descriptor set / buffer handle
    private static final long S_OFFSET = SCRATCH + 8;             // long: buffer offset
    private static final long S_DYNAMIC_OFFSET = SCRATCH + 16;    // int: dynamic uniform offset
    private static final long S_BLEND = SCRATCH + 32;             // 4 floats
    private static final long S_VIEWPORT = SCRATCH + 48;          // VkViewport: 6 floats
    private static final long S_SCISSOR = SCRATCH + 80;           // VkRect2D: 4 ints

    // ---- what the current main command buffer has bound (0 / -1 = nothing or unknown) ----
    private static long boundPipeline;
    private static boolean bindingLegacy;
    private static boolean boundHasLineBlendDynamic;   // only phase-1 pipelines declare LINE_WIDTH/BLEND_CONSTANTS dynamic (VUID -08608)
    private static boolean pipelineMissing;     // the last bindPipeline was for "no pipeline" (0): draws are dropped, see bindPipeline
    private static final long[] boundSet = new long[SETS];
    private static int boundUniformOffset = -1;      // dynamic offset of set 0
    /** Pipeline layout the descriptor set and push constant binds are recorded with; the phase-1 layout unless a native program set its own. */
    private static long layout;
    private static final long[] boundVertexBuffer = new long[BINDINGS];
    private static final long[] boundVertexOffset = new long[BINDINGS];
    private static long boundIndexBuffer;
    private static long boundIndexOffset;
    private static int boundIndexType = -1;
    private static final int[] pushShadow = new int[PUSH_WORDS];        // the words pushed into this command buffer
    private static long pushValid;                                      // bit i: word i has been pushed into this command buffer

    // ---- dynamic state as requested by the callers; a dirty flag means the command buffer does not have the value yet ----
    private static boolean viewportSet;     // false until the first setViewport: the full framebuffer is used then
    private static float viewportX, viewportY, viewportW, viewportH;
    private static boolean scissorSet;      // false until the first setScissor: the full framebuffer is used then
    private static int scissorX, scissorY, scissorW, scissorH;
    private static float depthBiasConstant, depthBiasSlope;
    private static float lineWidth = 1.0f;
    private static float blendR, blendG, blendB, blendA;
    private static boolean viewportDirty = true;
    private static boolean scissorDirty = true;
    private static boolean depthBiasDirty = true;
    private static boolean lineWidthDirty = true;
    private static boolean blendDirty = true;

    // ---- extended dynamic state (VK_EXT_extended_dynamic_state), only when requested through setDynamicDepthCull ----
    private static boolean dynamicDepthCullUsed;    // the native pipelines declare the four states dynamic
    private static boolean dynamicDepthTest, dynamicDepthWrite;
    private static int dynamicDepthCompareOp, dynamicCullMode;
    private static boolean dynamicDepthCullDirty = true;

    // ---- device capabilities (set by onPipelinesInit) ----
    private static boolean wideLines;
    private static float minLineWidth = 1.0f;
    private static float maxLineWidth = 1.0f;

    // ---- dummy binding 7: a 16-byte zero buffer that feeds the vertex attributes a layout does not have ----
    private static ByteBuffer dummyData;
    private static StaticBuffer dummyBuffer;
    private static long dummyHandle;
    private static long dummyOffset;
    private static Listener listener;
    private static boolean warnedNoPipeline;
    private static boolean warnedNoIndexBuffer;

    // ---- counters for the debug stats ----
    private static int drawCalls;
    private static int indexedDrawCalls;
    private static int pipelineBinds;
    private static int descriptorBinds;
    private static int pushConstantUpdates;
    private static int vertexBufferBinds;
    private static int redundantBindsSkipped;
    private static int indirectDrawCalls;
    private static int encodedDraws;
    private static Stats lastFrame = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);

    private CommandStream() {
    }

    /**
     * Draw statistics of one frame. {@code drawCalls} counts every vkCmdDraw/vkCmdDrawIndexed ({@code indexedDrawCalls} of them
     * indexed); the binds are the Vulkan commands that were actually recorded, {@code redundantBindsSkipped} the bind, push
     * and index/vertex buffer calls the cache made unnecessary. {@code indirectDrawCalls} counts the vkCmdDrawIndexedIndirect commands
     * (they are part of {@code drawCalls} too) and {@code encodedDraws} the number of draws all commands of the frame expand to (an
     * indirect command of count N counts N): the number of draws the GPU front end processes.
     */
    public record Stats(int drawCalls, int indexedDrawCalls, int pipelineBinds, int descriptorBinds, int pushConstantUpdates,
                        int vertexBufferBinds, int redundantBindsSkipped, int indirectDrawCalls, int encodedDraws) {
    }

    /** Resets the cache of bound state and the pending dynamic state on every (re)begun main command buffer. */
    private static final class Listener implements RenderCore.FrameListener {
        private boolean active = true;

        @Override
        public void onFrameBegin() {
            if (active) {
                nextFrame();
            }
        }

        @Override
        public void onCommandBufferReset() {
            if (active) {
                reset();
            }
        }

        @Override
        public void onResize(int w, int h) {
            if (active) {
                // the default viewport/scissor and the scissor clamp depend on the framebuffer size
                viewportDirty = true;
                scissorDirty = true;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // lifecycle (called by Pipelines)
    // ---------------------------------------------------------------------------------------------------------

    /** Reads the device limits, creates the dummy buffer and registers the frame listener. */
    public static void onPipelinesInit() {
        DeviceInfo info = RenderCore.deviceInfo();
        wideLines = info.wideLines();
        float[] range = info.lineWidthRange();
        minLineWidth = range[0];
        maxLineWidth = range[1];
        lineWidth = 1.0f;
        pipelineMissing = false;
        warnedNoPipeline = false;
        warnedNoIndexBuffer = false;
        resetCounters();
        lastFrame = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);
        layout = Pipelines.pipelineLayout();
        dynamicDepthCullUsed = false;

        // the zero data stays allocated until shutdown: no assumption about when createStatic reads it
        dummyData = MemoryUtil.memCalloc(ShaderInterface.DUMMY_BUFFER_SIZE);
        dummyBuffer = RenderCore.createStatic(dummyData, VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        dummyHandle = dummyBuffer.buffer();
        dummyOffset = dummyBuffer.offset();

        reset();
        listener = new Listener();
        RenderCore.addListener(listener);
    }

    /** Releases the dummy buffer and silences the frame listener (a listener cannot be removed from RenderCore). */
    public static void onPipelinesShutdown() {
        if (listener != null) {
            listener.active = false;
            listener = null;
        }
        if (dummyBuffer != null) {
            dummyBuffer.free();
            dummyBuffer = null;
        }
        dummyHandle = 0L;
        dummyOffset = 0L;
        if (dummyData != null) {
            MemoryUtil.memFree(dummyData);
            dummyData = null;
        }
        reset();
    }

    /**
     * Forgets everything the recorder believes the command buffer has bound and marks all dynamic state dirty. Done for every
     * fresh main command buffer (frame begin, after {@code RenderCore.flushAndWait}) by the registered frame listener.
     */
    public static void reset() {
        boundPipeline = 0L;
        boundHasLineBlendDynamic = false;
        for (int i = 0; i < SETS; i++) {
            boundSet[i] = 0L;
        }
        boundUniformOffset = -1;
        for (int i = 0; i < BINDINGS; i++) {
            boundVertexBuffer[i] = 0L;
            boundVertexOffset[i] = 0L;
        }
        boundIndexBuffer = 0L;
        boundIndexOffset = 0L;
        boundIndexType = -1;
        pushValid = 0L;         // the shadow may equal the next push, but this command buffer has not seen it
        viewportDirty = true;
        scissorDirty = true;
        depthBiasDirty = true;
        lineWidthDirty = true;
        blendDirty = true;
        dynamicDepthCullDirty = true;
        viewportSet = false;
        scissorSet = false;
    }

    /**
     * The main command buffer of the current frame for raw {@code vkCmd*} calls. A render pass is NOT started (copies and barriers need
     * none; call {@code RenderCore.ensureRenderPass()} before recording draws yourself); call {@link #invalidate()} afterwards when the
     * commands changed any state the stream caches.
     */
    public static VkCommandBuffer cb() {
        return RenderCore.mainCommandBuffer();
    }

    /** Binds an index buffer; {@code u32} selects 32-bit indices, else 16-bit. */
    public static void bindIndexBuffer(long buffer, long offset, boolean u32) {
        bindIndexBuffer(buffer, offset, u32 ? VK10.VK_INDEX_TYPE_UINT32 : VK10.VK_INDEX_TYPE_UINT16);
    }

    /**
     * Pushes the remaining bytes of the direct buffer {@code data} (position..limit) at byte {@code offset} of the push constant block.
     * Size and offset must be multiples of 4 and fit the 128 byte block.
     */
    public static void pushConstants(ByteBuffer data, int offset) {
        if (!data.isDirect()) {
            throw new IllegalArgumentException("push constant data must be a direct buffer");
        }
        pushConstants(MemoryUtil.memAddress(data), offset, data.remaining());
    }

    /** Back to "no scissor requested": the full active target. */
    public static void setScissorFull() {
        if (scissorSet) {
            scissorSet = false;
            scissorDirty = true;
        }
    }

    /**
     * Marks everything the recorder cached as unknown after somebody recorded raw {@code vkCmd*} calls into the main command buffer
     * (pipeline, descriptor sets, push constants, vertex/index buffers, dynamic state); the next draw re-establishes it.
     */
    public static void invalidate() {
        reset();
    }

    /** The render target changed: the default viewport / scissor (full framebuffer) must be recorded again. */
    public static void onTargetChanged() {
        viewportDirty = true;
        scissorDirty = true;
    }

    /**
     * Pipeline layout the following descriptor set and push constant binds use (a native program's layout from PipelineLibrary); the
     * phase-1 layout is restored by every phase-1 {@link #bindPipeline}. A different layout forgets the cached sets and push constants
     * (sets bound with an incompatible layout are disturbed by the Vulkan rules).
     */
    public static void useLayout(long pipelineLayout) {
        long wanted = pipelineLayout != 0L ? pipelineLayout : Pipelines.pipelineLayout();
        if (wanted != layout) {
            layout = wanted;
            for (int i = 0; i < SETS; i++) {
                boundSet[i] = 0L;
            }
            boundUniformOffset = -1;
            pushValid = 0L;
        }
    }

    private static void nextFrame() {
        lastFrame = currentStats();
        resetCounters();
    }

    // ---------------------------------------------------------------------------------------------------------
    // binds
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Binds the pipeline. 0, what {@link Pipelines#get} returns for a pipeline the driver cannot create, binds nothing and makes
     * {@code draw}/{@code drawIndexed} drop their draw until a real pipeline is bound (the failure was reported by Pipelines;
     * GL skips what it cannot draw). The pipeline that the command buffer really has bound stays cached.
     */
    public static void bindPipeline(long pipeline) {
        useLayout(Pipelines.pipelineLayout());
        bindingLegacy = true;
        try {
            bindPipelineKeepLayout(pipeline);
        } finally {
            bindingLegacy = false;
        }
    }

    /** {@link #bindPipeline} for pipelines of the native layer: the pipeline layout stays what {@link #useLayout} selected. */
    public static void bindPipelineKeepLayout(long pipeline) {
        VkCommandBuffer cb = prepare();
        pipelineMissing = pipeline == 0L;
        if (pipelineMissing) {
            return;
        }
        if (boundPipeline == pipeline) {
            redundantBindsSkipped++;
            return;
        }
        boundPipeline = pipeline;
        boundHasLineBlendDynamic = bindingLegacy;
        if (bindingLegacy) {
            // the legacy pipelines declare LINE_WIDTH/BLEND_CONSTANTS dynamic; make sure the values are (re)set for them
            lineWidthDirty = true;
            blendDirty = true;
        }
        // another pipeline bind invalidates dynamic state set before it (a phase-1 pipeline has static depth/cull state)
        dynamicDepthCullDirty = true;
        VK10.vkCmdBindPipeline(cb, GRAPHICS, pipeline);
        pipelineBinds++;
    }

    public static void bindUniforms(long uniformSet, int dynamicOffset) {
        bindDescriptorSet(ShaderInterface.SET_UNIFORMS, uniformSet, dynamicOffset);
    }

    /** Unit 0 binds set 1, unit 1 binds set 2. */
    public static void bindTexture(int unit, long descriptorSet) {
        if (unit != 0 && unit != 1) {
            return;     // the shaders sample units 0 and 1 only
        }
        bindDescriptorSet(unit == 0 ? ShaderInterface.SET_TEXTURE0 : ShaderInterface.SET_TEXTURE1, descriptorSet, 0);
    }

    /**
     * Binds descriptor set {@code set} (0..3) with the current layout. Set 0 is the dynamic uniform block and takes {@code dynamicOffset};
     * the other sets have no dynamic offsets and ignore it. Skipped when the command buffer already has exactly this binding.
     */
    public static void bindDescriptorSet(int set, long descriptorSet, int dynamicOffset) {
        if (set < 0 || set >= SETS) {
            throw new IllegalArgumentException("descriptor set " + set + " is not in 0.." + (SETS - 1));
        }
        VkCommandBuffer cb = prepare();
        if (set == ShaderInterface.SET_UNIFORMS) {
            if (boundSet[0] == descriptorSet && boundUniformOffset == dynamicOffset) {
                redundantBindsSkipped++;
                return;
            }
            boundSet[0] = descriptorSet;
            boundUniformOffset = dynamicOffset;
            MemoryUtil.memPutLong(S_HANDLE, descriptorSet);
            MemoryUtil.memPutInt(S_DYNAMIC_OFFSET, dynamicOffset);
            VK10.nvkCmdBindDescriptorSets(cb, GRAPHICS, layout, 0, 1, S_HANDLE, 1, S_DYNAMIC_OFFSET);
        } else {
            if (boundSet[set] == descriptorSet) {
                redundantBindsSkipped++;
                return;
            }
            boundSet[set] = descriptorSet;
            MemoryUtil.memPutLong(S_HANDLE, descriptorSet);
            VK10.nvkCmdBindDescriptorSets(cb, GRAPHICS, layout, set, 1, S_HANDLE, 0, 0L);
        }
        descriptorBinds++;
    }

    /**
     * {@code address} = native memory holding the 128 push-constant bytes (8-byte aligned); skipped when identical to the last
     * push into this command buffer.
     */
    public static void pushConstants(long address) {
        if (address == 0L) {
            throw new IllegalArgumentException("push constant address is null");
        }
        pushConstants(address, 0, ShaderInterface.PUSH_CONSTANT_SIZE);
    }

    /**
     * Pushes {@code size} bytes (multiple of 4) at {@code offset} (multiple of 4) of the 128 byte push constant block, VERTEX and
     * FRAGMENT stages, read from native memory at {@code address}; skipped when every word of the range is already what the command
     * buffer has. Nothing is allocated.
     */
    public static void pushConstants(long address, int offset, int size) {
        if ((offset | size) < 0 || (offset & 3) != 0 || (size & 3) != 0 || offset + size > ShaderInterface.PUSH_CONSTANT_SIZE) {
            throw new IllegalArgumentException("push constant range " + offset + "+" + size + " is not 4-byte aligned inside "
                    + ShaderInterface.PUSH_CONSTANT_SIZE + " bytes");
        }
        VkCommandBuffer cb = prepare();
        if (size == 0) {
            return;
        }
        int first = offset >> 2;
        int words = size >> 2;
        long rangeMask = (words >= 64 ? -1L : (1L << words) - 1L) << first;
        boolean changed = (pushValid & rangeMask) != rangeMask;
        int[] shadow = pushShadow;
        for (int i = 0; i < words; i++) {
            int word = MemoryUtil.memGetInt(address + ((long) i << 2));
            if (shadow[first + i] != word) {
                shadow[first + i] = word;
                changed = true;
            }
        }
        if (!changed) {
            redundantBindsSkipped++;
            return;
        }
        pushValid |= rangeMask;
        VK10.nvkCmdPushConstants(cb, layout, PUSH_STAGES, offset, size, address);
        pushConstantUpdates++;
    }

    public static void bindVertexBuffer(int binding, long buffer, long offset) {
        if (binding < 0 || binding >= BINDINGS) {
            throw new IllegalArgumentException("vertex binding " + binding + " is not in 0.." + (BINDINGS - 1));
        }
        VkCommandBuffer cb = prepare();
        if (buffer == 0L) {
            return;     // VK_NULL_HANDLE is not a valid vertex buffer without robustness2 (nullDescriptor)
        }
        if (boundVertexBuffer[binding] == buffer && boundVertexOffset[binding] == offset) {
            redundantBindsSkipped++;
            return;
        }
        bindVertexBufferNow(cb, binding, buffer, offset);
    }

    public static void bindIndexBuffer(long buffer, long offset, int vkIndexType) {
        VkCommandBuffer cb = prepare();
        if (buffer == 0L) {
            return;
        }
        if (boundIndexBuffer == buffer && boundIndexOffset == offset && boundIndexType == vkIndexType) {
            redundantBindsSkipped++;
            return;
        }
        boundIndexBuffer = buffer;
        boundIndexOffset = offset;
        boundIndexType = vkIndexType;
        VK10.vkCmdBindIndexBuffer(cb, buffer, offset, vkIndexType);
        vertexBufferBinds++;
    }

    // ---------------------------------------------------------------------------------------------------------
    // dynamic state (recorded lazily, before the next draw)
    // ---------------------------------------------------------------------------------------------------------

    /** Vulkan coords; negative height allowed; depth 0..1. */
    public static void setViewport(float x, float y, float w, float h) {
        if (!viewportSet || viewportX != x || viewportY != y || viewportW != w || viewportH != h) {
            viewportSet = true;
            viewportX = x;
            viewportY = y;
            viewportW = w;
            viewportH = h;
            viewportDirty = true;
        }
    }

    /** Vulkan coords (top-left origin); the box may exceed the framebuffer, it is clamped when it is recorded. */
    public static void setScissor(int x, int y, int w, int h) {
        if (!scissorSet || scissorX != x || scissorY != y || scissorW != w || scissorH != h) {
            scissorSet = true;
            scissorX = x;
            scissorY = y;
            scissorW = w;
            scissorH = h;
            scissorDirty = true;
        }
    }

    public static void setDepthBias(float constantFactor, float slopeFactor) {
        if (depthBiasConstant != constantFactor || depthBiasSlope != slopeFactor) {
            depthBiasConstant = constantFactor;
            depthBiasSlope = slopeFactor;
            depthBiasDirty = true;
        }
    }

    /** Clamped to the device's lineWidthRange; 1.0 on devices without wideLines (Metal, many mobile GPUs). */
    public static void setLineWidth(float width) {
        float effective = 1.0f;
        if (wideLines && width > 0.0f) {
            effective = Math.min(Math.max(width, minLineWidth), maxLineWidth);
        }
        if (effective != lineWidth) {
            lineWidth = effective;
            lineWidthDirty = true;
        }
    }

    /**
     * VK_EXT_extended_dynamic_state: depth test enable, depth write enable, depth compare op and cull mode (a {@code VkCullModeFlags}
     * value) for the following draws. Only valid for pipelines created with those four states dynamic; ignored when the device did not
     * enable the extension (such pipelines then carry the state themselves).
     */
    public static void setDynamicDepthCull(boolean test, boolean write, int compareOp, int cullMode) {
        if (!RenderCore.deviceCaps().extendedDynamicState()) {
            return;
        }
        if (!dynamicDepthCullUsed || dynamicDepthTest != test || dynamicDepthWrite != write || dynamicDepthCompareOp != compareOp
                || dynamicCullMode != cullMode) {
            dynamicDepthCullUsed = true;
            dynamicDepthTest = test;
            dynamicDepthWrite = write;
            dynamicDepthCompareOp = compareOp;
            dynamicCullMode = cullMode;
            dynamicDepthCullDirty = true;
        }
    }

    public static void setBlendConstants(float r, float g, float b, float a) {
        if (blendR != r || blendG != g || blendB != b || blendA != a) {
            blendR = r;
            blendG = g;
            blendB = b;
            blendA = a;
            blendDirty = true;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // draws
    // ---------------------------------------------------------------------------------------------------------

    public static void draw(int vertexCount, int firstVertex) {
        if (vertexCount <= 0) {
            return;     // nothing to draw (D1 flushes with draw(0, 0))
        }
        VkCommandBuffer cb = prepare();
        if (!canDraw(false)) {
            return;
        }
        flushDynamicState(cb);
        VK10.vkCmdDraw(cb, vertexCount, 1, firstVertex, 0);
        drawCalls++;
        encodedDraws++;
    }

    public static void drawIndexed(int indexCount, int firstIndex, int vertexOffset) {
        drawIndexed(indexCount, 1, firstIndex, vertexOffset, 0);
    }

    public static void drawIndexed(int indexCount, int instances, int firstIndex, int vertexOffset, int firstInstance) {
        if (indexCount <= 0 || instances <= 0) {
            return;
        }
        VkCommandBuffer cb = prepare();
        if (!canDraw(true)) {
            return;
        }
        flushDynamicState(cb);
        VK10.vkCmdDrawIndexed(cb, indexCount, instances, firstIndex, vertexOffset, firstInstance);
        drawCalls++;
        indexedDrawCalls++;
        encodedDraws++;
    }

    /**
     * {@code count} VkDrawIndexedIndirectCommand records of {@code stride} bytes at {@code offset} of {@code buffer}. One
     * vkCmdDrawIndexedIndirect when the device has multiDrawIndirect (or {@code count} is 1), else {@code count} single-draw commands;
     * commands with a non-zero firstInstance additionally need drawIndirectFirstInstance (callers consult DeviceFeatures).
     */
    public static void drawIndexedIndirect(long buffer, long offset, int count, int stride) {
        if (count <= 0 || buffer == 0L) {
            return;
        }
        VkCommandBuffer cb = prepare();
        if (!canDraw(true)) {
            return;
        }
        flushDynamicState(cb);
        if (count == 1 || RenderCore.deviceCaps().multiDrawIndirect()) {
            VK10.vkCmdDrawIndexedIndirect(cb, buffer, offset, count, stride);
            drawCalls++;
            indexedDrawCalls++;
            indirectDrawCalls++;
        } else {
            for (int i = 0; i < count; i++) {
                VK10.vkCmdDrawIndexedIndirect(cb, buffer, offset + (long) i * stride, 1, stride);
                drawCalls++;
                indexedDrawCalls++;
                indirectDrawCalls++;
            }
        }
        encodedDraws += count;
    }

    // ---------------------------------------------------------------------------------------------------------
    // debug counters
    // ---------------------------------------------------------------------------------------------------------

    /** vkCmdDraw + vkCmdDrawIndexed recorded since this frame began. */
    public static int drawCalls() {
        return drawCalls;
    }

    /** The vkCmdDrawIndexed part of {@link #drawCalls()}. */
    public static int indexedDrawCalls() {
        return indexedDrawCalls;
    }

    /** vkCmdBindPipeline recorded (not counting the ones the cache avoided). */
    public static int pipelineBinds() {
        return pipelineBinds;
    }

    /** vkCmdBindDescriptorSets recorded (uniform and texture sets). */
    public static int descriptorBinds() {
        return descriptorBinds;
    }

    /** vkCmdPushConstants recorded. */
    public static int pushConstantUpdates() {
        return pushConstantUpdates;
    }

    /** vkCmdBindVertexBuffers + vkCmdBindIndexBuffer recorded. */
    public static int vertexBufferBinds() {
        return vertexBufferBinds;
    }

    /** bind/push calls that were redundant for the command buffer and therefore not recorded. */
    public static int redundantBindsSkipped() {
        return redundantBindsSkipped;
    }

    /** The counters since the current frame began. */
    public static Stats currentStats() {
        return new Stats(drawCalls, indexedDrawCalls, pipelineBinds, descriptorBinds, pushConstantUpdates, vertexBufferBinds,
                redundantBindsSkipped, indirectDrawCalls, encodedDraws);
    }

    /** The counters of the previous frame (the counters restart at every frame begin). */
    public static Stats lastFrameStats() {
        return lastFrame;
    }

    /** Zeroes the counters (also done automatically when a frame begins). */
    public static void resetCounters() {
        drawCalls = 0;
        indexedDrawCalls = 0;
        pipelineBinds = 0;
        descriptorBinds = 0;
        pushConstantUpdates = 0;
        vertexBufferBinds = 0;
        redundantBindsSkipped = 0;
        indirectDrawCalls = 0;
        encodedDraws = 0;
    }

    // ---------------------------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------------------------

    /** Begins the render pass if needed and makes sure the dummy vertex binding is bound; returns the main command buffer. */
    private static VkCommandBuffer prepare() {
        RenderCore.ensureRenderPass();
        VkCommandBuffer cb = RenderCore.mainCommandBuffer();
        long dummy = dummyHandle;
        if (dummy != 0L && (boundVertexBuffer[VertexLayout.DUMMY_BINDING] != dummy
                || boundVertexOffset[VertexLayout.DUMMY_BINDING] != dummyOffset)) {
            bindVertexBufferNow(cb, VertexLayout.DUMMY_BINDING, dummy, dummyOffset);
        }
        return cb;
    }

    /** Records the vertex buffer bind unconditionally and updates the cache (never calls {@link #prepare()}). */
    private static void bindVertexBufferNow(VkCommandBuffer cb, int binding, long buffer, long offset) {
        boundVertexBuffer[binding] = buffer;
        boundVertexOffset[binding] = offset;
        MemoryUtil.memPutLong(S_HANDLE, buffer);
        MemoryUtil.memPutLong(S_OFFSET, offset);
        VK10.nvkCmdBindVertexBuffers(cb, binding, 1, S_HANDLE, S_OFFSET);
        vertexBufferBinds++;
    }

    /** A draw without a bound pipeline (or index buffer) is invalid and crashes some drivers: drop it instead. */
    private static boolean canDraw(boolean indexed) {
        if (pipelineMissing) {
            return false;       // bindPipeline(0): Pipelines.get has reported why there is no pipeline
        }
        if (boundPipeline == 0L) {
            if (!warnedNoPipeline) {
                warnedNoPipeline = true;
                VkLog.LOGGER.warn("CommandStream: draw without a bound pipeline ignored", new IllegalStateException("no pipeline"));
            }
            return false;
        }
        if (indexed && boundIndexBuffer == 0L) {
            if (!warnedNoIndexBuffer) {
                warnedNoIndexBuffer = true;
                VkLog.LOGGER.warn("CommandStream: indexed draw without a bound index buffer ignored", new IllegalStateException("no index buffer"));
            }
            return false;
        }
        return true;
    }

    private static void flushDynamicState(VkCommandBuffer cb) {
        if (viewportDirty) {
            viewportDirty = false;
            float x = viewportX;
            float y = viewportY;
            float w = viewportW;
            float h = viewportH;
            if (!viewportSet) {
                x = 0.0f;
                y = RenderCore.activeHeight();
                w = RenderCore.activeWidth();
                h = -y;
            }
            if (!(w > 0.0f)) {
                w = 1.0f;       // Vulkan requires a positive width
            }
            if (h == 0.0f || h != h) {
                h = -1.0f;      // and a non-zero height
            }
            MemoryUtil.memPutFloat(S_VIEWPORT, x);
            MemoryUtil.memPutFloat(S_VIEWPORT + 4, y);
            MemoryUtil.memPutFloat(S_VIEWPORT + 8, w);
            MemoryUtil.memPutFloat(S_VIEWPORT + 12, h);
            MemoryUtil.memPutFloat(S_VIEWPORT + 16, 0.0f);
            MemoryUtil.memPutFloat(S_VIEWPORT + 20, 1.0f);
            VK10.nvkCmdSetViewport(cb, 0, 1, S_VIEWPORT);
        }

        if (scissorDirty) {
            scissorDirty = false;
            // a GL scissor box may stick out of the window; Vulkan wants a non-negative offset and extent. Clamp the box
            // (its edges, not its size) to the framebuffer.
            long fbW = RenderCore.activeWidth();
            long fbH = RenderCore.activeHeight();
            long x0 = 0L;
            long y0 = 0L;
            long x1 = fbW;
            long y1 = fbH;
            if (scissorSet) {
                x0 = scissorX;
                y0 = scissorY;
                x1 = x0 + scissorW;
                y1 = y0 + scissorH;
            }
            x0 = Math.min(Math.max(x0, 0L), fbW);
            y0 = Math.min(Math.max(y0, 0L), fbH);
            x1 = Math.min(Math.max(x1, x0), fbW);
            y1 = Math.min(Math.max(y1, y0), fbH);
            MemoryUtil.memPutInt(S_SCISSOR, (int) x0);
            MemoryUtil.memPutInt(S_SCISSOR + 4, (int) y0);
            MemoryUtil.memPutInt(S_SCISSOR + 8, (int) (x1 - x0));
            MemoryUtil.memPutInt(S_SCISSOR + 12, (int) (y1 - y0));
            VK10.nvkCmdSetScissor(cb, 0, 1, S_SCISSOR);
        }

        if (depthBiasDirty) {
            depthBiasDirty = false;
            VK10.vkCmdSetDepthBias(cb, depthBiasConstant, 0.0f, depthBiasSlope);
        }

        if (lineWidthDirty && boundHasLineBlendDynamic) {
            lineWidthDirty = false;
            VK10.vkCmdSetLineWidth(cb, lineWidth);
        }

        if (dynamicDepthCullDirty) {
            dynamicDepthCullDirty = false;
            if (dynamicDepthCullUsed) {
                EXTExtendedDynamicState.vkCmdSetDepthTestEnableEXT(cb, dynamicDepthTest);
                EXTExtendedDynamicState.vkCmdSetDepthWriteEnableEXT(cb, dynamicDepthWrite);
                EXTExtendedDynamicState.vkCmdSetDepthCompareOpEXT(cb, dynamicDepthCompareOp);
                EXTExtendedDynamicState.vkCmdSetCullModeEXT(cb, dynamicCullMode);
            }
        }

        if (blendDirty && boundHasLineBlendDynamic) {
            blendDirty = false;
            MemoryUtil.memPutFloat(S_BLEND, blendR);
            MemoryUtil.memPutFloat(S_BLEND + 4, blendG);
            MemoryUtil.memPutFloat(S_BLEND + 8, blendB);
            MemoryUtil.memPutFloat(S_BLEND + 12, blendA);
            VK10.nvkCmdSetBlendConstants(cb, S_BLEND);
        }
    }
}

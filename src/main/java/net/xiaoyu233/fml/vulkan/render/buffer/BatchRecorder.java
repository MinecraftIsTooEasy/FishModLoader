package net.xiaoyu233.fml.vulkan.render.buffer;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StreamSlice;
import net.xiaoyu233.fml.vulkan.render.gpu.CommandStream;
import net.xiaoyu233.fml.vulkan.render.gpu.DeviceFeatures;
import net.xiaoyu233.fml.vulkan.render.gpu.PassUniforms;
import net.xiaoyu233.fml.vulkan.render.gpu.PipelineLibrary;
import net.xiaoyu233.fml.vulkan.render.gpu.Program;
import net.xiaoyu233.fml.vulkan.render.state.AlphaRefs;
import net.xiaoyu233.fml.vulkan.render.state.MaterialKey;
import net.xiaoyu233.fml.vulkan.render.state.RenderType;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

/**
 * GPU recording of closed batch entries (1.8, NR-03 shared seam). {@link Batches} decides WHAT is drawn and in which order; this class
 * turns one entry into Vulkan commands: pipeline from {@code PipelineLibrary.get} (0 = skip, fallback accepted), pass uniforms at the
 * first draw after a change, texture descriptor sets (captured by the entry), dynamic state (line width, polygon offset, dynamic depth/cull),
 * a {@code RenderCore.streamVertex} slice copy of the vertex block, quad index buffer bind, draws split at {@link QuadIndexBuffers#MAX_QUADS_U16}
 * quads with {@code vertexOffset}. Render thread only, no per-call heap allocation.
 */
final class BatchRecorder {
    private static final int PC_SIZE = 128, PC_PARAMS = 80;
    /** Scratch push constant block (128 B, native, allocated once). */
    private static long pc;
    private static long dummySet;

    private BatchRecorder() {
    }

    private static long scratch() {
        if (pc == 0L) {
            pc = MemoryUtil.nmemCalloc(1, PC_SIZE);
        }
        return pc;
    }

    private static long dummy(long set) {
        if (set != 0L) {
            return set;
        }
        if (dummySet == 0L) {
            dummySet = Textures.descriptorSet(0);
        }
        return dummySet;
    }

    /** Pipeline bind + pass uniforms + texture sets + dynamic state shared by stream and static draws; false = skip the draw. */
    private static boolean setup(RenderType type, long texSet, long lightSet, float lineWidth, float polyFactor, float polyUnits) {
        long key = type.materialKey();
        Program program = Program.ofOrdinal(MaterialKey.program(key));
        if (program == null) {
            return false;
        }
        boolean dyn = DeviceFeatures.extendedDynamicState();
        long pipeline = PipelineLibrary.get(program, MaterialKey.pipelineKey(key, dyn), type.format());
        if (pipeline == 0L) {
            return false;
        }
        CommandStream.bindPipeline(pipeline);
        CommandStream.bindUniforms(PassUniforms.descriptorSet(), PassUniforms.currentOffset());
        CommandStream.bindTexture(0, dummy(texSet));
        CommandStream.bindTexture(1, dummy(lightSet));
        if (dyn) {
            boolean test = MaterialKey.depthTest(key);
            int cull = MaterialKey.cullFront(key) ? VK10.VK_CULL_MODE_FRONT_BIT
                    : MaterialKey.cullBack(key) ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE;
            CommandStream.setDynamicDepthCull(test, test && MaterialKey.depthWrite(key), test ? MaterialKey.depthFunc(key) : VK10.VK_COMPARE_OP_ALWAYS, cull);
        }
        if (MaterialKey.polygonOffset(key)) {
            CommandStream.setDepthBias(polyUnits, polyFactor);
        }
        if (type.format() == VertexFormat.LINE) {
            CommandStream.setLineWidth(lineWidth);
        }
        return true;
    }

    /**
     * Records {@code vertexCount} (multiple of 4, quads) vertices at {@code address} (off-heap, format of {@code type}) as one or more draws.
     * {@code texSet}/{@code lightSet} are the descriptor sets captured when the entry was closed (0 = dummy/none).
     * {@code lineWidth} is used for LINE format, {@code polyFactor/polyUnits} when the material key has polygon offset.
     */
    static void drawStream(RenderType type, long address, int vertexCount, long texSet, long lightSet, float lineWidth, float polyFactor, float polyUnits) {
        int quads = vertexCount >> 2;
        if (quads <= 0 || address == 0L) {
            return;
        }
        if (!setup(type, texSet, lightSet, lineWidth, polyFactor, polyUnits)) {
            return;
        }
        long key = type.materialKey();
        if (MaterialKey.alphaMode(key) == 2) {
            long p = scratch();
            MemoryUtil.memPutFloat(p + PC_PARAMS, AlphaRefs.value(MaterialKey.alphaRefIndex(key)));
            CommandStream.pushConstants(p + PC_PARAMS, PC_PARAMS, 4);
        }
        int bytes = vertexCount * type.format().stride();
        StreamSlice slice = RenderCore.streamVertex(bytes);
        MemoryUtil.memCopy(address, slice.address(), bytes);
        CommandStream.bindVertexBuffer(0, slice.buffer(), slice.offset());
        CommandStream.bindIndexBuffer(QuadIndexBuffers.u16Buffer(), QuadIndexBuffers.u16Offset(), false);
        int max = QuadIndexBuffers.MAX_QUADS_U16;
        int done = 0;
        while (done < quads) {
            int n = Math.min(max, quads - done);
            CommandStream.drawIndexed(n * 6, 0, done * 4);
            done += n;
        }
    }

    /** Records a static mesh draw (program STATIC): model matrix (16 floats, column major) and colour modulator pushed as constants. */
    static void drawStatic(StaticMesh mesh, RenderType type, float[] model, int colorAbgr, long texSet, long lightSet) {
        int quads = mesh.quadCount();
        // A mesh destroyed after this draw was queued must still be drawn (display-list semantics: the draw happened at the call).
        // StaticMesh.destroy defers the arena free by frames in flight, so the allocation stays valid until this phase is recorded.
        if (quads <= 0 || mesh.allocation == null) {
            return;
        }
        if (!setup(type, texSet, lightSet, 2.0F, 0.0F, 0.0F)) {
            return;
        }
        long key = type.materialKey();
        long p = scratch();
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(p + 4L * i, model[i]);
        }
        final float k = 1.0F / 255.0F;
        MemoryUtil.memPutFloat(p + 64, (colorAbgr & 0xFF) * k);
        MemoryUtil.memPutFloat(p + 68, ((colorAbgr >>> 8) & 0xFF) * k);
        MemoryUtil.memPutFloat(p + 72, ((colorAbgr >>> 16) & 0xFF) * k);
        MemoryUtil.memPutFloat(p + 76, (colorAbgr >>> 24) * k);
        MemoryUtil.memPutFloat(p + PC_PARAMS, MaterialKey.alphaMode(key) == 2 ? AlphaRefs.value(MaterialKey.alphaRefIndex(key)) : 0.0F);
        CommandStream.pushConstants(p, 0, 96);
        CommandStream.bindVertexBuffer(0, mesh.allocation.buffer, mesh.allocation.offset);
        boolean u32 = quads > QuadIndexBuffers.MAX_QUADS_U16;
        CommandStream.bindIndexBuffer(u32 ? QuadIndexBuffers.u32Buffer() : QuadIndexBuffers.u16Buffer(),
                u32 ? QuadIndexBuffers.u32Offset() : QuadIndexBuffers.u16Offset(), u32);
        int max = u32 ? QuadIndexBuffers.MAX_QUADS_U32 : QuadIndexBuffers.MAX_QUADS_U16;
        int done = 0;
        while (done < quads) {
            int n = Math.min(max, quads - done);
            CommandStream.drawIndexed(n * 6, 0, done * 4);
            done += n;
        }
    }

    /**
     * Frame start and command buffer reset hook: forgets the cached white-texture set, which may be recreated. Bind caches (pipeline,
     * uniform offset) live in {@code CommandStream}, which resets them per command buffer itself. Safe mid-frame (flushAndWait).
     */
    static void onFrameBegin() {
        dummySet = 0L;
    }
}

package net.xiaoyu233.fml.vulkan.render.buffer;

import net.xiaoyu233.fml.vulkan.render.gpu.GpuAllocation;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuArena;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** Collects object-space quads of a static mesh (capture target of {@code NativeTess}). */
public final class StaticMeshBuilder implements VertexConsumer {
    private BufferBuilder inner = new BufferBuilder(VertexFormat.UNIVERSAL, 4096);

    public StaticMeshBuilder() {
    }

    @Override
    public void vertex(float x, float y, float z, int abgr, float u, float v, int packedLight, int overlayArgb) {
        inner.vertex(x, y, z, abgr, u, v, packedLight, overlayArgb);
    }

    /** Releases the builder memory without uploading anything (abandoned capture). Idempotent; the builder must not be used afterwards. */
    public void discard() {
        BufferBuilder b = inner;
        inner = null;
        if (b != null) {
            b.free();
        }
    }

    /** Uploads the collected vertices and releases the builder memory. An empty builder yields a mesh without storage. */
    public StaticMesh build() {
        final BufferBuilder b = inner;
        if (b == null) {
            throw new IllegalStateException("builder already built");
        }
        inner = null;
        try {
            int quads = b.vertexCount() / 4;
            int bytes = b.bytes();
            if (quads == 0 || bytes == 0) {
                return new StaticMesh(0, null);
            }
            GpuArena arena = StaticMesh.arena();
            GpuAllocation a = arena.allocate(bytes);
            if (a == null) {
                throw new IllegalStateException("static mesh arena out of memory for " + bytes + " bytes");
            }
            ByteBuffer src = MemoryUtil.memByteBuffer(b.address(), bytes);
            arena.upload(a, src);
            return new StaticMesh(quads, a);
        } finally {
            b.free();
        }
    }
}

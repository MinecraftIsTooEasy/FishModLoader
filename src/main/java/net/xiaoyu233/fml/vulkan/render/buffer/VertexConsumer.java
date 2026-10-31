package net.xiaoyu233.fml.vulkan.render.buffer;

/** Receiver of final (already transformed and lit) vertices of one render type (1.6). */
public interface VertexConsumer {
    void vertex(float x, float y, float z, int abgr, float u, float v, int packedLight, int overlayArgb);

    default void line(float x0, float y0, float z0, float x1, float y1, float z1, int abgr) {
        throw new UnsupportedOperationException();
    }
}

package net.xiaoyu233.fml.vulkan.render.tess;

/** Receiver of the section-local quads of a mesh worker's {@code NativeTess}. */
public interface MeshSink {
    /** {@code raw} = MITE 8-int vertices, quads only. */
    void quads(int[] raw, int intCount, int vertexCount);
}

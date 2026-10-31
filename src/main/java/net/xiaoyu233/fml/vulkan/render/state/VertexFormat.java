package net.xiaoyu233.fml.vulkan.render.state;

/** Vertex layouts of the native renderer (1.5); the stride is the size of one vertex in bytes. */
public enum VertexFormat {
    TERRAIN(28), UNIVERSAL(32), LINE(32), PORTAL(28);

    private final int stride;

    VertexFormat(int stride) {
        this.stride = stride;
    }

    public int stride() {
        return stride;
    }
}

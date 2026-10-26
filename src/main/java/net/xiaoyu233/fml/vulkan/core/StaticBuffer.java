package net.xiaoyu233.fml.vulkan.core;

/**
 * GPU-resident buffer created by {@code RenderCore.createStatic}: {@link #buffer()} + {@link #offset()} address the data
 * (it may be a sub-allocation of a larger VkBuffer). Module A owns this class and may restructure it freely as long as the
 * public accessors stay the same.
 */
public final class StaticBuffer {
    private final long buffer;
    private final long offset;
    private final int size;
    private final Runnable release;
    private boolean freed;

    StaticBuffer(long buffer, long offset, int size, Runnable release) {
        this.buffer = buffer;
        this.offset = offset;
        this.size = size;
        this.release = release;
    }

    /** VkBuffer handle. */
    public long buffer() {
        return buffer;
    }

    /** Byte offset of the data inside {@link #buffer()}. */
    public long offset() {
        return offset;
    }

    /** Size of the data in bytes. */
    public int size() {
        return size;
    }

    /** Releases the memory; deferred until every frame that could still reference it has completed. Idempotent. */
    public void free() {
        if (!freed) {
            freed = true;
            release.run();
        }
    }
}

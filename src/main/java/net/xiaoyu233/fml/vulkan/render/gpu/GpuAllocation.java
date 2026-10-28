package net.xiaoyu233.fml.vulkan.render.gpu;

/** A sub-range of a {@link GpuArena} page (5.6). {@code offset} and {@code size} are in bytes, whole multiples of the arena unit. */
public final class GpuAllocation {
    public final long buffer, offset, size;
    public final int page;
    /** Allocator block handle inside the page; -1 for an allocation that was not made by an arena. */
    final int block;
    /** Set by {@link GpuArena#free}; a second free is reported and ignored. */
    boolean freed;

    public GpuAllocation(long buffer, long offset, long size, int page) {
        this(buffer, offset, size, page, -1);
    }

    GpuAllocation(long buffer, long offset, long size, int page, int block) {
        this.buffer = buffer;
        this.offset = offset;
        this.size = size;
        this.page = page;
        this.block = block;
    }

    @Override
    public String toString() {
        return "GpuAllocation[page " + page + ", offset " + offset + ", size " + size + (freed ? ", freed" : "") + "]";
    }
}

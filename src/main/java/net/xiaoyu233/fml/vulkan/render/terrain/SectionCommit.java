package net.xiaoyu233.fml.vulkan.render.terrain;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import net.minecraft.TileEntity;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuAllocation;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuArena;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshResult;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/** Commit of finished {@link MeshResult}s on the render thread and the terrain {@code GpuArena} (2.2, 2.6). NR-09 subtask S4. */
public final class SectionCommit {
    /** One terrain quad = 4 vertices * 28 bytes. */
    static final int QUAD_BYTES = 112;
    static final long PAGE_BYTES = 64L << 20;

    /** Implemented by the WorldRenderer mixin (S7): merges the new tile entity renderer list into RenderGlobal's shared list. */
    public interface SectionTileAccess {
        /** Replaces tileEntityRenderers by {@code now} and applies the set difference (added/removed) to the shared tileEntities list. */
        void fml$mergeTileEntities(List<TileEntity> now);
    }

    private static GpuArena terrain;
    private static long uploadedBytesThisFrame;
    private static long commits;
    /** Render-thread scratch for {@link #commit}: fresh allocations per layer and quad counts (reset on each call). */
    private static final GpuAllocation[] FRESH_SCRATCH = new GpuAllocation[Section.LAYERS];
    private static final int[] COUNT_SCRATCH = new int[Section.LAYERS];

    private SectionCommit() {
    }

    static GpuArena arena() {
        GpuArena a = terrain;
        if (a == null) {
            a = new GpuArena("terrain", VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, PAGE_BYTES, QUAD_BYTES);
            terrain = a;
        }
        return a;
    }

    /** Resets the per-frame upload counter (called by the scheduler at the start of a frame). */
    static void beginFrame() {
        uploadedBytesThisFrame = 0L;
    }

    static long commitCount() {
        return commits;
    }

    static long uploadedBytesThisFrame() {
        return uploadedBytesThisFrame;
    }

    static GpuAllocation alloc(Section s, int layer) {
        return s.alloc[layer];
    }

    /**
     * Allocates/uploads the layers, swaps allocations (deferred free of old ones), applies WorldRenderer side effects of 2.3.
     * Returns false when the result was stale (it is released here, so callers must check {@code result.version() < section.version}
     * first and never retry a stale result) or when GPU memory was out of space: in the latter case nothing changed, the result is
     * NOT released, and the caller keeps the section in flight (SectionScheduler parks the result in PENDING and retries it each frame).
     */
    static boolean commit(Section section, MeshResult result) {
        if (result.version() < section.version) {
            result.release();
            return false;
        }
        GpuArena arena = arena();
        // render-thread only (commit is called from SectionScheduler on the render thread): reuse scratch arrays, cleared per call
        GpuAllocation[] fresh = FRESH_SCRATCH;
        int[] counts = COUNT_SCRATCH;
        Arrays.fill(fresh, null);
        Arrays.fill(counts, 0);
        for (int i = 0; i < Section.LAYERS; i++) {
            ByteBuffer buf = result.layer(i);
            int q = buf == null ? 0 : result.quads(i);
            if (q <= 0) {
                continue;
            }
            counts[i] = q;
            GpuAllocation a = arena.allocate((long) q * QUAD_BYTES);
            if (a == null) {
                for (int j = 0; j < i; j++) {
                    if (fresh[j] != null) {
                        arena.free(fresh[j]);
                    }
                }
                return false;
            }
            fresh[i] = a;
        }
        boolean any = false;
        for (int i = 0; i < Section.LAYERS; i++) {
            GpuAllocation old = section.alloc[i];
            if (fresh[i] != null) {
                ByteBuffer buf = result.layer(i);
                arena.upload(fresh[i], buf);
                uploadedBytesThisFrame += buf.remaining();
                any = true;
            }
            if (old != null) {
                arena.free(old);
            }
            section.alloc[i] = fresh[i];
            section.quads[i] = counts[i];
        }

        // translucent CPU copy for re-sorting
        ByteBuffer tv = result.layer(Section.TRANSLUCENT);
        if (counts[Section.TRANSLUCENT] > 0 && tv != null) {
            int n = tv.remaining();
            ByteBuffer copy = section.translucentVerts;
            if (copy == null || copy.capacity() < n) {
                if (copy != null) {
                    MemoryUtil.memFree(copy);
                }
                copy = MemoryUtil.memAlloc(n);
            }
            copy.clear();
            copy.put(tv.duplicate());
            copy.flip();
            section.translucentVerts = copy;
            float[] c = result.translucentCentroids();
            section.translucentCentroids = c == null ? null : c.clone();
        } else {
            freeTranslucentCopy(section);
        }
        section.lastSortX = Double.NaN;
        section.lastSortY = Double.NaN;
        section.lastSortZ = Double.NaN;
        section.resortPending = false;

        commits++;
        section.builtVersion = result.version();
        section.visibility = result.visibility();
        section.state = any ? Section.READY : Section.EMPTY;

        WorldRenderer r = section.owner;
        boolean[] skip = result.skipRenderPass();
        for (int i = 0; i < r.skipRenderPass.length; i++) {
            r.skipRenderPass[i] = skip == null || i >= skip.length || skip[i];
        }
        r.isChunkLit = result.chunkLit();
        r.isInitialized = true;
        WorldRenderer.chunksUpdated++;
        if (result.version() == section.version) {
            r.needsUpdate = false;
        }
        if ((Object) r instanceof SectionTileAccess access) {
            access.fml$mergeTileEntities(result.tileEntities());
        }
        result.release();
        return true;
    }

    /**
     * Re-uploads re-sorted translucent quads (same quad count) into a fresh allocation, swaps it in and frees the old block
     * through the deferred free (2.7), so frames in flight keep reading the old data. Returns false, changing nothing, when the
     * section has no translucent block, the size does not match, or the arena has no room (the caller retries later).
     */
    static boolean commitResorted(Section s, ByteBuffer sorted) {
        GpuAllocation old = s.alloc[Section.TRANSLUCENT];
        if (old == null || sorted == null || sorted.remaining() != (long) s.quads[Section.TRANSLUCENT] * QUAD_BYTES) {
            return false;
        }
        GpuArena arena = arena();
        GpuAllocation fresh = arena.allocate(sorted.remaining());
        if (fresh == null) {
            return false;
        }
        arena.upload(fresh, sorted);
        uploadedBytesThisFrame += sorted.remaining();
        s.alloc[Section.TRANSLUCENT] = fresh;
        arena.free(old);
        return true;
    }

    private static void freeTranslucentCopy(Section s) {
        if (s.translucentVerts != null) {
            MemoryUtil.memFree(s.translucentVerts);
            s.translucentVerts = null;
        }
        s.translucentCentroids = null;
    }

    /** Frees every allocation of the section (deferred) and resets it to EMPTY. */
    static void release(Section section) {
        GpuArena arena = terrain;
        for (int i = 0; i < Section.LAYERS; i++) {
            GpuAllocation a = section.alloc[i];
            if (a != null && arena != null) {
                arena.free(a);
            }
            section.alloc[i] = null;
            section.quads[i] = 0;
        }
        freeTranslucentCopy(section);
        section.state = Section.EMPTY;
        section.visibility = Section.FULLY_CONNECTED;
        section.builtVersion = -1;
    }

    static void shutdown() {
        if (terrain != null) {
            terrain.destroy();
            terrain = null;
        }
        uploadedBytesThisFrame = 0L;
    }

    static long bytesUsed() {
        return terrain == null ? 0L : terrain.bytesUsed();
    }
}

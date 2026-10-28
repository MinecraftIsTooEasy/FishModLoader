package net.xiaoyu233.fml.vulkan.render.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.TileEntity;
import net.minecraft.WorldRenderer;

/** Result of a {@link MeshTask} (2.2): up to three TERRAIN-format layers (28 B/vertex): 0 SOLID, 1 CUTOUT, 2 TRANSLUCENT. */
public final class MeshResult {
    public static final int BYTES_PER_VERTEX = 28;
    private static final int MIN_SHIFT = 12, MAX_SHIFT = 28;
    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<ByteBuffer>[] POOLS = new ConcurrentLinkedQueue[MAX_SHIFT + 1];
    private static final int POOL_LIMIT_PER_CLASS = 64;

    static {
        for (int i = 0; i < POOLS.length; i++) {
            POOLS[i] = new ConcurrentLinkedQueue<>();
        }
    }

    private final WorldRenderer target;
    private final int version;
    private volatile int epoch;
    private final ByteBuffer[] layers = new ByteBuffer[3];
    private final int[] quads = new int[3];
    private long visibility;
    private final boolean[] skipRenderPass = new boolean[2];
    private boolean chunkLit;
    private List<TileEntity> tileEntities = List.of();
    private float[] translucentCentroids;
    private boolean released;

    private MeshResult(WorldRenderer target, int version, int epoch) {
        this.target = target;
        this.version = version;
        this.epoch = epoch;
    }

    /** Package-internal: used by SectionMeshBuilder; layers 0 SOLID, 1 CUTOUT, 2 TRANSLUCENT. */
    static MeshResult create(WorldRenderer target, int version, int epoch) {
        return new MeshResult(target, version, epoch);
    }

    /** Takes a pooled direct little-endian buffer with at least {@code bytes} capacity, cleared (limit = capacity). */
    static ByteBuffer acquire(int bytes) {
        int shift = Math.max(MIN_SHIFT, 32 - Integer.numberOfLeadingZeros(Math.max(1, bytes) - 1));
        if (shift > MAX_SHIFT) {
            throw new IllegalArgumentException("layer too large: " + bytes);
        }
        ByteBuffer b = POOLS[shift].poll();
        if (b == null) {
            b = ByteBuffer.allocateDirect(1 << shift).order(ByteOrder.LITTLE_ENDIAN);
        }
        b.clear();
        return b;
    }

    private static void recycle(ByteBuffer b) {
        if (b == null) {
            return;
        }
        int cap = b.capacity();
        int shift = 31 - Integer.numberOfLeadingZeros(cap);
        if (shift < MIN_SHIFT || shift > MAX_SHIFT || (1 << shift) != cap) {
            return;
        }
        ConcurrentLinkedQueue<ByteBuffer> q = POOLS[shift];
        if (q.size() < POOL_LIMIT_PER_CLASS) {
            q.add(b);
        }
    }

    /** Sets a layer: {@code buf} comes from {@link #acquire}, position 0, limit = quads * 4 * 28. */
    synchronized void setLayer(int layer, ByteBuffer buf, int quadCount) {
        if (released) {
            recycle(buf);
            return;
        }
        if (layers[layer] != null) {
            recycle(layers[layer]);
        }
        layers[layer] = buf;
        quads[layer] = quadCount;
    }

    /** Fills everything at once (builder); buffers may be null/empty, they are used as given (pooled or not). */
    synchronized void fill(ByteBuffer[] out, int[] counts, long vis, boolean[] skip, boolean lit, List<TileEntity> tes, float[] centroids) {
        if (released) {
            for (ByteBuffer b : out) {
                recycle(b);
            }
            return;
        }
        for (int i = 0; i < 3; i++) {
            layers[i] = out[i];
            quads[i] = counts[i];
        }
        visibility = vis;
        skipRenderPass[0] = skip[0];
        skipRenderPass[1] = skip[1];
        chunkLit = lit;
        tileEntities = tes == null ? List.of() : tes;
        translucentCentroids = centroids;
    }

    void setVisibility(long v) {
        visibility = v;
    }

    void setSkipRenderPass(int pass, boolean v) {
        skipRenderPass[pass] = v;
    }

    void setChunkLit(boolean v) {
        chunkLit = v;
    }

    void setTileEntities(List<TileEntity> l) {
        tileEntities = l == null ? List.of() : l;
    }

    void setTranslucentCentroids(float[] c) {
        translucentCentroids = c;
    }

    void setEpoch(int e) {
        epoch = e;
    }

    public int epoch() {
        return epoch;
    }

    public WorldRenderer target() {
        return target;
    }

    public int version() {
        return version;
    }

    /** The layer's bytes (position 0, limit = used bytes) or null when empty. */
    public ByteBuffer layer(int layer) {
        return layers[layer];
    }

    public int quads(int layer) {
        return quads[layer];
    }

    public long visibility() {
        return visibility;
    }

    public boolean[] skipRenderPass() {
        return skipRenderPass;
    }

    public boolean chunkLit() {
        return chunkLit;
    }

    public List<TileEntity> tileEntities() {
        return tileEntities;
    }

    public float[] translucentCentroids() {
        return translucentCentroids;
    }

    /** Returns the pooled buffers; the layers must not be used afterwards. Idempotent. */
    public synchronized void release() {
        if (released) {
            return;
        }
        released = true;
        for (int i = 0; i < 3; i++) {
            if (layers[i] != null) {
                recycle(layers[i]);
                layers[i] = null;
                quads[i] = 0;
            }
        }
    }
}

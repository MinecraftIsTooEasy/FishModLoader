package net.xiaoyu233.fml.vulkan.render.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.Block;
import net.minecraft.Minecraft;
import net.minecraft.RenderBlocks;
import net.minecraft.TextureMap;
import net.minecraft.TileEntity;
import net.minecraft.Tessellator;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.render.tess.MeshSink;
import net.xiaoyu233.fml.vulkan.render.tess.MeshWorkerThread;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;
import net.xiaoyu233.fml.vulkan.render.tess.TessTarget;
import net.xiaoyu233.fml.vulkan.render.texture.AtlasInfo;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;

/** Runs RenderBlocks over a snapshot and converts MITE 32-byte quads to TERRAIN layers (2.3, 2.4). Owner NR-10 (subtask builder). */
final class SectionMeshBuilder {
    static final int SOLID = 0, CUTOUT = 1, TRANSLUCENT = 2;
    private static final int VERTEX_BYTES = 28;
    private static final int QUAD_BYTES = 4 * VERTEX_BYTES;

    /** GL texture id of the blocks atlas once resolved (-1 = unknown). */
    private static volatile int blocksAtlasId = -1;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private SectionMeshBuilder() {
    }

    /** Per-thread reusable layer buffers (grown geometrically) and the sink that fills them. */
    private static final class Scratch implements MeshSink {
        final ByteBuffer[] buf = new ByteBuffer[3];
        final int[] quads = new int[3];
        AtlasInfo atlas;
        int pass;

        Scratch() {
            for (int i = 0; i < 3; i++) {
                buf[i] = alloc(QUAD_BYTES * 256);
            }
        }

        void reset(AtlasInfo atlas) {
            this.atlas = atlas;
            quads[0] = quads[1] = quads[2] = 0;
            for (ByteBuffer b : buf) {
                b.clear();
            }
        }

        private static ByteBuffer alloc(int n) {
            return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        }

        private ByteBuffer ensure(int layer, int extra) {
            ByteBuffer b = buf[layer];
            if (b.remaining() < extra) {
                ByteBuffer n = alloc(Math.max(b.capacity() * 2, b.position() + extra));
                b.flip();
                n.put(b);
                buf[layer] = n;
                b = n;
            }
            return b;
        }

        @Override
        public void quads(int[] raw, int intCount, int vertexCount) {
            for (int q = 0; q < vertexCount; q += 4) {
                int base = q * 8;
                int layer;
                if (pass == 1) {
                    layer = TRANSLUCENT;
                } else {
                    float u = (Float.intBitsToFloat(raw[base + 3]) + Float.intBitsToFloat(raw[base + 11])
                            + Float.intBitsToFloat(raw[base + 19]) + Float.intBitsToFloat(raw[base + 27])) * 0.25F;
                    float v = (Float.intBitsToFloat(raw[base + 4]) + Float.intBitsToFloat(raw[base + 12])
                            + Float.intBitsToFloat(raw[base + 20]) + Float.intBitsToFloat(raw[base + 28])) * 0.25F;
                    AtlasInfo a = atlas;
                    layer = a != null && a.isOpaque(u, v) ? SOLID : CUTOUT;
                }
                ByteBuffer b = ensure(layer, QUAD_BYTES);
                for (int k = 0; k < 4; k++) {
                    int i = base + k * 8;
                    b.putInt(raw[i]).putInt(raw[i + 1]).putInt(raw[i + 2]);
                    b.putInt(raw[i + 5] | 0xFF000000);
                    b.putInt(raw[i + 3]).putInt(raw[i + 4]);
                    b.putInt(raw[i + 7]);
                }
                quads[layer]++;
            }
        }
    }

    /** True when the sprite under (u, v) of the blocks atlas is fully opaque; false (CUTOUT) while the atlas is not registered. */
    static boolean isOpaqueUv(float u, float v) {
        AtlasInfo a = blocksAtlas();
        return a != null && a.isOpaque(u, v);
    }

    private static AtlasInfo blocksAtlas() {
        int id = blocksAtlasId;
        if (id < 0) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.getTextureManager() != null) {
                    id = mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture).getGlTextureId();
                    blocksAtlasId = id;
                }
            } catch (Throwable t) {
                return null;
            }
        }
        return id < 0 ? null : Textures.atlas(id);
    }

    /** Forgets the cached atlas id (resource reload). */
    static void invalidateAtlas() {
        blocksAtlasId = -1;
    }

    static MeshResult build(MeshTask task, NativeTess tess, List<int[]> unusedReserved) {
        return build(task, tess, null, 0);
    }

    /**
     * {@code prevTranslucent}/{@code prevQuads}: the previous TERRAIN translucent layer, used only when {@code task.resortOnly()}.
     */
    static MeshResult build(MeshTask task, NativeTess tess, ByteBuffer prevTranslucent, int prevQuads) {
        final SectionSnapshot snap = task.snapshot();
        final WorldRenderer wr = task.target();
        final int posX = wr.posXMinus + wr.posXClip;
        final int posY = wr.posYMinus + wr.posYClip;
        final int posZ = wr.posZMinus + wr.posZClip;
        final MeshResult res = MeshResult.create(wr, task.version(), snap.epoch());
        // Copy: release() clears the snapshot-owned list and pools the snapshot, and the result outlives this task.
        final List<TileEntity> tes = snap.specialTileEntities().isEmpty() ? List.of() : List.copyOf(snap.specialTileEntities());
        final boolean[] skip = {true, true};
        final ByteBuffer[] out = new ByteBuffer[3];
        final int[] counts = new int[3];

        if (task.resortOnly()) {
            if (prevTranslucent != null && prevQuads > 0) {
                ByteBuffer copy = copyOf(prevTranslucent, prevQuads * QUAD_BYTES);
                out[TRANSLUCENT] = copy;
                counts[TRANSLUCENT] = prevQuads;
                skip[1] = false;
                float[] cent = TranslucentSorter.sort(copy, prevQuads, (float) (task.camX() - posX), (float) (task.camY() - posY),
                        (float) (task.camZ() - posZ));
                res.fill(out, counts, 0L, skip, false, tes, cent);
                return res;
            }
            // No previous translucent data: returning null (no result) keeps the section's layers; an empty result would erase them on commit.
            res.release();
            return null;
        }

        if (snap.extendedLevelsInChunkCache()) {
            res.fill(out, counts, 0L, skip, false, tes, null);
            return res;
        }

        final Scratch s = SCRATCH.get();
        s.reset(blocksAtlas());
        final RenderBlocks rb = new RenderBlocks(snap);
        // RenderBlocks writes through Tessellator.instance, which forwards to TessBridge.current(). The instance that is configured
        // here must be that same one, so the passed tess is not used: a worker uses its own instance, every other thread the render one.
        if (Thread.currentThread() instanceof MeshWorkerThread) {
            meshBlocks(TessBridge.current(), s, rb, snap, posX, posY, posZ, skip);
        } else {
            meshOnRenderThread(TessBridge.renderThread(), s, rb, snap, posX, posY, posZ, skip);
        }

        for (int l = 0; l < 3; l++) {
            counts[l] = s.quads[l];
            if (counts[l] > 0) {
                out[l] = copyOf(s.buf[l], counts[l] * QUAD_BYTES);
            }
        }
        float[] cent = null;
        if (counts[TRANSLUCENT] > 0) {
            cent = TranslucentSorter.sort(out[TRANSLUCENT], counts[TRANSLUCENT], (float) (task.camX() - posX), (float) (task.camY() - posY),
                    (float) (task.camZ() - posZ));
        }
        res.fill(out, counts, VisGraph.compute(snap), skip, snap.sawSkyLight(), tes, cent);
        return res;
    }

    /**
     * Render-thread path: borrows the render-thread instance for the build and puts its state back afterwards (target, sink,
     * translation, public Tessellator mirror), so a build never leaves a half-drawn or stale render-thread state behind.
     */
    private static void meshOnRenderThread(NativeTess rt, Scratch s, RenderBlocks rb, SectionSnapshot snap, int posX, int posY, int posZ,
            boolean[] skip) {
        if (rt.isDrawing()) {
            throw new IllegalStateException("render-thread NativeTess is mid-draw; the section cannot be meshed now");
        }
        final TessTarget oldTarget = rt.target();
        final MeshSink oldSink = rt.meshSink();
        final double ox = rt.xOffset(), oy = rt.yOffset(), oz = rt.zOffset();
        try {
            meshBlocks(rt, s, rb, snap, posX, posY, posZ, skip);
        } finally {
            if (rt.isDrawing()) {
                rt.draw();
            }
            rt.reset();
            rt.setTranslation(ox, oy, oz);
            rt.setTarget(oldTarget);
            rt.setMeshSink(oldSink);
            TessBridge.syncPublicFields(Tessellator.instance);
        }
    }

    /** Runs the block passes of the section through {@code t}, which must be the instance {@link TessBridge#current()} returns. */
    private static void meshBlocks(NativeTess t, Scratch s, RenderBlocks rb, SectionSnapshot snap, int posX, int posY, int posZ,
            boolean[] skip) {
        t.setTarget(TessTarget.MESH);
        t.setMeshSink(s);
        final int x1 = posX + 15, y1 = posY + 15, z1 = posZ + 15;
        for (int pass = 0; pass < 2; pass++) {
            boolean otherPass = false;
            boolean rendered = false;
            boolean started = false;
            s.pass = pass;
            for (int y = posY; y <= y1; y++) {
                for (int z = posZ; z <= z1; z++) {
                    for (int x = posX; x <= x1; x++) {
                        int id = snap.getBlockId(x, y, z);
                        if (id > 0) {
                            if (!started) {
                                started = true;
                                t.startDrawingQuads();
                                t.setTranslation(-posX, -posY, -posZ);
                            }
                            Block block = Block.blocksList[id];
                            if (block != null) {
                                int bp = block.getRenderBlockPass();
                                if (bp != pass) {
                                    otherPass = true;
                                } else {
                                    rendered |= rb.renderBlockByRenderType(block, x, y, z);
                                }
                            }
                        }
                    }
                }
            }
            if (started) {
                t.draw();
                t.setTranslation(0.0, 0.0, 0.0);
            } else {
                rendered = false;
            }
            if (rendered) {
                skip[pass] = false;
            }
            if (!otherPass) {
                break;
            }
        }
    }

    /** Copies the first {@code bytes} of {@code src} into a pooled result buffer (position 0, limit = bytes). */
    private static ByteBuffer copyOf(ByteBuffer src, int bytes) {
        ByteBuffer d = MeshResult.acquire(bytes);
        ByteBuffer s = src.duplicate();
        s.position(0).limit(bytes);
        d.put(s);
        d.flip();
        return d;
    }
}

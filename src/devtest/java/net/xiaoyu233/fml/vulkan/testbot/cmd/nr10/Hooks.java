package net.xiaoyu233.fml.vulkan.testbot.cmd.nr10;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.Block;
import net.minecraft.Chunk;
import net.minecraft.ChunkCache;
import net.minecraft.Minecraft;
import net.minecraft.RenderBlocks;
import net.minecraft.TextureMap;
import net.minecraft.TileEntity;
import net.minecraft.TileEntityRenderer;
import net.minecraft.Tessellator;
import net.minecraft.World;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshResult;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshTask;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshWorkers;
import net.xiaoyu233.fml.vulkan.render.mesh.SectionSnapshot;
import net.xiaoyu233.fml.vulkan.render.mesh.TranslucentSorter;
import net.xiaoyu233.fml.vulkan.render.tess.MeshSink;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;
import net.xiaoyu233.fml.vulkan.render.tess.TessTarget;
import net.xiaoyu233.fml.vulkan.render.texture.AtlasInfo;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;

/**
 * Small public test hooks of NR-10 (devtest tree only): section selection, package-private mesh builder / vis graph access through
 * reflection, the worker round trip, and MITE's own WorldRenderer.updateRenderer path over a ChunkCache ({@link #buildMite}).
 */
public final class Hooks {
    /** One section of the live world with its renderer. */
    public record Sec(WorldRenderer renderer, int x, int y, int z) {
    }

    /**
     * MITE's mesh of one section: the quads of WorldRenderer.updateRenderer converted to the TERRAIN layers exactly as the worker path does,
     * the special tile entities in MITE's order, and the translucent centroids. {@code layers[l]} is null when empty, as in {@link MeshResult}.
     */
    public record MiteMesh(ByteBuffer[] layers, int[] counts, boolean[] skipRenderPass, List<TileEntity> tileEntities,
            float[] translucentCentroids) {
    }

    private static final String MESH_PKG = "net.xiaoyu233.fml.vulkan.render.mesh.";

    private Hooks() {
    }

    public static TestBot bot() {
        TestBot b = TestBot.get();
        if (b == null) {
            throw new IllegalStateException("testbot not running");
        }
        return b;
    }

    /** Client thread: up to {@code count} distinct sections (random, seeded) of the loaded renderers whose centre column is loaded. */
    public static List<Sec> pickSections(Minecraft mc, int count, long seed) {
        List<Sec> all = new ArrayList<>();
        WorldRenderer[] renderers = mc.renderGlobal == null ? null : mc.renderGlobal.worldRenderers;
        World w = mc.theWorld;
        if (renderers == null || w == null) {
            return all;
        }
        for (WorldRenderer r : renderers) {
            if (r == null) {
                continue;
            }
            int x = r.posXMinus + r.posXClip;
            int y = r.posYClip;
            int z = r.posZMinus + r.posZClip;
            if (w.blockExists(x + 8, 64, z + 8)) {
                all.add(new Sec(r, x, y, z));
            }
        }
        Collections.shuffle(all, new Random(seed));
        return all.size() > count ? new ArrayList<>(all.subList(0, count)) : all;
    }

    /** Client thread: the snapshot of {@code s}; {@code skylight >= 0} temporarily sets {@code World.skylightSubtracted} around the capture. */
    public static SectionSnapshot capture(Minecraft mc, Sec s, int skylight) {
        World w = mc.theWorld;
        int old = w.skylightSubtracted;
        try {
            if (skylight >= 0) {
                w.skylightSubtracted = skylight;
            }
            return SectionSnapshot.capture(w, s.x(), s.y(), s.z());
        } finally {
            w.skylightSubtracted = old;
        }
    }

    public static MeshTask task(Sec s, int version, SectionSnapshot snap) {
        return new MeshTask(s.renderer(), version, snap, 0, 0, 0, false);
    }

    /** Builds on the calling thread (call from the client thread) through the package-private {@code SectionMeshBuilder.build}. */
    public static MeshResult buildOnCallingThread(MeshTask task) throws Exception {
        Class<?> c = Class.forName(MESH_PKG + "SectionMeshBuilder");
        Method m = c.getDeclaredMethod("build", MeshTask.class, NativeTess.class, List.class);
        m.setAccessible(true);
        // The builder drives the instance that TessBridge.current() returns for this thread: the render-thread instance here.
        NativeTess tess = TessBridge.renderThread();
        try {
            return (MeshResult) m.invoke(null, task, tess, new ArrayList<int[]>());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(t);
        }
    }

    /**
     * MITE's original path (client thread): the loop of WorldRenderer.updateRenderer over a {@link ChunkCache} with border 1, rendering through
     * RenderBlocks and the render-thread Tessellator in MESH mode (the raw MITE quads, no snapshot involved). {@code skylight >= 0} sets
     * {@code World.skylightSubtracted} for the build, as {@link #capture} does for the snapshot.
     */
    public static MiteMesh buildMite(Minecraft mc, Sec s, int skylight) {
        World w = mc.theWorld;
        int oldSky = w.skylightSubtracted;
        NativeTess rt = TessBridge.renderThread();
        if (rt.isDrawing()) {
            throw new IllegalStateException("render-thread tessellator is mid-draw; the MITE reference cannot be built now");
        }
        TessTarget oldTarget = rt.target();
        MeshSink oldSink = rt.meshSink();
        double ox = rt.xOffset(), oy = rt.yOffset(), oz = rt.zOffset();
        MiteSink sink = new MiteSink(blocksAtlas(mc));
        List<TileEntity> tes = new ArrayList<>();
        boolean[] skip = {true, true};
        try {
            if (skylight >= 0) {
                w.skylightSubtracted = skylight;
            }
            int px = s.x(), py = s.y(), pz = s.z();
            Chunk chunk = w.getChunkFromBlockCoords(px, pz);
            // WorldRenderer.updateRenderer: ChunkCache over the 18^3 box with border 1
            ChunkCache cc = new ChunkCache(w, px - 1, py - 1, pz - 1, px + 16, py + 16, pz + 16, 1);
            if (chunk != null && !cc.extendedLevelsInChunkCache()) {
                RenderBlocks rb = new RenderBlocks(cc);
                rt.setTarget(TessTarget.MESH);
                rt.setMeshSink(sink);
                for (int pass = 0; pass < 2; pass++) {
                    boolean otherPass = false;
                    boolean rendered = false;
                    boolean started = false;
                    sink.pass = pass;
                    for (int y = py; y <= py + 15; y++) {
                        for (int z = pz; z <= pz + 15; z++) {
                            for (int x = px; x <= px + 15; x++) {
                                int id = chunk.getBlockID(x & 15, y, z & 15);
                                if (id <= 0) {
                                    continue;
                                }
                                if (!started) {
                                    started = true;
                                    rt.startDrawingQuads();
                                    rt.setTranslation(-px, -py, -pz);
                                }
                                Block block = Block.blocksList[id];
                                if (block == null) {
                                    continue;
                                }
                                if (pass == 0 && block.hasTileEntity()) {
                                    TileEntity te = cc.getBlockTileEntity(x, y, z);
                                    if (TileEntityRenderer.instance.hasSpecialRenderer(te)) {
                                        tes.add(te);
                                    }
                                }
                                if (block.getRenderBlockPass() != pass) {
                                    otherPass = true;
                                } else {
                                    rendered |= rb.renderBlockByRenderType(block, x, y, z);
                                }
                            }
                        }
                    }
                    if (started) {
                        rt.draw();
                        rt.setTranslation(0.0, 0.0, 0.0);
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
        } finally {
            w.skylightSubtracted = oldSky;
            if (rt.isDrawing()) {
                rt.draw();
            }
            rt.reset();
            rt.setTranslation(ox, oy, oz);
            rt.setTarget(oldTarget);
            rt.setMeshSink(oldSink);
            TessBridge.syncPublicFields(Tessellator.instance);
        }
        ByteBuffer[] layers = new ByteBuffer[3];
        int[] counts = new int[3];
        for (int l = 0; l < 3; l++) {
            counts[l] = sink.layer[l].quads;
            if (counts[l] > 0) {
                layers[l] = sink.layer[l].bytes();
            }
        }
        float[] cent = null;
        if (counts[2] > 0) {
            cent = TranslucentSorter.sort(layers[2], counts[2], (float) (0.0 - s.x()), (float) (0.0 - s.y()), (float) (0.0 - s.z()));
        }
        return new MiteMesh(layers, counts, skip, tes.isEmpty() ? List.of() : List.copyOf(tes), cent);
    }

    /** {@code VisGraph.compute(SectionSnapshot)} (package-private). */
    public static long visOf(SectionSnapshot snap) throws Exception {
        Class<?> c = Class.forName(MESH_PKG + "VisGraph");
        Method m = c.getDeclaredMethod("compute", SectionSnapshot.class);
        m.setAccessible(true);
        try {
            return (Long) m.invoke(null, snap);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(t);
        }
    }

    /** Submits the tasks and polls until all results arrived (by version) or the timeout expired; results of other versions are released. */
    public static Map<Integer, MeshResult> runOnWorkers(List<MeshTask> tasks, long timeoutMs) throws Exception {
        Map<Integer, MeshResult> out = new HashMap<>();
        Set<Integer> wanted = new HashSet<>();
        for (MeshTask t : tasks) {
            wanted.add(t.version());
            MeshWorkers.submit(t, 0);
        }
        long end = System.currentTimeMillis() + timeoutMs;
        while (out.size() < tasks.size() && System.currentTimeMillis() < end) {
            MeshResult r = MeshWorkers.pollResult();
            if (r == null) {
                Thread.sleep(2);
                continue;
            }
            if (wanted.contains(r.version())) {
                out.put(r.version(), r);
            } else {
                r.release();
            }
        }
        return out;
    }

    /** Byte equality of two results; returns null when equal, else the first difference. */
    public static String diff(MeshResult a, MeshResult b) {
        for (int l = 0; l < 3; l++) {
            String d = diffLayer(l, a.quads(l), a.layer(l), b.quads(l), b.layer(l));
            if (d != null) {
                return d;
            }
        }
        if (a.visibility() != b.visibility()) {
            return "visibility " + Long.toHexString(a.visibility()) + " vs " + Long.toHexString(b.visibility());
        }
        if (a.chunkLit() != b.chunkLit()) {
            return "chunkLit";
        }
        if (!Arrays.equals(a.skipRenderPass(), b.skipRenderPass())) {
            return "skipRenderPass";
        }
        String t = diffTileEntities(a.tileEntities(), b.tileEntities());
        if (t != null) {
            return t;
        }
        if (!Arrays.equals(a.translucentCentroids(), b.translucentCentroids())) {
            return "translucentCentroids";
        }
        return null;
    }

    /**
     * Worker result against MITE's own ChunkCache mesh: layers, skipRenderPass, tile entities and translucent centroids. Visibility and
     * chunkLit have no MITE counterpart (MITE keeps no visibility graph, and Chunk.isLit is a static MITE never reads), so they are not compared.
     */
    public static String diffMite(MeshResult w, MiteMesh m) {
        for (int l = 0; l < 3; l++) {
            String d = diffLayer(l, w.quads(l), w.layer(l), m.counts()[l], m.layers()[l]);
            if (d != null) {
                return "vs MITE " + d;
            }
        }
        if (!Arrays.equals(w.skipRenderPass(), m.skipRenderPass())) {
            return "vs MITE skipRenderPass";
        }
        String t = diffTileEntities(w.tileEntities(), m.tileEntities());
        if (t != null) {
            return "vs MITE " + t;
        }
        if (!Arrays.equals(w.translucentCentroids(), m.translucentCentroids())) {
            return "vs MITE translucentCentroids";
        }
        return null;
    }

    private static String diffLayer(int l, int qa, ByteBuffer x, int qb, ByteBuffer y) {
        if (qa != qb) {
            return "layer " + l + " quads " + qa + " vs " + qb;
        }
        if (x == null || y == null) {
            return x == y ? null : "layer " + l + " present in only one";
        }
        if (!x.duplicate().equals(y.duplicate())) {
            return "layer " + l + " bytes differ (" + x.remaining() + " vs " + y.remaining() + " bytes)";
        }
        return null;
    }

    /** Same size, same objects (identity), same order. */
    private static String diffTileEntities(List<TileEntity> a, List<TileEntity> b) {
        if (a.size() != b.size()) {
            return "tileEntities " + a.size() + " vs " + b.size();
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return "tileEntities differ at index " + i + " (" + a.get(i) + " vs " + b.get(i) + ")";
            }
        }
        return null;
    }

    /** The blocks atlas as the builder classifies quads with it; null while it is not registered (every quad is CUTOUT then). */
    private static AtlasInfo blocksAtlas(Minecraft mc) {
        try {
            int id = mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture).getGlTextureId();
            return id < 0 ? null : Textures.atlas(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when a thread of the mesh pipeline is still alive. */
    public static List<String> liveWorkerThreads() {
        List<String> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t instanceof net.xiaoyu233.fml.vulkan.render.tess.MeshWorkerThread && t.isAlive()) {
                out.add(t.getName());
            }
        }
        return out;
    }

    /** Starts the workers unless a failure says they run already. */
    public static void ensureWorkers(int n) {
        try {
            MeshWorkers.start(n);
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * Sink of the MITE path: classifies the raw quads into TERRAIN layers exactly as the builder does (pass 1 = TRANSLUCENT, else SOLID when
     * the sprite is opaque, CUTOUT otherwise) and writes the 28-byte vertices in native order, as the worker's buffers are.
     */
    private static final class MiteSink implements MeshSink {
        private static final int SOLID = 0, CUTOUT = 1, TRANSLUCENT = 2;
        private static final int QUAD_BYTES = 4 * 28;

        /** A growing layer buffer with its quad count. */
        static final class Layer {
            ByteBuffer buf = ByteBuffer.allocate(QUAD_BYTES * 64).order(ByteOrder.nativeOrder());
            int quads;

            void room(int n) {
                if (buf.remaining() < n) {
                    ByteBuffer nb = ByteBuffer.allocate(Math.max(buf.capacity() * 2, buf.position() + n)).order(ByteOrder.nativeOrder());
                    buf.flip();
                    nb.put(buf);
                    buf = nb;
                }
            }

            ByteBuffer bytes() {
                ByteBuffer d = buf.duplicate();
                d.flip();
                return d;
            }
        }

        final Layer[] layer = {new Layer(), new Layer(), new Layer()};
        final AtlasInfo atlas;
        int pass;

        MiteSink(AtlasInfo atlas) {
            this.atlas = atlas;
        }

        @Override
        public void quads(int[] raw, int intCount, int vertexCount) {
            for (int q = 0; q < vertexCount; q += 4) {
                int base = q * 8;
                int l;
                if (pass == 1) {
                    l = TRANSLUCENT;
                } else {
                    float u = (Float.intBitsToFloat(raw[base + 3]) + Float.intBitsToFloat(raw[base + 11])
                            + Float.intBitsToFloat(raw[base + 19]) + Float.intBitsToFloat(raw[base + 27])) * 0.25F;
                    float v = (Float.intBitsToFloat(raw[base + 4]) + Float.intBitsToFloat(raw[base + 12])
                            + Float.intBitsToFloat(raw[base + 20]) + Float.intBitsToFloat(raw[base + 28])) * 0.25F;
                    l = atlas != null && atlas.isOpaque(u, v) ? SOLID : CUTOUT;
                }
                Layer out = layer[l];
                out.room(QUAD_BYTES);
                for (int k = 0; k < 4; k++) {
                    int i = base + k * 8;
                    out.buf.putInt(raw[i]).putInt(raw[i + 1]).putInt(raw[i + 2]);
                    out.buf.putInt(raw[i + 5] | 0xFF000000);
                    out.buf.putInt(raw[i + 3]).putInt(raw[i + 4]);
                    out.buf.putInt(raw[i + 7]);
                }
                out.quads++;
            }
        }
    }
}

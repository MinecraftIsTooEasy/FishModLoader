package net.xiaoyu233.fml.vulkan.render.terrain;

import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StreamSlice;
import net.xiaoyu233.fml.vulkan.render.buffer.QuadIndexBuffers;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.render.gpu.CommandStream;
import net.xiaoyu233.fml.vulkan.render.gpu.DeviceFeatures;
import net.xiaoyu233.fml.vulkan.render.gpu.GpuAllocation;
import net.xiaoyu233.fml.vulkan.render.gpu.PassUniforms;
import net.xiaoyu233.fml.vulkan.render.gpu.PipelineLibrary;
import net.xiaoyu233.fml.vulkan.render.gpu.Program;
import net.xiaoyu233.fml.vulkan.render.legacy.LegacyInterop;
import net.xiaoyu233.fml.vulkan.render.state.AlphaRefs;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.MaterialKey;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import org.lwjgl.system.MemoryUtil;

/** Draw side (2.6, 2.7): per-frame instance offsets, MDI records, SOLID/CUTOUT passes, translucent order and modes. Render thread. NR-09 subtask S4. */
public final class TerrainDraw {
    private static final int VK_COMPARE_LEQUAL = 3, VK_CULL_NONE = 0, VK_CULL_BACK = 2, VK_INDEX_U32 = 1;
    private static final int CMD_BYTES = 20;
    private static final int DEPTH_LEQUAL = 3;   // DepthFunc.LEQUAL ordinal

    private static long[] pages = new long[16];
    private static int draws, encoded;
    private static long texSet, lightSet;

    private TerrainDraw() {
    }

    /** SOLID then CUTOUT over the BFS visible list. */
    static void drawOpaque() {
        int n = VisibilityBfs.visibleCount();
        if (n <= 0) {
            return;
        }
        int any = 0;
        for (int i = 0; i < n && any == 0; i++) {
            Section s = VisibilityBfs.visible(i);
            if (has(s, Section.SOLID) || has(s, Section.CUTOUT)) {
                any = 1;
            }
        }
        if (any == 0 || !beginPass()) {
            return;
        }
        draws = 0;
        encoded = 0;
        long instBuf = writeInstances(n, false);
        if (instBuf == 0L) {
            return;
        }
        bindCommon();
        bindPipeline(false, false, true, false, false);
        drawLayer(Section.SOLID, n, false, false);
        bindPipeline(true, false, true, false, false);
        drawLayer(Section.CUTOUT, n, false, false);
        FrameStats.noteDraws(draws, encoded);
    }

    /** Returns the number of drawn translucent sections. */
    static int drawTranslucent() {
        double cx = TerrainRenderer.camX(), cy = TerrainRenderer.camY(), cz = TerrainRenderer.camZ();
        int n = TranslucentOrder.build(cx, cy, cz);
        if (n <= 0) {
            return 0;
        }
        boolean prepass = "prepass".equals(VulkanConfig.translucency());
        if (!prepass) {
            // sorted mode (2.7): schedule per-quad resorts for the nearest sections before the draw reads their allocations
            TranslucentOrder.scheduleResorts(cx, cy, cz);
        }
        if (!beginPass()) {
            return 0;
        }
        draws = 0;
        encoded = 0;
        if (writeInstances(n, true) == 0L) {
            return 0;
        }
        bindCommon();
        if (prepass) {
            boolean fancy = Minecraft.getMinecraft().gameSettings.isFancyGraphicsEnabled();
            if (fancy) {
                bindPipeline(true, true, false, true, true);     // depth only, colour mask off, alpha test 0.1 as MITE
                drawLayer(Section.TRANSLUCENT, n, true, true);
            }
            bindPipeline(true, true, false, true, false);
            drawLayer(Section.TRANSLUCENT, n, true, true);
        } else {
            bindPipeline(true, true, false, true, false);
            drawLayer(Section.TRANSLUCENT, n, true, false);
        }
        FrameStats.noteDraws(draws, encoded);
        return n;
    }

    static void shutdown() {
        draws = 0;
        encoded = 0;
        texSet = 0L;
        lightSet = 0L;
    }

    // ---------------------------------------------------------------------------------------------------------

    private static boolean beginPass() {
        if (!RenderCore.isInitialized() || QuadIndexBuffers.u32Buffer() == 0L) {
            return false;
        }
        // The terrain is recorded straight into the command stream: an emulated draw still held back by the GL emulation (a mod sky
        // drawn from display lists, e.g. Twilight Forest's lower sky disc) must be recorded first, else it lands on top of the terrain.
        LegacyInterop.beforeRawCommands();
        PassUniforms.setProjection(Draw.projection().top());
        PassUniforms.setTerrainView(Draw.pose().top());
        PassUniforms.setTerrainParams(PassUniforms.SECTION_SCALE, 8.0F * (PassUniforms.SECTION_SCALE - 1.0F));
        int mode = !Draw.fogEnabled() ? 0 : switch (Draw.fogModeState()) {
            case LINEAR -> 1;
            case EXP -> 2;
            case EXP2 -> 3;
        };
        PassUniforms.setFog(Draw.fogColorComponent(0), Draw.fogColorComponent(1), Draw.fogColorComponent(2), Draw.fogColorComponent(3),
                Draw.fogStartValue(), Draw.fogEndValue(), Draw.fogDensityValue(), mode);
        texSet = Textures.descriptorSet(Draw.boundTexture(0));
        lightSet = Textures.descriptorSet(Draw.lightmapTexture());
        return true;
    }

    private static void bindCommon() {
        CommandStream.useLayout(PipelineLibrary.layout(Program.TERRAIN));
        CommandStream.bindUniforms(PassUniforms.descriptorSet(), PassUniforms.currentOffset());
        CommandStream.bindTexture(0, texSet);
        CommandStream.bindTexture(1, lightSet);
        CommandStream.bindIndexBuffer(QuadIndexBuffers.u32Buffer(), QuadIndexBuffers.u32Offset(), VK_INDEX_U32);
    }

    /** {@code alphaTest}: GREATER 0.1, as MITE's alpha test (cutout layer, and the translucent passes of sortAndRender(…,1)). */
    private static void bindPipeline(boolean alphaTest, boolean translucent, boolean cull, boolean blend, boolean depthOnly) {
        boolean write = true;
        int alphaIdx = alphaTest ? AlphaRefs.index(0.1F) : -1;
        long key = MaterialKey.pack(Program.TERRAIN.ordinal(), true, Draw.lightmapEnabled(), Draw.fogEnabled(), false,
                alphaTest ? 1 : 0, Math.max(alphaIdx, 0), blend && !depthOnly, BlendFactor.SRC_ALPHA.ordinal(),
                BlendFactor.ONE_MINUS_SRC_ALPHA.ordinal(), true, DEPTH_LEQUAL, write, cull, depthOnly ? 0 : 7, false, false, false);
        long pipeline = PipelineLibrary.get(Program.TERRAIN, key, VertexFormat.TERRAIN);
        CommandStream.bindPipelineKeepLayout(pipeline);
        CommandStream.setDynamicDepthCull(true, write, VK_COMPARE_LEQUAL, cull ? VK_CULL_BACK : VK_CULL_NONE);
    }

    private static Section get(int i, boolean trans) {
        return trans ? TranslucentOrder.at(i) : VisibilityBfs.visible(i);
    }

    /** One vec4 per list entry (camera-relative origin), bound as vertex binding 1. Returns the buffer handle (0 on failure). */
    private static long writeInstances(int n, boolean trans) {
        StreamSlice s = RenderCore.streamVertex(n * 16);
        if (s == null) {
            return 0L;
        }
        double cx = TerrainRenderer.camX(), cy = TerrainRenderer.camY(), cz = TerrainRenderer.camZ();
        long a = s.address();
        for (int i = 0; i < n; i++) {
            Section sec = get(i, trans);
            long p = a + (long) i * 16;
            if (sec == null) {
                MemoryUtil.memPutFloat(p, 0F);
                MemoryUtil.memPutFloat(p + 4, 0F);
                MemoryUtil.memPutFloat(p + 8, 0F);
            } else {
                MemoryUtil.memPutFloat(p, (float) (sec.x - cx));
                MemoryUtil.memPutFloat(p + 4, (float) (sec.y - cy));
                MemoryUtil.memPutFloat(p + 8, (float) (sec.z - cz));
            }
            MemoryUtil.memPutFloat(p + 12, 0F);
        }
        CommandStream.bindVertexBuffer(1, s.buffer(), s.offset());
        return s.buffer();
    }

    /**
     * True when the section has drawable geometry for {@code layer}. A renderer re-positioned by {@code setPosition} goes through
     * {@code setDontDraw} (both {@code skipRenderPass} entries set) and keeps its old allocations until its rebuild commits; such a
     * section is hidden here, as MITE hides it until rebuilt. {@code markAllRenderersUninitialized} does not touch the pass flags,
     * so those sections keep drawing, as in MITE.
     */
    private static boolean has(Section s, int layer) {
        if (s == null || s.quads[layer] <= 0 || s.alloc[layer] == null) {
            return false;
        }
        boolean[] skip = s.owner.skipRenderPass;
        return !(skip[0] && skip[1]);
    }

    /**
     * Draws one layer of the list. {@code ordered}: preserve list order (runs of one page), else group by page (opaque, MDI).
     * {@code reverse}: iterate from the end (prepass: nearest first).
     */
    private static void drawLayer(int layer, int n, boolean ordered, boolean reverse) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (has(get(i, ordered), layer)) {
                m++;
            }
        }
        if (m == 0) {
            return;
        }
        boolean trans = ordered;
        boolean mdi = DeviceFeatures.mdiEnabled();
        if (!mdi) {
            long bound = 0L;
            for (int k = 0; k < n; k++) {
                int i = reverse ? n - 1 - k : k;
                Section s = get(i, trans);
                if (!has(s, layer)) {
                    continue;
                }
                GpuAllocation al = s.alloc[layer];
                if (al.buffer != bound) {
                    bound = al.buffer;
                    CommandStream.bindVertexBuffer(0, bound, 0L);
                }
                CommandStream.drawIndexed(6 * s.quads[layer], 1, 0, (int) (al.offset / 28), i);
                draws++;
                encoded++;
            }
            return;
        }
        StreamSlice slice = RenderCore.streamIndirect(m * CMD_BYTES);
        if (slice == null) {
            return;
        }
        long addr = slice.address();
        int written = 0;
        if (!ordered) {
            int np = collectPages(n, layer);
            for (int pi = 0; pi < np; pi++) {
                long page = pages[pi];
                int start = written;
                for (int i = 0; i < n; i++) {
                    Section s = get(i, false);
                    if (has(s, layer) && s.alloc[layer].buffer == page) {
                        putCmd(addr, written++, s, layer, i);
                    }
                }
                flush(slice, page, start, written - start);
            }
        } else {
            long page = 0L;
            int start = 0;
            for (int k = 0; k < n; k++) {
                int i = reverse ? n - 1 - k : k;
                Section s = get(i, true);
                if (!has(s, layer)) {
                    continue;
                }
                long buf = s.alloc[layer].buffer;
                if (buf != page) {
                    flush(slice, page, start, written - start);
                    page = buf;
                    start = written;
                }
                putCmd(addr, written++, s, layer, i);
            }
            flush(slice, page, start, written - start);
        }
    }

    private static void putCmd(long addr, int index, Section s, int layer, int instance) {
        long p = addr + (long) index * CMD_BYTES;
        MemoryUtil.memPutInt(p, 6 * s.quads[layer]);
        MemoryUtil.memPutInt(p + 4, 1);
        MemoryUtil.memPutInt(p + 8, 0);
        MemoryUtil.memPutInt(p + 12, (int) (s.alloc[layer].offset / 28));
        MemoryUtil.memPutInt(p + 16, instance);
    }

    private static void flush(StreamSlice slice, long page, int start, int count) {
        if (count <= 0 || page == 0L) {
            return;
        }
        CommandStream.bindVertexBuffer(0, page, 0L);
        CommandStream.drawIndexedIndirect(slice.buffer(), slice.offset() + (long) start * CMD_BYTES, count, CMD_BYTES);
        draws++;
        encoded += count;
    }

    /** Distinct arena pages of the layer in first-seen (front-to-back) order into {@link #pages}. */
    private static int collectPages(int n, int layer) {
        int np = 0;
        for (int i = 0; i < n; i++) {
            Section s = get(i, false);
            if (!has(s, layer)) {
                continue;
            }
            long b = s.alloc[layer].buffer;
            boolean found = false;
            for (int j = 0; j < np; j++) {
                if (pages[j] == b) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (np == pages.length) {
                    pages = java.util.Arrays.copyOf(pages, np * 2);
                }
                pages[np++] = b;
            }
        }
        return np;
    }
}

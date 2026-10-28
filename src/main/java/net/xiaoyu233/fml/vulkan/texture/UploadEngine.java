package net.xiaoyu233.fml.vulkan.texture;

import java.nio.IntBuffer;
import java.util.ArrayList;
import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StreamSlice;
import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Coalescing of native texture uploads (TEX-03, 1.11, 1.12). Every upload copies its pixels into the frame's staging stream at once
 * (a straight memcpy of the ARGB ints: little-endian {@code B,G,R,A} is the {@code B8G8R8A8_UNORM} layout) and appends a region to
 * the pending group of its texture; a group is recorded as one {@code vkCmdCopyBufferToImage} with many regions on the upload command
 * buffer when the batch ends, when the texture is about to be sampled or touched by emulated GL, or at the end of the frame.
 * Outside a batch every upload is recorded immediately. Groups are keyed by (texture, staging buffer): the regions of a copy must come
 * from one buffer and must not overlap, so an overlapping region closes its group first. Render thread only.
 */
final class UploadEngine {
    private static final int STACK_REGIONS = 256;

    /** The regions of one texture that come from one staging buffer. */
    private static final class Group {
        TextureObject tex;
        long buffer;
        int count;
        /** level, x, y, w, h per region. */
        int[] rects = new int[5 * 16];
        long[] offsets = new long[16];

        void add(int level, int x, int y, int w, int h, long offset) {
            if (count == offsets.length) {
                rects = java.util.Arrays.copyOf(rects, rects.length * 2);
                offsets = java.util.Arrays.copyOf(offsets, offsets.length * 2);
            }
            int i = count * 5;
            rects[i] = level;
            rects[i + 1] = x;
            rects[i + 2] = y;
            rects[i + 3] = w;
            rects[i + 4] = h;
            offsets[count] = offset;
            count++;
        }

        boolean overlaps(int level, int x, int y, int w, int h) {
            for (int r = 0; r < count; r++) {
                int i = r * 5;
                if (rects[i] == level && x < rects[i + 1] + rects[i + 3] && rects[i + 1] < x + w && y < rects[i + 2] + rects[i + 4]
                    && rects[i + 2] < y + h) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final ArrayList<Group> OPEN = new ArrayList<>();
    private static final ArrayList<Group> POOL = new ArrayList<>();
    private static int depth;

    private UploadEngine() {
    }

    static void reset() {
        OPEN.clear();
        POOL.clear();
        depth = 0;
    }

    static void beginBatch() {
        depth++;
    }

    static void endBatch() {
        if (depth > 0 && --depth == 0) {
            flushAll();
        }
    }

    /** Closes every open batch level (a caller that threw between begin and end) and records everything pending. */
    static void endAllBatches() {
        depth = 0;
        flushAll();
    }

    static boolean isBatching() {
        return depth > 0;
    }

    /**
     * Copies {@code w x h} pixels of {@code src} (tightly packed, starting at {@code srcOffset}) into staging memory and queues the
     * region; recorded at once outside a batch. The caller validated the rectangle against the level.
     */
    static void addRegion(TextureObject tex, int level, int x, int y, int w, int h, int[] src, int srcOffset) {
        int pixels = w * h;
        if (tex.pendingCount == 0) {
            // draws already batched must keep seeing the contents they were issued against; once a group is pending, any draw that
            // samples the texture flushes it first, so one call per group is enough
            Batches.flushIfTextureReferenced(tex.name);
        }
        StreamSlice slice = RenderCore.streamStaging(pixels * 4);
        IntBuffer target = MemoryUtil.memIntBuffer(slice.address(), pixels);
        target.put(src, srcOffset, pixels);
        Group g = null;
        for (int i = OPEN.size() - 1; i >= 0; i--) {
            Group candidate = OPEN.get(i);
            if (candidate.tex == tex) {
                if (candidate.buffer == slice.buffer()) {
                    g = candidate;
                }
                break; // only the newest group of the texture may be extended: the order of the regions is the order of the calls
            }
        }
        if (g != null && g.overlaps(level, x, y, w, h)) {
            flushTexture(tex); // one copy command must not write a texel twice: close every group of the texture, in call order
            g = null;
        }
        if (g == null) {
            g = POOL.isEmpty() ? new Group() : POOL.remove(POOL.size() - 1);
            g.tex = tex;
            g.buffer = slice.buffer();
            g.count = 0;
            OPEN.add(g);
        }
        g.add(level, x, y, w, h, slice.offset());
        tex.pendingCount++;
        if (depth == 0) {
            flushTexture(tex);
        }
    }

    /** Records every pending group of {@code tex} (in call order). */
    static void flushTexture(TextureObject tex) {
        if (tex.pendingCount == 0) {
            return;
        }
        for (int i = 0; i < OPEN.size(); ) {
            Group g = OPEN.get(i);
            if (g.tex == tex) {
                record(g);
            } else {
                i++;
            }
        }
    }

    static void flushAll() {
        while (!OPEN.isEmpty()) {
            record(OPEN.get(0));
        }
    }

    /** Drops what is pending for a texture whose image goes away. */
    static void discard(TextureObject tex) {
        if (tex.pendingCount == 0) {
            return;
        }
        for (int i = 0; i < OPEN.size(); ) {
            Group g = OPEN.get(i);
            if (g.tex == tex) {
                OPEN.remove(i);
                g.tex = null;
                POOL.add(g);
            } else {
                i++;
            }
        }
        tex.pendingCount = 0;
    }

    private static void record(Group g) {
        OPEN.remove(g);
        TextureObject tex = g.tex;
        int count = g.count;
        g.tex = null;
        tex.pendingCount = Math.max(0, tex.pendingCount - count);
        try {
            if (tex.image == 0L || count == 0) {
                return;
            }
            TextureManager.settleMips(tex);
            int minLevel = Integer.MAX_VALUE;
            int maxLevel = 0;
            boolean full = false;
            for (int r = 0; r < count; r++) {
                int i = r * 5;
                minLevel = Math.min(minLevel, g.rects[i]);
                maxLevel = Math.max(maxLevel, g.rects[i]);
                if (g.rects[i] == 0 && g.rects[i + 1] == 0 && g.rects[i + 2] == 0 && g.rects[i + 3] == tex.width && g.rects[i + 4] == tex.height) {
                    full = true;
                }
            }
            maxLevel = Math.min(maxLevel, tex.mipLevels - 1);
            if (minLevel > maxLevel) {
                return;
            }
            VkCommandBuffer cb = VersionPool.commandBufferForWrite(tex, full && tex.mipLevels == 1);
            int levels = maxLevel - minLevel + 1;
            TextureCommands.barrier(cb, tex.image, minLevel, levels, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            if (count <= STACK_REGIONS) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(count, stack);
                    fill(g, tex, regions, count);
                    TextureCommands.copyBufferToImageRegions(cb, g.buffer, tex.image, regions);
                }
            } else {
                VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(count);
                try {
                    fill(g, tex, regions, count);
                    TextureCommands.copyBufferToImageRegions(cb, g.buffer, tex.image, regions);
                } finally {
                    regions.free();
                }
            }
            TextureCommands.barrier(cb, tex.image, minLevel, levels, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (minLevel == 0) {
                TextureManager.afterLevel0Write(tex); // GL_GENERATE_MIPMAP chains of textures written by emulated code too
            }
        } finally {
            POOL.add(g);
        }
    }

    private static void fill(Group g, TextureObject tex, VkBufferImageCopy.Buffer regions, int count) {
        int used = 0;
        for (int r = 0; r < count; r++) {
            int i = r * 5;
            int level = g.rects[i];
            if (level >= tex.mipLevels) {
                continue;
            }
            VkBufferImageCopy region = regions.get(used++);
            region.bufferOffset(g.offsets[r]).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(g.rects[i + 1], g.rects[i + 2], 0);
            region.imageExtent().set(g.rects[i + 3], g.rects[i + 4], 1);
        }
        regions.limit(used);
    }
}

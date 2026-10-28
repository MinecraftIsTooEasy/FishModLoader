package net.xiaoyu233.fml.vulkan.texture;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Diagnostics for the devtest commands of NR-04 and the testbot: reads the pixels behind a descriptor set (which backing image of a
 * versioned texture a draw would have sampled). Render thread only; each call submits and waits ({@code RenderCore.flushAndWait}),
 * so nothing here belongs on a hot path.
 */
public final class TextureTestSupport {
    private TextureTestSupport() {
    }

    /**
     * Level 0 of the image that {@code descriptorSet} (a value of {@code Textures.descriptorSet(id)}) refers to, as ARGB ints, rows in
     * memory order; null when the set belongs to no image of the texture (for instance it was freed).
     */
    public static int[] readBehindSet(int id, long descriptorSet) {
        TextureObject tex = TextureManager.find(id);
        if (tex == null || tex.image == 0L) {
            return null;
        }
        UploadEngine.flushTexture(tex);
        long image = 0L;
        if (tex.ownsSet(descriptorSet) || tex.currentSet == descriptorSet) {
            image = tex.image;
        } else if (tex.spare != null) {
            for (TextureObject.Version v : tex.spare) {
                if (TextureObject.versionOwnsSet(v, descriptorSet)) {
                    image = v.image;
                    break;
                }
            }
        }
        return image == 0L ? null : readImage(image, tex.width, tex.height);
    }

    /** Level 0 of the current image of {@code id}. */
    public static int[] readCurrent(int id) {
        TextureObject tex = TextureManager.find(id);
        if (tex == null || tex.image == 0L) {
            return null;
        }
        UploadEngine.flushTexture(tex);
        TextureManager.settleMips(tex);
        return readImage(tex.image, tex.width, tex.height);
    }

    /** Whether a texture owns more than one backing image. */
    public static int imageCount(int id) {
        TextureObject tex = TextureManager.find(id);
        return tex == null ? 0 : 1 + (tex.spare == null ? 0 : tex.spare.size());
    }

    private static int[] readImage(long image, int w, int h) {
        long bytes = (long) w * h * 4L;
        if (!TextureManager.ensureReadback(bytes)) {
            return null;
        }
        RenderCore.ensureNoRenderPass();
        VkCommandBuffer cb = RenderCore.mainCommandBuffer();
        TextureCommands.barrier(cb, image, 0, 1, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        TextureCommands.copyImageToBuffer(cb, image, 0, w, h, TextureManager.readbackBuffer, 0L);
        TextureCommands.barrier(cb, image, 0, 1, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        TextureCommands.hostReadBarrier(cb, TextureManager.readbackBuffer);
        RenderCore.flushAndWait();
        Vma.vmaInvalidateAllocation(RenderCore.allocator(), TextureManager.readbackAllocation, 0L, VK10.VK_WHOLE_SIZE);
        int[] out = new int[w * h];
        MemoryUtil.memIntBuffer(TextureManager.readbackAddress, out.length).get(out);
        return out;
    }
}

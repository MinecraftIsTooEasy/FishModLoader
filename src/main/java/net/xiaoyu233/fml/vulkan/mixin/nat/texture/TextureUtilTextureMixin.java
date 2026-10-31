package net.xiaoyu233.fml.vulkan.mixin.nat.texture;

import java.awt.image.BufferedImage;
import net.minecraft.Minecraft;
import net.minecraft.TextureUtil;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Native area "texture": every GL call of {@code TextureUtil} becomes a {@code Textures} call (1.11). The ARGB ints go to staging as
 * they are; the anaglyph conversion stays CPU code here, exactly where MITE applied it (every upload path).
 */
@Mixin(TextureUtil.class)
public abstract class TextureUtilTextureMixin {
    /** Render-thread scratch for {@code BufferedImage.getRGB}; grown on demand, larger than the image when reused. */
    private static int[] scratch = new int[0];

    /**
     * @author fml-native
     * @reason native renderer: texture names come from the native texture store
     */
    @Overwrite
    public static int glGenTextures() {
        return Textures.create();
    }

    /**
     * Uploads the pixels into the texture bound on the active unit, at (x, y); filter and wrap from the flags.
     *
     * @author fml-native
     * @reason native renderer: one ARGB upload instead of chunked glTexSubImage2D through a staging IntBuffer
     */
    @Overwrite
    public static void uploadTextureSub(int[] pixels, int width, int height, int x, int y, boolean blurred, boolean clamped) {
        int[] data = Minecraft.getMinecraft().gameSettings.anaglyph ? TextureUtil.updateAnaglyph(pixels) : pixels;
        Textures.uploadArgb(Textures.bound(), data, width, height, x, y, blurred, clamped);
    }

    /**
     * Creates the storage of a texture and binds it, like glTexImage2D with a null pointer did.
     *
     * @author fml-native
     * @reason native renderer: allocation through the texture store
     */
    @Overwrite
    public static void allocateTexture(int id, int width, int height) {
        Textures.bind(id);
        Textures.allocate(id, width, height);
    }

    /**
     * @author fml-native
     * @reason native renderer: the image is read once and uploaded as ARGB ints
     */
    @Overwrite
    private static void uploadTextureImageSubImpl(BufferedImage image, int x, int y, boolean blurred, boolean clamped) {
        int width = image.getWidth();
        int height = image.getHeight();
        int count = width * height;
        int[] pixels = scratch;
        if (pixels.length < count) {
            pixels = new int[count];
            scratch = pixels;
        }
        image.getRGB(0, 0, width, height, pixels, 0, width);
        // the upload copies into staging before returning, so the scratch array is free again (render thread only)
        uploadTextureSub(pixels, width, height, x, y, blurred, clamped);
    }

    /**
     * @author fml-native
     * @reason native renderer: wrap state of the bound texture through the texture store
     */
    @Overwrite
    private static void setTextureClamped(boolean clamped) {
        Textures.setWrap(Textures.bound(), clamped);
    }

    /**
     * @author fml-native
     * @reason native renderer: filter state of the bound texture through the texture store
     */
    @Overwrite
    private static void setTextureBlurred(boolean blurred) {
        Textures.setFilter(Textures.bound(), blurred);
    }

    /**
     * @author fml-native
     * @reason native renderer: binding through the texture store (mirrored into the native draw state)
     */
    @Overwrite
    static void bindTexture(int id) {
        Textures.bind(id);
    }
}

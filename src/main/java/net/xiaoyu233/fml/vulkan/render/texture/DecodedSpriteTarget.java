package net.xiaoyu233.fml.vulkan.render.texture;

/**
 * Implemented by {@code net.minecraft.TextureAtlasSprite} through the texture mixin: loads a sprite from already decoded pixels, so the
 * PNG decode can run on a worker (TEX-01) while the sprite state is only ever touched by the render thread.
 */
public interface DecodedSpriteTarget {
    /** What {@code TextureAtlasSprite.loadSprite} does before it reads the image: forgets frames, animation and counters. */
    void fml$resetSprite();

    /**
     * What {@code loadSprite} does after it read the image: {@code pixels} is the {@code imageWidth x imageHeight} ARGB image,
     * {@code animation} the {@code animation} metadata section (null for a plain sprite). Throws the same exceptions.
     */
    void fml$loadDecoded(int imageWidth, int imageHeight, int[] pixels, net.minecraft.AnimationMetadataSection animation);
}

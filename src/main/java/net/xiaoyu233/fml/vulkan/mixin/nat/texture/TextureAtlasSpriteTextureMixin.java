package net.xiaoyu233.fml.vulkan.mixin.nat.texture;

import java.util.List;
import net.minecraft.AnimationFrame;
import net.minecraft.AnimationMetadataSection;
import net.minecraft.TextureAtlasSprite;
import net.xiaoyu233.fml.vulkan.render.texture.DecodedSpriteTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Native area "texture": lets {@code TextureMap} load a sprite from pixels that a worker already decoded (TEX-01). The animation upload
 * path itself ({@code updateAnimation} -> {@code TextureUtil.uploadTextureSub}) has no GL call any more and is coalesced per tick by
 * the upload batch that {@code TextureMap.updateAnimations} opens.
 */
@Mixin(TextureAtlasSprite.class)
public abstract class TextureAtlasSpriteTextureMixin implements DecodedSpriteTarget {
    @Shadow
    protected List framesTextureData;
    @Shadow
    private AnimationMetadataSection animationMetadata;
    @Shadow
    protected int width;
    @Shadow
    protected int height;

    @Shadow
    protected abstract void resetSprite();

    /** Frame {@code index} of a vertical strip of square frames. */
    @Unique
    private static int[] frame(int[] strip, int frameSize, int index) {
        int[] data = new int[frameSize * frameSize];
        System.arraycopy(strip, index * data.length, data, 0, data.length);
        return data;
    }

    @Override
    public void fml$resetSprite() {
        this.resetSprite();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void fml$loadDecoded(int imageWidth, int imageHeight, int[] pixels, AnimationMetadataSection animation) {
        this.height = imageHeight;
        this.width = imageWidth;
        if (animation == null) {
            if (this.height != this.width) {
                throw new RuntimeException("broken aspect ratio and not an animation");
            }
            this.framesTextureData.add(pixels);
            return;
        }
        int frames = this.height / this.width;
        int frameWidth = this.width;
        this.height = this.width;
        if (animation.getFrameCount() > 0) {
            for (Object frameIndex : animation.getFrameIndexSet()) {
                int index = (Integer) frameIndex;
                if (index >= frames) {
                    throw new RuntimeException("invalid frameindex " + index);
                }
                while (this.framesTextureData.size() <= index) {
                    this.framesTextureData.add(null);
                }
                this.framesTextureData.set(index, frame(pixels, frameWidth, index));
            }
            this.animationMetadata = animation;
        } else {
            java.util.ArrayList<AnimationFrame> generated = new java.util.ArrayList<>();
            for (int index = 0; index < frames; index++) {
                this.framesTextureData.add(frame(pixels, frameWidth, index));
                generated.add(new AnimationFrame(index, -1));
            }
            this.animationMetadata = new AnimationMetadataSection(generated, this.width, this.height, animation.getFrameTime());
        }
    }
}

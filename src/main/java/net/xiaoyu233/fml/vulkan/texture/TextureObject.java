package net.xiaoyu233.fml.vulkan.texture;

import org.lwjgl.opengl.GL11;

/**
 * Internal representation of a GL texture object (module B, render thread only). The texture owns its VkImage (+ VMA allocation),
 * a lazily created VkImageView and a few descriptor sets (one per sampler it was used with); samplers themselves belong to
 * {@link TextureSamplers} and are never destroyed per texture.
 */
final class TextureObject {
    /** How many (sampler, descriptor set) pairs one texture keeps: typically it only flips between two states (e.g. the lightmap). */
    static final int SET_CACHE = 4;

    /** glTexParameter state (what glPushAttrib(GL_TEXTURE_BIT) saves for the bound texture). Defaults are the GL defaults. */
    static final class Params {
        int minFilter = GL11.GL_NEAREST_MIPMAP_LINEAR;
        int magFilter = GL11.GL_LINEAR;
        int wrapS = GL11.GL_REPEAT;
        int wrapT = GL11.GL_REPEAT;
        int wrapR = GL11.GL_REPEAT;
        float lodBias;
        float minLod = -1000f;
        float maxLod = 1000f;
        int baseLevel;
        int maxLevel = 1000;
        /** GL_TEXTURE_MAX_LEVEL was set by the application (the image is then created with that many levels up front). */
        boolean maxLevelSet;
        float maxAnisotropy = 1f;
        boolean generateMipmap;
        final float[] borderColor = new float[4];

        void copyFrom(Params o) {
            minFilter = o.minFilter;
            magFilter = o.magFilter;
            wrapS = o.wrapS;
            wrapT = o.wrapT;
            wrapR = o.wrapR;
            lodBias = o.lodBias;
            minLod = o.minLod;
            maxLod = o.maxLod;
            baseLevel = o.baseLevel;
            maxLevel = o.maxLevel;
            maxLevelSet = o.maxLevelSet;
            maxAnisotropy = o.maxAnisotropy;
            generateMipmap = o.generateMipmap;
            System.arraycopy(o.borderColor, 0, borderColor, 0, 4);
        }
    }

    final int name;
    /** True once the name was bound (or created by a bind): glIsTexture. */
    boolean created;
    final Params params = new Params();

    // image (level 0 size, B8G8R8A8, every level rests in SHADER_READ_ONLY_OPTIMAL between operations)
    long image;
    long allocation;
    long imageView;
    /** (base level, level count, opaque flag) of {@link #imageView}; -1 when there is no view. */
    int viewKey = -1;
    int width;
    int height;
    /** Levels allocated in the VkImage. */
    int mipLevels;
    /** Bit i set = level i was specified (glTexImage2D) or generated. */
    int definedMask;
    /** GL_GENERATE_MIPMAP: level 0 changed on the upload command buffer and levels 1.. are regenerated when the texture is next sampled. */
    boolean mipsStale;
    int internalFormat = GL11.GL_RGBA;
    int formatClass = PixelFormatConverter.CLASS_RGBA;
    /** Alpha reads as 1 (framebuffer copies / texture formats without alpha): view swizzle A=ONE when the device supports it. */
    boolean copyOpaque;
    /** Frame number in which the main command buffer last referenced this texture (sampled or written inline). */
    long lastUsedFrame = -1;

    // descriptor sets
    boolean setDirty = true;
    /** The set to bind; 0 means "not available, use the white texture". */
    long currentSet;
    boolean errorLogged;
    private final long[] setSamplers = new long[SET_CACHE];
    private final long[] sets = new long[SET_CACHE];
    private final TextureDescriptors.Pool[] setPools = new TextureDescriptors.Pool[SET_CACHE];
    private int setCount;

    TextureObject(int name) {
        this.name = name;
    }

    boolean hasImage() {
        return image != 0;
    }

    boolean isDefined(int level) {
        return level >= 0 && level < 31 && (definedMask & (1 << level)) != 0;
    }

    long findSet(long sampler) {
        for (int i = 0; i < setCount; i++) {
            if (setSamplers[i] == sampler) {
                return sets[i];
            }
        }
        return 0L;
    }

    void addSet(long sampler, long set, TextureDescriptors.Pool pool) {
        if (setCount == SET_CACHE) {
            // evict the oldest entry; the set may still be referenced by command buffers in flight, so the free is deferred
            TextureDescriptors.deferFree(sets[0], setPools[0]);
            for (int i = 1; i < SET_CACHE; i++) {
                setSamplers[i - 1] = setSamplers[i];
                sets[i - 1] = sets[i];
                setPools[i - 1] = setPools[i];
            }
            setCount--;
        }
        setSamplers[setCount] = sampler;
        sets[setCount] = set;
        setPools[setCount] = pool;
        setCount++;
    }

    /** Gives every cached descriptor set back (deferred) and forces a new one to be resolved on the next use. */
    void freeAllSets() {
        for (int i = 0; i < setCount; i++) {
            TextureDescriptors.deferFree(sets[i], setPools[i]);
            sets[i] = 0L;
            setPools[i] = null;
            setSamplers[i] = 0L;
        }
        setCount = 0;
        currentSet = 0L;
        setDirty = true;
    }

    /** Forgets the cached sets without freeing them (their pools are destroyed by the caller). */
    void dropAllSets() {
        for (int i = 0; i < setCount; i++) {
            sets[i] = 0L;
            setPools[i] = null;
            setSamplers[i] = 0L;
        }
        setCount = 0;
        currentSet = 0L;
        setDirty = true;
    }
}

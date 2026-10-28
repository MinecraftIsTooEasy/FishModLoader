package net.xiaoyu233.fml.vulkan.mixin.nat.texture;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.Minecraft;
import net.minecraft.ReportedException;
import net.minecraft.ResourceLocation;
import net.minecraft.ResourceManager;
import net.minecraft.Stitcher;
import net.minecraft.StitcherException;
import net.minecraft.TextureAtlasSprite;
import net.minecraft.TextureMap;
import net.minecraft.TextureUtil;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshWorkers;
import net.xiaoyu233.fml.vulkan.render.texture.AtlasPrefetch;
import net.xiaoyu233.fml.vulkan.render.texture.DecodedSpriteTarget;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "texture": atlas reload with parallel sprite decode (TEX-01), one bulk upload of the whole atlas, opacity map and optional
 * mip levels (1.11), and per tick coalescing of the animation uploads (TEX-03). The stitching itself is untouched: sprites are added in
 * the original order and {@link Stitcher} sorts them, so the atlas layout is identical to the serial loader's.
 */
@Mixin(TextureMap.class)
public abstract class TextureMapTextureMixin {
    @Shadow
    @Final
    private List listAnimatedSprites;
    @Shadow
    @Final
    private Map mapRegisteredSprites;
    @Shadow
    @Final
    private Map mapUploadedSprites;
    @Shadow
    @Final
    private String basePath;
    @Shadow
    @Final
    private TextureAtlasSprite missingImage;

    /**
     * Loads, stitches and uploads the atlas. Mesh workers are drained first: sprites must not be swapped under {@code getAtlasSprite}
     * while a worker meshes with them (1.15).
     *
     * @author fml-native
     * @reason native renderer: epoch bump before the reload, parallel PNG decode, bulk upload with opacity map and optional mip levels
     */
    @Overwrite
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void loadTextureAtlas(ResourceManager resources) {
        MeshWorkers.bumpEpoch("reload");
        long startNanos = System.nanoTime();
        TextureMap self = (TextureMap) (Object) this;
        int maxSize = Minecraft.getGLMaximumTextureSize();
        Stitcher stitcher = new Stitcher(maxSize, maxSize, true);
        this.mapUploadedSprites.clear();
        this.listAnimatedSprites.clear();

        int registered = this.mapRegisteredSprites.size();
        Object[] iconNames = new Object[registered];
        TextureAtlasSprite[] sprites = new TextureAtlasSprite[registered];
        ResourceLocation[] locations = new ResourceLocation[registered];
        int next = 0;
        for (Object entryObject : this.mapRegisteredSprites.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObject;
            ResourceLocation iconLocation = new ResourceLocation((String) entry.getKey(), false);
            iconNames[next] = entry.getKey();
            sprites[next] = (TextureAtlasSprite) entry.getValue();
            locations[next] = new ResourceLocation(iconLocation.getResourceDomain(),
                String.format("%s/%s%s", this.basePath, iconLocation.getResourcePath(), ".png"), false);
            next++;
        }

        // phase 1: resources are looked up on this thread in the original order, the PNG decode runs on the worker pool
        AtlasPrefetch prefetch = new AtlasPrefetch(registered);
        for (int i = 0; i < registered; i++) {
            prefetch.fetch(i, resources, locations[i]);
        }
        // phase 2: same order as MITE's loop; failures are reported exactly as the serial loader did
        for (int i = 0; i < registered; i++) {
            TextureAtlasSprite sprite = sprites[i];
            try {
                prefetch.load(i, (DecodedSpriteTarget) sprite);
            } catch (RuntimeException e) {
                Minecraft.getMinecraft().getLogAgent().logSevere(
                    String.format("Unable to parse animation metadata from %s: %s", locations[i], e.getMessage()));
                continue;
            } catch (Throwable e) {
                if (!(e instanceof IOException)) {
                    throw e; // MITE's loop only catches RuntimeException and IOException
                }
                String message = "Missing resource: " + locations[i].getResourcePath();
                Minecraft.getMinecraft().getLogAgent().logSevere(message);
                Minecraft.setErrorMessage(message, false);
                continue;
            }
            stitcher.addSprite(sprite);
        }
        stitcher.addSprite(this.missingImage);
        long decodedNanos = System.nanoTime();
        stitcher.doStitch();

        List<TextureAtlasSprite> stitched = stitcher.getStichSlots();
        int atlasWidth = stitcher.getCurrentWidth();
        int atlasHeight = stitcher.getCurrentHeight();
        int alignment = 8;
        for (TextureAtlasSprite sprite : stitched) {
            alignment = Math.min(alignment, Textures.alignmentBits(sprite.getOriginX(), sprite.getOriginY(), sprite.getIconWidth(),
                sprite.getIconHeight()));
        }
        int id = self.getGlTextureId();
        Textures.bind(id);
        Textures.allocateAtlas(id, atlasWidth, atlasHeight, alignment);
        HashMap notStitched = new HashMap(this.mapRegisteredSprites);

        Textures.beginUploadBatch();
        try {
            for (TextureAtlasSprite sprite : stitched) {
                String iconName = sprite.getIconName();
                notStitched.remove(iconName);
                this.mapUploadedSprites.put(iconName, sprite);

                try {
                    TextureUtil.uploadTextureSub(sprite.getFrameTextureData(0), sprite.getIconWidth(), sprite.getIconHeight(), sprite.getOriginX(),
                        sprite.getOriginY(), false, false);
                } catch (Throwable t) {
                    CrashReport report = CrashReport.makeCrashReport(t, "Stitching texture atlas");
                    CrashReportCategory category = report.makeCategory("Texture being stitched together");
                    category.addCrashSection("Atlas path", this.basePath);
                    category.addCrashSection("Sprite", sprite);
                    throw new ReportedException(report);
                }
                Textures.atlasSprite(id, sprite.getOriginX(), sprite.getOriginY(), sprite.getIconWidth(), sprite.getIconHeight(),
                    isOpaque(sprite));

                if (sprite.hasAnimationMetadata()) {
                    this.listAnimatedSprites.add(sprite);
                } else {
                    sprite.clearFramesTextureData();
                }
            }
        } finally {
            Textures.endUploadBatch();
        }
        Textures.registerAtlas(id);

        for (Object missing : notStitched.values()) {
            ((TextureAtlasSprite) missing).copyFrom(this.missingImage);
        }
        long endNanos = System.nanoTime();
        VkLog.LOGGER.info("Atlas {} stitched: {} sprites -> {}x{} in {} ms (read+decode {} ms: {} sprites on the worker pool, {} ms waiting; "
                + "stitch+upload {} ms)", this.basePath, stitched.size(), atlasWidth, atlasHeight, (endNanos - startNanos) / 1_000_000L,
            (decodedNanos - startNanos) / 1_000_000L, prefetch.parallelCount(), prefetch.decodeWaitNanos() / 1_000_000L,
            (endNanos - decodedNanos) / 1_000_000L);
    }

    /** All alpha values of every frame of the sprite are 255 (an animation that ever turns transparent makes the sprite a cutout). */
    private static boolean isOpaque(TextureAtlasSprite sprite) {
        int pixels = sprite.getIconWidth() * sprite.getIconHeight();
        for (int frame = 0; frame < sprite.getFrameCount(); frame++) {
            int[] data = sprite.getFrameTextureData(frame);
            if (data != null && !net.xiaoyu233.fml.vulkan.render.texture.AtlasOpacity.allOpaque(data, pixels)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ticks the animated sprites; every upload of the tick lands in one staging block and one copy command (TEX-03).
     *
     * @author fml-native
     * @reason native renderer: animation uploads coalesced into one batch per tick
     */
    @Overwrite
    public void updateAnimations() {
        TextureMap self = (TextureMap) (Object) this;
        Textures.bind(self.getGlTextureId());
        Textures.beginUploadBatch();
        try {
            for (Object sprite : this.listAnimatedSprites) {
                ((TextureAtlasSprite) sprite).updateAnimation();
            }
        } finally {
            Textures.endUploadBatch();
        }
    }
}

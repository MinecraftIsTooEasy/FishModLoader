package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link TextureMap}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Call {@code registerIcons()} at the start of
 *       {@code loadTextureAtlas}.</li>
 *   <li>Fire {@link ForgeHooksClient#onTextureStitchedPre} and
 *       {@code onTextureStitchedPost}.</li>
 *   <li>Use {@code textureatlassprite.load(ResourceManager, ResourceLocation)}.</li>
 *   <li>Null safety for registerIcon.</li>
 *   <li>Add {@code getTextureExtry} and {@code setTextureEntry} methods.</li>
 * </ul>
 */
@Mixin(TextureMap.class)
public class TextureMapMixin {

    @Shadow
    private java.util.Map mapRegisteredSprites;

    @Shadow
    private java.util.Map mapUploadedSprites;

    @Shadow
    private java.util.List listAnimatedSprites;

    @Shadow
    private TextureAtlasSprite missingImage;

    /**
     * Placeholder: The patch calls {@code registerIcons()} at the
     * start of {@code loadTextureAtlas} to re-gather the icon list.
     * <p>
     * This is a body insertion in a complex method.
     */
    @Unique
    private void fmlForgeReRegisterIcons() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for registerIcons() call in loadTextureAtlas.");
    }

    /**
     * Placeholder: The patch replaces
     * {@code textureatlassprite.loadSprite(...)} with
     * {@code if (!textureatlassprite.load(par1ResourceManager, resourcelocation1)) continue;}.
     * <p>
     * This changes both the method call and adds control flow.
     */
    @Unique
    private void fmlForgeTextureLoad() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for textureatlassprite.load() migration.");
    }

    /**
     * Placeholder: The patch fires
     * {@code ForgeHooksClient.onTextureStitchedPre(this)} before the
     * stitching loop and
     * {@code ForgeHooksClient.onTextureStitchedPost(this)} after.
     * <p>
     * These are method-call insertions requiring direct patching.
     */
    @Unique
    private void fmlForgeTextureStitchedHooks() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for onTextureStitchedPre/Post hooks.");
    }

    /**
     * Placeholder: The patch replaces a dangling null-registration
     * with {@code par1Str = "null"} to prevent NPEs.
     * <p>
     * This is a single-line insertion.
     */
    @Unique
    private void fmlForgeNullRegistrationSafety() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for null registration safety.");
    }

    /**
     * New method matching the Forge patch:
     * {@code getTextureExtry(String name)}.
     * <p>
     * Returns the registered entry for the given name, or null if
     * no entry exists.
     */
    @Unique
    public TextureAtlasSprite getTextureExtry(String name) {
        return (TextureAtlasSprite) mapRegisteredSprites.get(name);
    }

    /**
     * New method matching the Forge patch:
     * {@code setTextureEntry(String name, TextureAtlasSprite entry)}.
     * <p>
     * Adds a texture entry if one does not already exist.
     *
     * @param name  Entry name
     * @param entry Entry instance
     * @return true if the entry was added, false if it already existed
     */
    @Unique
    public boolean setTextureEntry(String name, TextureAtlasSprite entry) {
        if (!mapRegisteredSprites.containsKey(name)) {
            mapRegisteredSprites.put(name, entry);
            return true;
        }
        return false;
    }
}

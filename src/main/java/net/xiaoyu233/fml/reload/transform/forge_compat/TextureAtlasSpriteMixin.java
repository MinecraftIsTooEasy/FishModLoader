package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.resources.Resource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.ResourceManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link TextureAtlasSprite}.
 * <p>
 * Adds a new {@code load(ResourceManager, ResourceLocation)} method
 * that delegates to {@code loadSprite(Resource)} and returns
 * {@code true}. Returning {@code false} prevents stitching.
 */
@Mixin(TextureAtlasSprite.class)
public class TextureAtlasSpriteMixin {

    @Shadow
    public void loadSprite(Resource par1Resource) {}

    /**
     * New method matching the Forge patch:
     * {@code load(ResourceManager, ResourceLocation)}.
     * <p>
     * Loads the sprite from the given location and returns true
     * to allow stitching.
     *
     * @param manager  Main resource manager
     * @param location File resource location
     * @return true (always, to allow stitching)
     */
    @Unique
    public boolean load(ResourceManager manager, ResourceLocation location) {
        loadSprite(manager.getResource(location));
        return true;
    }
}

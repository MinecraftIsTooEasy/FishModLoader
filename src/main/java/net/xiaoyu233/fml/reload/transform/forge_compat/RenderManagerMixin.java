package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.entity.RenderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link RenderManager}.
 * <p>
 * The patch modifies bed-direction detection to use
 * {@code Block.isBed} and {@code Block.getBedDirection} instead
 * of hardcoded bed-block ID and metadata.
 */
@Mixin(RenderManager.class)
public class RenderManagerMixin {

    /**
     * Placeholder: The patch changes the sleeping-player bed-direction
     * code to use {@code Block.isBed} and
     * {@code Block.getBedDirection} instead of checking for
     * {@code Block.bed.blockID} and parsing metadata directly.
     * <p>
     * This is a body modification inside the
     * {@code cacheActiveRenderInfo} method.
     */
    @Unique
    private void fmlForgeBedDirection() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for bed direction via Block.isBed/getBedDirection.");
    }
}

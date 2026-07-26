package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link WorldRenderer}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Uses {@code Tessellator.instance} instead of a private field.</li>
 *   <li>Uses {@code block.hasTileEntity(metadata)} instead of
 *       {@code block.hasTileEntity()}.</li>
 *   <li>Adds {@code block.canRenderInPass(pass)} checks.</li>
 *   <li>Changes render-pass comparisons from equality to
 *       {@code i3 > l1}.</li>
 * </ul>
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    /**
     * Placeholder: The patch changes the tessellator reference from a
     * private static field to always use {@code Tessellator.instance},
     * and makes several block-rendering logic changes.
     * <p>
     * These are body-level changes inside the
     * {@code updateRenderer} method and require direct patching.
     */
    @Unique
    private void fmlForgeWorldRendererChanges() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for WorldRenderer.");
    }
}

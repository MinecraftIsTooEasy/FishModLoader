package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.tileentity.TileEntityChestRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link TileEntityChestRenderer}.
 * <p>
 * The patch wraps the {@code unifyAdjacentChests} call in a
 * try-catch for {@link ClassCastException} to prevent rendering
 * crashes when a non-chest tile entity is encountered.
 */
@Mixin(TileEntityChestRenderer.class)
public class TileEntityChestRendererMixin {

    /**
     * Placeholder: The patch wraps
     * {@code ((BlockChest)block).unifyAdjacentChests(...)} in a
     * try-catch(ClassCastException) block.
     * <p>
     * This is a single-call wrapping that requires direct patching.
     */
    @Unique
    private void fmlForgeChestRendererClassCast() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for ClassCastException catch.");
    }
}

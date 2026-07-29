package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockComparator;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link BlockComparator}.
 *
 * <p>Forge 1.6.4 adds {@code onNeighborTileChange} and {@code weakTileChanges}
 * so that comparators react when an adjacent tile entity's inventory changes
 * without a full block update. MITE has neither method, so both are added
 * here as real new members via {@code @Unique}.
 *
 * <p>{@code onNeighborTileChange} forwards to MITE's existing
 * {@code onNeighborBlockChange(World,int,int,int,int)} which returns
 * {@code boolean} (vanilla/Forge returned {@code void}) — the return value is
 * discarded here since Forge's contract has no result.
 */
@Mixin(BlockComparator.class)
public abstract class BlockComparatorMixin {

    /** MITE's real neighbour-change entry point, declared on Block/BlockRedstoneLogic. */
    @Shadow
    public abstract boolean onNeighborBlockChange(World world, int x, int y, int z, int neighborBlockId);

    /**
     * Forge addition: called when a neighbouring tile entity changes.
     * Only reacts to changes on the same Y level, matching Forge's behaviour.
     */
    @Unique
    public void onNeighborTileChange(World world, int x, int y, int z, int tileX, int tileY, int tileZ) {
        if (y == tileY) {
            this.onNeighborBlockChange(world, x, y, z, world.getBlockId(tileX, tileY, tileZ));
        }
    }

    /**
     * Forge addition: signals that this block only needs weak (tile-level)
     * change notifications rather than full block updates.
     */
    @Unique
    public boolean weakTileChanges() {
        return true;
    }
}

package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockComparator;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockComparator.class)
public class BlockComparatorMixin {
    @Unique
    public void onNeighborTileChange(World world, int x, int y, int z, int tileX, int tileY, int tileZ) {
        if (y == tileY) {
            onNeighborBlockChange(world, x, y, z, world.getBlockId(tileX, tileY, tileZ));
        }
    }

    @Unique
    public boolean weakTileChanges() {
        return true;
    }

    @Unique
    public void onNeighborBlockChange(World world, int x, int y, int z, int blockId) {}
}

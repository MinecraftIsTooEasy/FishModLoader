package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BlockSand.class)
public class BlockSandMixin {
    /**
     * @reason Use isAirBlock instead of getBlockId == 0 for Forge compat
     */
    @Overwrite
    public boolean canFallAbove(World world, int x, int y, int z) {
        int l = world.getBlockId(x, y, z);
        if (world.isAirBlock(x, y, z)) {
            return true;
        }
        Block block = Block.blocksList[l];
        if (block == null) {
            return true;
        }
        return block.blockMaterial.isReplaceable();
    }
}

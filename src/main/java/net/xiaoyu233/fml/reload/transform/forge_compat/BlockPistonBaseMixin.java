package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BlockPistonBase.class)
public class BlockPistonBaseMixin {
    /**
     * @reason Use getBlockHardness(metadata) and blockHasTileEntity for Forge compat
     */
    @Overwrite
    private static boolean canPushBlock(int blockId, World world, int x, int y, int z, boolean requireHardness) {
        if (blockId == 0) return true;
        Block block = Block.blocksList[blockId];
        if (block == null) return true;
        int meta = world.getBlockMetadata(x, y, z);
        if (block.getBlockHardness(meta) == -1.0F) return false;
        if (block instanceof net.minecraft.block.ITileEntityProvider) return false;
        return !world.blockHasTileEntity(x, y, z);
    }
}

package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFarmland;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(BlockFarmland.class)
public class BlockFarmlandMixin {
    /**
     * @reason Use Block.lightOpacity array instead of getBlockLightOpacity for Forge compat
     */
    @Unique
    public void updateTick(World world, int x, int y, int z, Random rand) {
        if (!world.isRemote) {
            if (world.getBlockLightValue(x, y + 1, z) < 4 &&
                Block.lightOpacity[world.getBlockId(x, y + 1, z)] > 2) {
                world.setBlock(x, y, z, BlockFarmland.dirt.blockID);
            } else {
                for (int l = 0; l < 4; ++l) {
                    int i1 = x + rand.nextInt(3) - 1;
                    int j1 = y + rand.nextInt(5) - 3;
                    int k1 = z + rand.nextInt(3) - 1;

                    if (world.getBlockId(i1, j1, k1) == BlockFarmland.dirt.blockID &&
                        world.getBlockLightValue(i1, j1 + 1, k1) >= 4 &&
                        Block.lightOpacity[world.getBlockId(i1, j1 + 1, k1)] <= 2) {
                        world.setBlock(i1, j1, k1, BlockFarmland.grass.blockID);
                    }
                }
            }
        }
    }
}

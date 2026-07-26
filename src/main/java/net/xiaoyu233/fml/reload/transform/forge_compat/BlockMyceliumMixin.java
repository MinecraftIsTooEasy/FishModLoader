package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockMycelium;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Random;

@Mixin(BlockMycelium.class)
public abstract class BlockMyceliumMixin {
    /**
     * @reason Use Block.lightOpacity array instead of getBlockLightOpacity for Forge compat
     */
    @Overwrite
    public void updateTick(World world, int x, int y, int z, Random rand) {
        if (!world.isRemote) {
            if (world.getBlockLightValue(x, y + 1, z) < 4 && Block.lightOpacity[world.getBlockId(x, y + 1, z)] > 2) {
                world.setBlock(x, y, z, BlockMycelium.dirt.blockID);
            } else {
                if (world.getBlockLightValue(x, y + 1, z) >= 9) {
                    for (int l = 0; l < 4; ++l) {
                        int i1 = x + rand.nextInt(3) - 1;
                        int j1 = y + rand.nextInt(5) - 3;
                        int k1 = z + rand.nextInt(3) - 1;
                        int l1 = world.getBlockId(i1, j1 + 1, k1);

                        if (world.getBlockId(i1, j1, k1) == BlockMycelium.dirt.blockID &&
                            world.getBlockLightValue(i1, j1 + 1, k1) >= 4 &&
                            Block.lightOpacity[world.getBlockId(i1, j1 + 1, k1)] <= 2) {
                            world.setBlock(i1, j1, k1, BlockMycelium.mycelium.blockID);
                        }
                    }
                }
            }
        }
    }
}

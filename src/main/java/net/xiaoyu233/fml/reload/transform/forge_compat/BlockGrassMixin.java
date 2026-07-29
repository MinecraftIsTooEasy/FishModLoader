package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockGrass;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(BlockGrass.class)
public abstract class BlockGrassMixin {
    /**
     * DEAD METHOD: MITE's updateTick returns boolean; this mixin returns void.
     * The JVM treats them as distinct methods (different descriptors), so both
     * coexist in the class but MITE only calls its own boolean variant. This
     * Forge-compat logic never executes.
     *
     * Originally intended @reason: Use Block.lightOpacity array instead of
     * getBlockLightOpacity for Forge compat.
     */
    @Unique
    public void updateTick(World world, int x, int y, int z, Random rand) {
        if (!world.isRemote) {
            if (world.getBlockLightValue(x, y + 1, z) < 4 && Block.lightOpacity[world.getBlockId(x, y + 1, z)] > 2) {
                world.setBlock(x, y, z, BlockGrass.dirt.blockID);
            } else {
                if (world.getBlockLightValue(x, y + 1, z) >= 9) {
                    for (int l = 0; l < 4; ++l) {
                        int i1 = x + rand.nextInt(3) - 1;
                        int j1 = y + rand.nextInt(5) - 3;
                        int k1 = z + rand.nextInt(3) - 1;
                        int l1 = world.getBlockId(i1, j1 + 1, k1);

                        if (world.getBlockId(i1, j1, k1) == BlockGrass.dirt.blockID &&
                            world.getBlockLightValue(i1, j1 + 1, k1) >= 4 &&
                            Block.lightOpacity[world.getBlockId(i1, j1 + 1, k1)] <= 2) {
                            world.setBlock(i1, j1, k1, BlockGrass.grass.blockID);
                        }
                    }
                }
            }
        }
    }
}

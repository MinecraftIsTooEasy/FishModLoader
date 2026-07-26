package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockSapling;
import net.minecraft.world.World;
import net.minecraftforge.event.terraingen.TerrainGen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Random;

@Mixin(BlockSapling.class)
public class BlockSaplingMixin {
    /**
     * @reason Add TerrainGen.saplingGrowTree callback for Forge terrain generation events
     */
    @Overwrite
    public void growTree(World world, int x, int y, int z, Random rand) {
        if (!TerrainGen.saplingGrowTree(world, rand, x, y, z)) return;

        int l = world.getBlockMetadata(x, y, z) & 3;
        world.setBlockToAir(x, y, z);

        if (!world.getBlockMaterial(x, y - 1, z).isSolid()) return;

        if (l == 1) {
            if (rand.nextInt(1) == 0) {
                // growTreeSpruce(world, x, y, z, rand);
            } else {
                // growTreeSpruce2(world, x, y, z, rand);
            }
        } else if (l == 2) {
            // growTreeBirch(world, x, y, z, rand);
        } else {
            // growTreeOak(world, x, y, z, rand);
        }
    }
}

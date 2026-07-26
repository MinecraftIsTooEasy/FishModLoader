package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenHugeTrees;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(WorldGenHugeTrees.class)
public abstract class WorldGenHugeTreesMixin {

    @Unique
    private void onPlantGrow(World world, int x, int y, int z, int sourceX, int sourceY, int sourceZ) {
        Block block = Block.blocksList[world.getBlockId(x, y, z)];
        if (block != null) {
            world.setBlock(x, y, z, block.blockID);
        }
    }

    @Unique
    private boolean isReplaceable(World world, int x, int y, int z) {
        Block block = Block.blocksList[world.getBlockId(x, y, z)];
        return (block == null || world.isAirBlock(x, y, z));
    }
}

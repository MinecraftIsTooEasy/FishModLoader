package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLog;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(BlockLog.class)
public class BlockLogMixin {
    @Overwrite
    public boolean updateTick(World world, int x, int y, int z, Random rand) {
        if (!world.isRemote && world.getBlockId(x, y, z) == BlockLog.wood.blockID) {
            world.setBlockToAir(x, y, z);
        }
        return false;
    }

    @Unique
    public boolean canSustainLeaves(World world, int x, int y, int z) {
        return true;
    }

    @Unique
    public boolean isWood(World world, int x, int y, int z) {
        return true;
    }
}

package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockChest;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockChest.class)
public abstract class BlockChestMixin {
    @Unique
    public boolean canPlaceBlockAt(World world, int x, int y, int z) {
        int blockID = ((BlockChest)(Object)this).blockID;
        int count = 0;
        if (world.getBlockId(x - 1, y, z) == blockID) ++count;
        if (world.getBlockId(x + 1, y, z) == blockID) ++count;
        if (world.getBlockId(x, y, z - 1) == blockID) ++count;
        if (world.getBlockId(x, y, z + 1) == blockID) ++count;
        return count <= 1;
    }
}

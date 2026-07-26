package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockMushroom;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Random;

@Mixin(BlockMushroom.class)
public class BlockMushroomMixin {
    /**
     * @reason Use simple block ID check instead of canSustainPlant for Forge compat
     */
    @Overwrite
    public boolean canBlockStay(World world, int x, int y, int z) {
        if (y >= 0 && y < 256) {
            int l = world.getBlockId(x, y - 1, z);
            Block soil = Block.blocksList[l];
            return (l == Block.mycelium.blockID || world.getFullBlockLightValue(x, y, z) < 13) &&
                   (soil != null);
        }
        return false;
    }
}

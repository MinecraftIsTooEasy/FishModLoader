package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFlower;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BlockFlower.class)
public abstract class BlockFlowerMixin {
    /**
     * @reason Forge patch: flowers need light or sky access
     * (converted from source patch to Mixin)
     */
    @Overwrite
    public boolean canBlockStay(World world, int x, int y, int z) {
        int l = world.getBlockId(x, y - 1, z);
        return (l == Block.grass.blockID || l == Block.dirt.blockID || l == Block.tilledField.blockID) &&
               (world.getFullBlockLightValue(x, y, z) >= 8 || world.canBlockSeeTheSky(x, y, z));
    }
}

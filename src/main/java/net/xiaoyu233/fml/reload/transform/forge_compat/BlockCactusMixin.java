package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockCactus;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BlockCactus.class)
public abstract class BlockCactusMixin {
    /**
     * @reason Forge patch: allow cactus to grow on sand or other cactus only
     * (converted from source patch to Mixin)
     */
    @Overwrite
    public boolean canBlockStay(World world, int x, int y, int z) {
        int l = world.getBlockId(x, y - 1, z);
        return l == Block.sand.blockID || l == Block.cactus.blockID;
    }
}

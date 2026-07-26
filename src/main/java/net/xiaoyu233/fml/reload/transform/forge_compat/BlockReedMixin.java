package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockReed;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BlockReed.class)
public abstract class BlockReedMixin {
    /**
     * @reason Forge patch: sugar cane can grow on grass/dirt/sand adjacent to water,
     * or on top of another reed; uses null-safe block lookup.
     * (converted from source patch to Mixin)
     */
    @Overwrite
    public boolean canBlockStay(World world, int x, int y, int z) {
        int l = world.getBlockId(x, y - 1, z);
        Block soil = Block.blocksList[l];
        if (soil == null) return false;
        if (soil == Block.reed) return true;
        if (soil == Block.grass || soil == Block.dirt || soil == Block.sand) {
            for (int i1 = x - 1; i1 <= x + 1; ++i1) {
                for (int j1 = z - 1; j1 <= z + 1; ++j1) {
                    if (world.getBlockMaterial(i1, y - 1, j1) == Material.water) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}

package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSnow;
import net.minecraft.util.EnumFace;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(BlockSnow.class)
public class BlockSnowMixin {
    @Unique
    private boolean canSnowStayAt(World world, int x, int y, int z) {
        int l = world.getBlockId(x, y - 1, z);
        Block block = Block.blocksList[l];
        if (block == null) return false;
        if (block == Block.snow && (world.getBlockMetadata(x, y - 1, z) & 7) == 7) return true;
        int meta = world.getBlockMetadata(x, y - 1, z);
        if (block == Block.leaves) {
            return true;
        }
        return block.isFaceFlatAndSolid(meta, EnumFace.TOP);
    }

    @Unique
    public boolean canPlaceBlockAt(World world, int x, int y, int z) {
        return canSnowStayAt(world, x, y, z);
    }

    @Unique
    public boolean canBlockStay(World world, int x, int y, int z) {
        if (!canSnowStayAt(world, x, y, z)) {
            world.setBlockToAir(x, y, z);
            return false;
        }
        return true;
    }

    /** NOTE: MITE has no quantityDropped (func_71925_a) on BlockSnow or Block,
     * so this cannot be an @Overwrite. Kept inert until rewired. See PLAN.md.
     */
    @Unique
    public int quantityDropped(Random random) {
        return 1;
    }

    @Unique
    public int quantityDropped(int meta, int fortune, Random random) {
        return (meta & 7) + 1;
    }

    @Overwrite
    public boolean updateTick(World world, int x, int y, int z, Random rand) {
        if (world.getSavedLightValue(net.minecraft.world.EnumSkyBlock.Block, x, y, z) > 11) {
            world.setBlockToAir(x, y, z);
        }
        return false;
    }

    @Unique
    public boolean isBlockReplaceable(World world, int x, int y, int z) {
        int meta = world.getBlockMetadata(x, y, z);
        return meta >= 7 ? false : Block.snow.blockMaterial.isReplaceable();
    }
}

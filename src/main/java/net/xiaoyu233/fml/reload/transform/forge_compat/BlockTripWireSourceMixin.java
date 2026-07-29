package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockTripWireSource;
import net.minecraft.util.EnumFace;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockTripWireSource.class)
public abstract class BlockTripWireSourceMixin {

    @Unique
    private static boolean isBlockSolidOnSide(World world, int x, int y, int z, EnumFace face) {
        int id = world.getBlockId(x, y, z);
        Block block = Block.blocksList[id];
        if (block == null) return false;
        int meta = world.getBlockMetadata(x, y, z);
        return block.isFaceFlatAndSolid(meta, face);
    }

    @Unique
    public boolean canPlaceBlockOnSide(World world, int x, int y, int z, int side) {
        EnumFace dir = EnumFace.get(side);
        return (dir == EnumFace.NORTH && isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH)) ||
               (dir == EnumFace.SOUTH && isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH)) ||
               (dir == EnumFace.WEST  && isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST)) ||
               (dir == EnumFace.EAST  && isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST));
    }

    @Unique
    public boolean canPlaceBlockAt(World world, int x, int y, int z) {
        return isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST) ||
               isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST) ||
               isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH) ||
               isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH);
    }

    /**
     * NOTE: MITE has no onBlockPlaced (func_85104_a) on this class, so this
     * cannot be an @Overwrite. Kept inert until rewired. See PLAN.md.
     */
    @Unique
    public int onBlockPlaced(World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ, int meta) {
        byte b0 = 0;

        if (side == 2 && isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH)) b0 = 2;
        if (side == 3 && isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH)) b0 = 0;
        if (side == 4 && isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST)) b0 = 1;
        if (side == 5 && isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST)) b0 = 3;

        return b0;
    }

    @Shadow
    public abstract boolean onNotLegal(World world, int x, int y, int z, int metadata);

    @Unique
    public boolean canBlockStay(World world, int x, int y, int z) {
        int i1 = world.getBlockMetadata(x, y, z);
        int j1 = i1 & 3;
        boolean flag = false;

        if (!isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST) && j1 == 3) flag = true;
        if (!isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST) && j1 == 1) flag = true;
        if (!isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH) && j1 == 0) flag = true;
        if (!isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH) && j1 == 2) flag = true;

        if (flag) {
            this.onNotLegal(world, x, y, z, i1);
            return false;
        }
        return true;
    }
}

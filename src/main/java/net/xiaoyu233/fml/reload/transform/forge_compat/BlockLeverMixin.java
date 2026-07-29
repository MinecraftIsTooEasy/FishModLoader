package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLever;
import net.minecraft.util.EnumFace;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockLever.class)
public abstract class BlockLeverMixin {

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
        return (dir == EnumFace.BOTTOM && isBlockSolidOnSide(world, x, y + 1, z, EnumFace.BOTTOM)) ||
               (dir == EnumFace.TOP    && isBlockSolidOnSide(world, x, y - 1, z, EnumFace.TOP)) ||
               (dir == EnumFace.NORTH  && isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH)) ||
               (dir == EnumFace.SOUTH  && isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH)) ||
               (dir == EnumFace.WEST   && isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST)) ||
               (dir == EnumFace.EAST   && isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST));
    }

    @Unique
    public boolean canPlaceBlockAt(World world, int x, int y, int z) {
        return isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST) ||
               isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST) ||
               isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH) ||
               isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH) ||
               isBlockSolidOnSide(world, x, y - 1, z, EnumFace.TOP) ||
               isBlockSolidOnSide(world, x, y + 1, z, EnumFace.BOTTOM);
    }

    @Unique
    public int onBlockPlaced(World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ, int meta) {
        int k1 = meta & 7;
        byte b0 = -1;

        if (side == 0 && isBlockSolidOnSide(world, x, y + 1, z, EnumFace.BOTTOM)) b0 = 0;
        if (side == 1 && isBlockSolidOnSide(world, x, y - 1, z, EnumFace.TOP)) b0 = 5;
        if (side == 2 && isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH)) b0 = 4;
        if (side == 3 && isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH)) b0 = 3;
        if (side == 4 && isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST)) b0 = 2;
        if (side == 5 && isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST)) b0 = 1;

        if (b0 == -1 && isBlockSolidOnSide(world, x, y - 1, z, EnumFace.TOP)) b0 = 5;
        if (b0 == -1 && isBlockSolidOnSide(world, x, y + 1, z, EnumFace.BOTTOM)) b0 = 0;

        return b0 | (k1 & 8);
    }

    @Unique
    public boolean onNotLegal(World world, int x, int y, int z, int metadata) { return false; }

    @Unique
    public boolean canBlockStay(World world, int x, int y, int z) {
        int i1 = world.getBlockMetadata(x, y, z) & 7;
        boolean flag = false;

        if (!isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST) && i1 == 1) flag = true;
        if (!isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST) && i1 == 2) flag = true;
        if (!isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH) && i1 == 3) flag = true;
        if (!isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH) && i1 == 4) flag = true;
        if (!isBlockSolidOnSide(world, x, y - 1, z, EnumFace.TOP) && i1 == 5) flag = true;
        if (!isBlockSolidOnSide(world, x, y - 1, z, EnumFace.TOP) && i1 == 6) flag = true;
        if (!isBlockSolidOnSide(world, x, y + 1, z, EnumFace.BOTTOM) && i1 == 0) flag = true;
        if (!isBlockSolidOnSide(world, x, y + 1, z, EnumFace.BOTTOM) && i1 == 7) flag = true;

        if (flag) {
            this.onNotLegal(world, x, y, z, i1);
            return false;
        }
        return true;
    }
}

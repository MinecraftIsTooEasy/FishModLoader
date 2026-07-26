package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockButton;
import net.minecraft.util.EnumFace;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockButton.class)
public abstract class BlockButtonMixin {

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

    @Shadow
    public void dropBlockAsItem(World world, int x, int y, int z, int meta, int fortune) {}

    @Unique
    public boolean canBlockStay(World world, int x, int y, int z) {
        int meta = world.getBlockMetadata(x, y, z);
        int i1 = meta & 7;
        boolean flag = false;

        if (!isBlockSolidOnSide(world, x - 1, y, z, EnumFace.EAST) && i1 == 1) flag = true;
        if (!isBlockSolidOnSide(world, x + 1, y, z, EnumFace.WEST) && i1 == 2) flag = true;
        if (!isBlockSolidOnSide(world, x, y, z - 1, EnumFace.SOUTH) && i1 == 3) flag = true;
        if (!isBlockSolidOnSide(world, x, y, z + 1, EnumFace.NORTH) && i1 == 4) flag = true;

        if (flag) {
            this.dropBlockAsItem(world, x, y, z, meta, 0);
            world.setBlockToAir(x, y, z);
            return false;
        }
        return true;
    }
}

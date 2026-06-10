package net.minecraftforge.common;

import net.minecraft.block.*;
import net.minecraft.world.World;

import static net.minecraftforge.common.ForgeDirection.*;

/**
 * Forge 1.6.4 RotationHelper, full implementation.
 *
 * <p>All four target classes (BlockChest, BlockPistonBase, BlockDispenser,
 * BlockDropper) exist on stock 1.6.4 MITE, so this can be a faithful port
 * of the upstream Forge code rather than a stub.
 */
public class RotationHelper {

    private static final ForgeDirection[] UP_DOWN_AXES = new ForgeDirection[]{UP, DOWN};

    public static ForgeDirection[] getValidVanillaBlockRotations(Block block) {
        return block instanceof BlockChest ? UP_DOWN_AXES : VALID_DIRECTIONS;
    }

    public static boolean rotateVanillaBlock(Block block, World worldObj, int x, int y, int z, ForgeDirection axis) {
        if (worldObj.isRemote) {
            return false;
        }
        if (block instanceof BlockChest && (axis == UP || axis == DOWN)) {
            return rotateBlock(worldObj, x, y, z, axis, 0x7);
        }
        if (block instanceof BlockPistonBase
                || block instanceof BlockDropper
                || block instanceof BlockDispenser) {
            return rotateBlock(worldObj, x, y, z, axis, 0x7);
        }
        return false;
    }

    public static boolean rotateBlock(World worldObj, int x, int y, int z, ForgeDirection axis, int mask) {
        int rotMeta = worldObj.getBlockMetadata(x, y, z);
        int masked = rotMeta & ~mask;
        ForgeDirection orientation = ForgeDirection.getOrientation(rotMeta & mask);
        ForgeDirection rotated = orientation.getRotation(axis);
        worldObj.setBlockMetadataWithNotify(x, y, z, rotated.ordinal() & mask | masked, 3);
        return true;
    }
}

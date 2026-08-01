package net.xiaoyu233.fml.modfixer;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;

/** Loader-owned target for the removed vanilla 1.6.4 BlockSand.canFallBelow API. */
public final class LegacyForgeBlockSandBridge {
    private LegacyForgeBlockSandBridge() {}

    public static boolean canFallBelow(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == null) return true;
        Material material = block.blockMaterial;
        return material == Material.water || material == Material.lava;
    }
}

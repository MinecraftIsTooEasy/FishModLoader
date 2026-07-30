package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockPortal;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Random;

@Mixin(BlockPortal.class)
public class BlockPortalMixin {
    @Overwrite
    public boolean tryToCreatePortal(World world, int x, int y, int z) {
        int l = world.getBlockId(x, y, z);
        if (l == 0) {
            byte b0 = 1;
            byte b1 = 0;

            for (int i1 = -1; i1 <= 1; ++i1) {
                for (int j1 = -1; j1 <= 1; ++j1) {
                    if (world.isAirBlock(x + b0 * i1, y + j1, z + b1 * i1)) {
                        if (world.getBlockId(x + b0 * i1 + b0, y + j1, z + b1 * i1 + b1) == Block.obsidian.blockID) {
                            if (world.getBlockId(x + b0 * i1 - b0, y + j1, z + b1 * i1 - b1) == Block.obsidian.blockID) {
                                boolean flag = true;

                                for (int k1 = -1; k1 <= 1; ++k1) {
                                    for (int l1 = -1; l1 <= 1; ++l1) {
                                        if (k1 != 0 || l1 != 0) {
                                            int i2 = world.getBlockId(x + b0 * k1, y + l1, z + b1 * k1);
                                            boolean isAirBlock = world.isAirBlock(x + b0 * k1, y + l1, z + b1 * k1);

                                            if (flag) {
                                                if (i2 != Block.obsidian.blockID && !isAirBlock) {
                                                    flag = false;
                                                }
                                            } else if (!isAirBlock && i2 != Block.fire.blockID) {
                                                flag = false;
                                            }
                                        }
                                    }
                                }

                                if (flag) {
                                    for (int l2 = -1; l2 <= 1; ++l2) {
                                        for (int i3 = -1; i3 <= 1; ++i3) {
                                            world.setBlock(x + b0 * l2, y + i3, z + b1 * l2, Block.portal.blockID, 0, 2);
                                        }
                                    }
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}

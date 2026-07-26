package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockOre;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(BlockOre.class)
public abstract class BlockOreMixin {
    @Shadow
    public abstract int idDropped(int meta, Random rand, int fortune);

    @Unique
    public int getExpDrop(World world, int metadata, int fortune) {
        Block self = (Block) (Object) this;
        if (this.idDropped(metadata, world.rand, fortune) != self.blockID) {
            int j1 = 0;

            if (self.blockID == Block.oreCoal.blockID) {
                j1 = MathHelper.getRandomIntegerInRange(world.rand, 0, 2);
            } else if (self.blockID == Block.oreDiamond.blockID) {
                j1 = MathHelper.getRandomIntegerInRange(world.rand, 3, 7);
            } else if (self.blockID == Block.oreLapis.blockID) {
                j1 = MathHelper.getRandomIntegerInRange(world.rand, 2, 5);
            }

            return j1;
        }
        return 0;
    }
}

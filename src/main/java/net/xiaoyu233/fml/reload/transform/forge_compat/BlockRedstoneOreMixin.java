package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(BlockRedstoneOre.class)
public abstract class BlockRedstoneOreMixin {
    @Shadow
    public abstract int idDropped(int meta, Random rand, int fortune);

    @Unique
    public int getExpDrop(World world, int metadata, int fortune) {
        Block self = (Block) (Object) this;
        if (this.idDropped(metadata, world.rand, fortune) != self.blockID) {
            return 1 + world.rand.nextInt(5);
        }
        return 0;
    }
}

package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockRedstoneOre.class)
public abstract class BlockRedstoneOreMixin {
    @Unique
    public int getExpDrop(World world, int metadata, int fortune) {
        return 0; // MITE handles XP via Block.dropXpOnBlockBreak; this hook is inert
    }
}

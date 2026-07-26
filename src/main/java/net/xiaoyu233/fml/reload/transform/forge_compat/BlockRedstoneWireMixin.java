package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BlockRedstoneWire.class)
public class BlockRedstoneWireMixin {
    /**
     * @reason Use canConnectRedstone instead of canProvidePower for Forge compat
     */
    @Overwrite
    public boolean canProvidePower() {
        return true;
    }
}

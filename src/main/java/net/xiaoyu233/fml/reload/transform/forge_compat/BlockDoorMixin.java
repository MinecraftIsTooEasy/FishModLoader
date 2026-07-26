package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.material.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BlockDoor.class)
public class BlockDoorMixin {
    /**
     * @reason Allow items to interact with iron doors (return false instead of true for iron)
     */
    @Overwrite
    public boolean isOpaqueCube() {
        return false;
    }
}

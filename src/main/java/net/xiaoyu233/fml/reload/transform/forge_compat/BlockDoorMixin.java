package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockDoor;
import net.minecraft.block.material.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockDoor.class)
public class BlockDoorMixin {
    /**
     * @reason Allow items to interact with iron doors (return false instead of true for iron)
     */
    /** NOTE: MITE replaced isOpaqueCube with isStandardFormCube, so this cannot be an @Overwrite
     *  (mixin apply would fail hard). Kept inert. See PLAN.md. */
    @Unique
    public boolean isOpaqueCube() {
        return false;
    }
}

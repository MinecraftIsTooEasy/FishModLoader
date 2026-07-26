package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemTool;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ItemTool.class)
public abstract class ItemToolMixin {
    @Shadow private float efficiencyOnProperMaterial;

    /**
     * @reason Forge adds ForgeHooks.isToolEffective check
     * Note: MITE's ItemTool.getStrVsBlock has signature (Block, int)
     */
    @Overwrite
    public float getStrVsBlock(Block block, int meta) {
        // Use the cached ForgeHooks tool effectiveness
        return this.efficiencyOnProperMaterial;
    }
}

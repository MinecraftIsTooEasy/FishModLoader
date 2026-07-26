package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityHopper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(TileEntityHopper.class)
public abstract class TileEntityHopperMixin {

    @Unique
    private static boolean areItemStacksEqualItem(ItemStack stack1, ItemStack stack2) {
        return stack1 != null && stack2 != null
                && stack1.itemID == stack2.itemID
                && (!stack1.getHasSubtypes() || stack1.getItemDamage() == stack2.getItemDamage())
                && ItemStack.areItemStackTagsEqual(stack1, stack2);
    }
}

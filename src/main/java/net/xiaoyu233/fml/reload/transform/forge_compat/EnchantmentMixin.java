package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Enchantment.class)
public class EnchantmentMixin {

    @Unique
    public boolean canApplyAtEnchantingTable(ItemStack stack) {
        return true;
    }

    @Unique
    private static void addToBookList(Enchantment enchantment) {
        // No-op: ObjectArrays.concat returns a new array, doesn't modify in place
    }

    @Unique
    public boolean isAllowedOnBooks() {
        return true;
    }
}

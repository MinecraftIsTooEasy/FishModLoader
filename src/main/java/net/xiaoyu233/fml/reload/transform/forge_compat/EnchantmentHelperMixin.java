package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EnchantmentHelper.class)
public class EnchantmentHelperMixin {

    // The patch modifies the buildEnchantmentList method to use
    // enchantment.canApplyAtEnchantingTable() and isAllowedOnBooks()
}

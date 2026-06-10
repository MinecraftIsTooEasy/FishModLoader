package net.xiaoyu233.fml.reload.transform.id_extend;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityItem.class)
public class EntityItemMixin {
    @ModifyExpressionValue(method = {"isImmuneToExplosion"}, at = @At(value = "FIELD", target = "Lnet/minecraft/item/ItemStack;itemID:I"))
    private static int injectedExtendId(int value, @Local ItemStack stack) {
        if (stack.getItem() instanceof ItemBlock) return 0;
        return value;
    }

    @ModifyExpressionValue(method = {"handleExplosion"}, at = @At(value = "FIELD", target = "Lnet/minecraft/item/Item;itemID:I", ordinal = 0))
    private static int injectedExtendId(int value, @Local Item item) {
        if (item instanceof ItemBlock) return 0;
        return value;
    }
}

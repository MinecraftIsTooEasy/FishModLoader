package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(PotionEffect.class)
public class PotionEffectMixin {

    @Unique
    private List<ItemStack> curativeItems;

    @Unique
    public List<ItemStack> getCurativeItems() {
        if (curativeItems == null) {
            curativeItems = new java.util.ArrayList<ItemStack>();
            curativeItems.add(new ItemStack(Item.bucketIronMilk));
        }
        return this.curativeItems;
    }

    @Unique
    public boolean isCurativeItem(ItemStack stack) {
        for (ItemStack curativeItem : getCurativeItems()) {
            if (curativeItem.itemID == stack.itemID && curativeItem.getItemDamage() == stack.getItemDamage()) {
                return true;
            }
        }
        return false;
    }

    @Unique
    public void setCurativeItems(List<ItemStack> curativeItems) {
        this.curativeItems = curativeItems;
    }

    @Unique
    public void addCurativeItem(ItemStack stack) {
        if (!isCurativeItem(stack)) {
            getCurativeItems().add(stack);
        }
    }
}

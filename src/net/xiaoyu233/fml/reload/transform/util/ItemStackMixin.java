package net.xiaoyu233.fml.reload.transform.util;

import net.minecraft.Item;
import net.minecraft.ItemStack;
import net.xiaoyu233.fml.api.block.IBlock;
import net.xiaoyu233.fml.api.item.IItem;
import net.xiaoyu233.fml.api.item.IItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ItemStack.class)
public class ItemStackMixin implements IItemStack {
    @Shadow public int itemID;

    @Override
    public String getNamespace() {
        for (Item item : Item.itemsList) {
            ItemStack stack = new ItemStack(item);
            if (stack.isBlock() && stack.itemID == this.itemID) return ((IBlock) stack.getItem().getAsItemBlock().getBlock()).getNamespace();
            else if (stack.itemID == this.itemID) return ((IItem) item).getNamespace();
        }
        return "Minecraft";
    }
}

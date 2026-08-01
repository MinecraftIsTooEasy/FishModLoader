package net.xiaoyu233.fml.modfixer;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Narrow target used only by the remapped legacy Lucky Block ordinary-item drop path. */
public final class LegacyLuckyItemDropBridge {
    private LegacyLuckyItemDropBridge() {}

    /**
     * Returns no entity when a legacy numeric id has no MITE item registration.
     * The original stack is passed through unchanged, preserving its count, subtype and NBT.
     */
    public static EntityItem createEntityItem(World world, double x, double y, double z, ItemStack stack) {
        if (stack == null || Item.getItem(stack.itemID) == null || world == null) return null;
        return new EntityItem(world, x, y, z, stack);
    }
}

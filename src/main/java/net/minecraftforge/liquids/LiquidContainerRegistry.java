package net.minecraftforge.liquids;

import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * STUB. The original LiquidContainerRegistry references vanilla bucket
 * Item fields (bucketEmpty, bucketWater, bucketLava) that MITE renamed to
 * bucketIronEmpty/bucketIronWater/bucketIronLava and gated to specific
 * material tiers. The whole bucket-as-liquid-container abstraction needs
 * stage 5 work; for now we expose the public registration API as no-ops.
 */
public class LiquidContainerRegistry {

    public static final int BUCKET_VOLUME = 1000;
    public static final ItemStack EMPTY_BUCKET = null;

    private static final List<LiquidContainerData> registry = new ArrayList<>();

    public static void registerLiquid(LiquidContainerData data) {
        if (data != null) registry.add(data);
    }

    public static boolean isLiquid(ItemStack stack) { return false; }
    public static boolean isEmptyContainer(ItemStack stack) { return false; }
    public static boolean isFilledContainer(ItemStack stack) { return false; }
    public static boolean isContainer(ItemStack stack) { return false; }
    public static boolean isBucket(ItemStack stack) { return false; }

    public static LiquidStack getLiquidForFilledItem(ItemStack stack) { return null; }
    public static ItemStack fillLiquidContainer(LiquidStack liquid, ItemStack container) { return null; }
    public static LiquidContainerData[] getRegisteredLiquidContainerData() {
        return registry.toArray(new LiquidContainerData[0]);
    }
}

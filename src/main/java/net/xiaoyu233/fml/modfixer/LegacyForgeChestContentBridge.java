package net.xiaoyu233.fml.modfixer;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.WeightedRandom;
import net.minecraft.util.WeightedRandomChestContent;
import net.minecraftforge.common.ChestGenHooks;

import java.util.Random;

/** Loader-owned target for the legacy vanilla four-argument chest population API. */
public final class LegacyForgeChestContentBridge {
    private LegacyForgeChestContentBridge() {}

    public static void generateChestContents(Random random, WeightedRandomChestContent[] contents,
                                             IInventory inventory, int rolls) {
        if (random == null || contents == null || contents.length == 0 || inventory == null || rolls <= 0) return;
        int inventorySize = inventory.getSizeInventory();
        if (inventorySize <= 0) return;

        for (int roll = 0; roll < rolls; roll++) {
            WeightedRandomChestContent selected = (WeightedRandomChestContent) WeightedRandom.getRandomItem(random, contents);
            if (selected == null) continue;
            ItemStack[] stacks = ChestGenHooks.generateStacks(random, selected.theItemId,
                    selected.min_quantity, selected.max_quantity);
            if (stacks == null || stacks.length == 0) continue;
            for (ItemStack stack : stacks) {
                inventory.setInventorySlotContents(random.nextInt(inventorySize), stack);
            }
        }
    }
}

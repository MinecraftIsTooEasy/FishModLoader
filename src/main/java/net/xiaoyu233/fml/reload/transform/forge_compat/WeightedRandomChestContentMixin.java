package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.WeightedRandomChestContent;
import net.minecraft.util.WeightedRandom;
import net.minecraftforge.common.ChestGenHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(WeightedRandomChestContent.class)
public class WeightedRandomChestContentMixin {

    @Shadow
    @Final
    private ItemStack theItemId;

    @Shadow
    private int min_quantity;

    @Shadow
    private int max_quantity;

    /** Legacy vanilla four-argument chest population API used by Forge mods. */
    @Unique
    private static void func_76293_a(Random random, WeightedRandomChestContent[] contents, IInventory inventory, int rolls) {
        for (int roll = 0; roll < rolls; roll++) {
            WeightedRandomChestContent selected = (WeightedRandomChestContent) WeightedRandom.getRandomItem(random, contents);
            WeightedRandomChestContentMixin access = (WeightedRandomChestContentMixin) (Object) selected;
            for (ItemStack stack : access.generateChestContent(random, inventory)) {
                inventory.setInventorySlotContents(random.nextInt(inventory.getSizeInventory()), stack);
            }
        }
    }

    @Unique
    protected ItemStack[] generateChestContent(Random random, IInventory newInventory) {
        return ChestGenHooks.generateStacks(random, theItemId,
                min_quantity, max_quantity);
    }
}

package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.WeightedRandomChestContent;
import net.minecraftforge.common.ChestGenHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

@Mixin(WeightedRandomChestContent.class)
public class WeightedRandomChestContentMixin {

    @Shadow
    private ItemStack theItemId;

    @Shadow
    private int min_quantity;

    @Shadow
    private int max_quantity;

    @Unique
    protected ItemStack[] generateChestContent(Random random, IInventory newInventory) {
        return ChestGenHooks.generateStacks(random, theItemId,
                min_quantity, max_quantity);
    }
}

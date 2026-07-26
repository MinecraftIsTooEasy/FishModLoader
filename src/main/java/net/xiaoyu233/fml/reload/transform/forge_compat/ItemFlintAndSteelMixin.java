package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.ItemFlintAndSteel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Random;

@Mixin(ItemFlintAndSteel.class)
public abstract class ItemFlintAndSteelMixin {

    @Shadow
    private Random itemRand;
}

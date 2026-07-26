package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.ItemShears;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Random;

@Mixin(ItemShears.class)
public abstract class ItemShearsMixin {

    @Shadow
    private Random itemRand;
}

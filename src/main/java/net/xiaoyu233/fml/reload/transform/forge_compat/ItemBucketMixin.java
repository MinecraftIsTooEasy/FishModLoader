package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.ItemBucket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ItemBucket.class)
public abstract class ItemBucketMixin {

    @Shadow
    private int isFull;
}

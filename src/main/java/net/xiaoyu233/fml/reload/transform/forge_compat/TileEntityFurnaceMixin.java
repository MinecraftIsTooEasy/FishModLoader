package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityFurnace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TileEntityFurnace.class)
public abstract class TileEntityFurnaceMixin {

    @Shadow
    private ItemStack[] furnaceItemStacks;

    @Shadow
    public abstract int getInventoryStackLimit();
}

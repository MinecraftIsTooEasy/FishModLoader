package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin to SlotCrafting for Forge compatibility patches.
 */
@Mixin(SlotCrafting.class)
public abstract class SlotCraftingMixin {

    @Shadow
    private EntityPlayer thePlayer;

    /**
     * Intercepts the addItemStackToInventory call inside the crafting result loop.
     * Fires PlayerDestroyItemEvent when a damageable container item exceeds max damage.
     */
    @Redirect(method = "onPickupFromSlot(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/item/ItemStack;)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/entity/player/InventoryPlayer;addItemStackToInventory(Lnet/minecraft/item/ItemStack;)Z"))
    private boolean fmlForgeOnPickupAddToInventory(InventoryPlayer inventory, ItemStack itemstack2) {
        if (itemstack2 != null && itemstack2.isItemStackDamageable() && itemstack2.getItemDamage() > itemstack2.getMaxDamage()) {
            MinecraftForge.EVENT_BUS.post(new PlayerDestroyItemEvent(thePlayer, itemstack2));
            return true;
        }
        return inventory.addItemStackToInventory(itemstack2);
    }

    /**
     * Provides a bridge for Forge code that calls getBackgroundIconTexture() on SlotCrafting.
     */
    @Unique
    public ResourceLocation getBackgroundIconTexture() {
        return null;
    }
}

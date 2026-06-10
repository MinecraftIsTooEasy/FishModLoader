package net.xiaoyu233.fml.reload.transform.fix.fix_creative_inv;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.inventory.Container;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiContainerCreative.class)
public abstract class GuiCreativeMixin extends InventoryEffectRenderer {
    public GuiCreativeMixin(Container par1Container) {
        super(par1Container);
    }

    @Inject(method = "handleMouseClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/inventory/Container;slotClick(IIIZLnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;", shift = At.Shift.AFTER, ordinal = 0))
    private void injectUnlock(Slot par1Slot, int par2, int par3, int par4, CallbackInfo callbackInfo, @Share(value = "clickedStack") LocalRef<ItemStack> stack){
        this.mc.thePlayer.inventoryContainer.unlockAllSlots();
        this.mc.thePlayer.inventoryContainer.unlockNextTick();
    }

    @Inject(method = "updateScreen", at = @At("RETURN"))
    public void updateUnlock(CallbackInfo callbackInfo) {
        this.mc.thePlayer.inventoryContainer.unlockAllSlots();
    }
}

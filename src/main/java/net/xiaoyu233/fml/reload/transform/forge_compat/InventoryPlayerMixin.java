package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to InventoryPlayer for Forge compatibility patches.
 * <p>
 * Implements Forge event hooking for inventory change events and
 * provides compatibility hooks that Forge mods may expect to call
 * on InventoryPlayer instances.
 */
@Mixin(InventoryPlayer.class)
public abstract class InventoryPlayerMixin {

    @Shadow
    public ItemStack[] mainInventory;
    @Shadow
    public ItemStack[] armorInventory;
    @Shadow
    public int currentItem;
    @Shadow
    public EntityPlayer player;

    @Shadow
    public abstract void setCurrentItem(int itemID, int meta, boolean searchBackward, boolean hotbar);

    @Shadow
    public abstract void onInventoryChanged();

    /**
     * Injects at the beginning of {@code onInventoryChanged()} to fire
     * a Forge event notifying listeners that the player's inventory has
     * been modified. This is separate from the individual slot-change
     * firing in {@code setInventorySlotContents}.
     * <p>
     * Forge uses this hook to keep internal caches in sync (e.g.,
     * armor modifiers, potion effect calculations) when the inventory
     * changes through any path.
     */
    @Inject(method = "onInventoryChanged()V",
            at = @At("TAIL"))
    private void fmlForgeOnInventoryChanged(CallbackInfo ci) {
        // Fire a generic inventory changed event for Forge mods that listen
        // for inventory state changes beyond individual slot mutations.
        net.minecraftforge.event.entity.player.PlayerEvent event =
                new net.minecraftforge.event.entity.player.PlayerEvent(this.player);
        MinecraftForge.EVENT_BUS.post(event);

        // Call onArmorTickUpdate for all equipped armor items (Forge hook)
        for (int i = 0; i < this.armorInventory.length; i++) {
            if (this.armorInventory[i] != null) {
                // MITE Item doesn't have onArmorTickUpdate - skip
            }
        }
    }

    /**
     * Fires an event when a slot in the main inventory or armor inventory
     * is explicitly set. This fires at the TAIL of
     * {@code setInventorySlotContents} after the value has been written
     * and {@code onInventoryChanged()} has already been called, ensuring
     * all listeners see the most up-to-date state.
     * <p>
     * Part of Forge's inventory-change notification contract for mods
     * that monitor specific slot types (e.g., baubles, backpack mods).
     */
    @Inject(method = "setInventorySlotContents(ILnet/minecraft/item/ItemStack;)V",
            at = @At("TAIL"))
    private void fmlForgeOnSetInventorySlotContents(int slot, ItemStack stack, CallbackInfo ci) {
        // Notify forge event bus of the slot change
        net.minecraftforge.event.entity.player.PlayerEvent event =
                new net.minecraftforge.event.entity.player.PlayerEvent(this.player);
        MinecraftForge.EVENT_BUS.post(event);
    }

    // ========== Forge-compat @Unique methods ==========

    /**
     * Sets the current item in the player's hand by searching the inventory
     * for an item matching the given item ID. If found, the hotbar selection
     * is updated.
     * <p>
     * Note: The vanilla method {@code setCurrentItem(int, int, boolean, boolean)}
     * already exists on InventoryPlayer. This @Unique helper provides a
     * Forge-compatible variant that searches for a specific item ID.
     *
     * @param itemID   The item ID to search for
     * @param meta     The metadata value to match, or -1 for any
     * @param searchBackward Whether to search from the end of the inventory
     * @param hotbar   Whether to also check the hotbar slots
     */
    @Unique
    public void forgeSetCurrentItem(int itemID, int meta, boolean searchBackward, boolean hotbar) {
        // Delegate to the vanilla method via @Shadow
        this.setCurrentItem(itemID, meta, searchBackward, hotbar);
    }

    /**
     * Checks whether the itemstack in the given inventory slot is damageable
     * and has exceeded its maximum damage. If so, fires a
     * {@link PlayerDestroyItemEvent} and clears the slot.
     * <p>
     * This emulates the Forge behavior of destroying over-damaged items
     * that may occur through external item stack mutations.
     *
     * @param slot The inventory slot to check
     */
    @Unique
    private void fmlCheckAndDestroyItem(int slot) {
        if (slot < 0 || slot >= this.mainInventory.length) return;
        ItemStack stack = this.mainInventory[slot];
        if (stack != null && stack.isItemStackDamageable() && stack.getItemDamage() > stack.getMaxDamage()) {
            MinecraftForge.EVENT_BUS.post(new PlayerDestroyItemEvent(this.player, stack));
            this.mainInventory[slot] = null;
            this.onInventoryChanged();
        }
    }

    /**
     * Fires a destroy-item check on the player's current held item and clears
     * it from the inventory if it is damaged beyond its maximum durability.
     * Forge mods call this after taking damage or using items to detect when
     * a tool or weapon breaks.
     */
    @Unique
    public void destroyCurrentItem() {
        this.fmlCheckAndDestroyItem(this.currentItem);
    }
}

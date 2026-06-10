package net.xiaoyu233.fml.reload.transform.fix.fix_creative_inv;

import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.gui.inventory.SlotCreativeInventory")
public class SlotCreativeInvMixin extends Slot {

    public SlotCreativeInvMixin(IInventory inventory, int slot_index, int display_x, int display_y) {
        super(inventory, slot_index, display_x, display_y);
    }

    @Override
    public void setLocked(boolean locked) {

    }
}

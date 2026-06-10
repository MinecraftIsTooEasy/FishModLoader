package net.xiaoyu233.fml.reload.transform.fix.fix_creative_inv;

import net.minecraft.inventory.Container;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.gui.inventory.ContainerCreative")
public abstract class ContainerCreativeMixin extends Container {

    public ContainerCreativeMixin(EntityPlayer player) {
        super(player);
    }

    @Override
    public void lockSlotsThatChanged() {

    }
}

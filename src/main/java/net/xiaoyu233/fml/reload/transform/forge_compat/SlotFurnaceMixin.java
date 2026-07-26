package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.SlotFurnace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SlotFurnace.class)
public abstract class SlotFurnaceMixin {

    @Shadow
    private EntityPlayer thePlayer;

    @Shadow
    private int field_75228_b;
}

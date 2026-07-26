package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerEnchantment;
import net.minecraft.inventory.IInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Random;

@Mixin(ContainerEnchantment.class)
public abstract class ContainerEnchantmentMixin extends Container {

    @Shadow
    private int posX;
    @Shadow
    private int posY;
    @Shadow
    private int posZ;

    @Shadow
    private Random rand;

    @Shadow
    private int[] enchantLevels;

    @Shadow
    private IInventory tableInventory;

    public ContainerEnchantmentMixin(EntityPlayer player) {
        super(player);
    }
}

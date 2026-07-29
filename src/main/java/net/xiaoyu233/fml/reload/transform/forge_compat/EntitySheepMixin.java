package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Random;

@Mixin(EntitySheep.class)
public abstract class EntitySheepMixin {
    @Shadow public abstract boolean getSheared();
    @Shadow public abstract void setSheared(boolean par1);
    @Shadow public abstract int getFleeceColor();

    @Unique
    public boolean isShearable(ItemStack item, World world, int X, int Y, int Z) {
        return !getSheared();
    }

    @Unique
    public ArrayList<ItemStack> onSheared(ItemStack item, World world, int X, int Y, int Z, int fortune) {
        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();
        setSheared(true);
        int i = 1 + ((EntitySheep)(Object)this).rand.nextInt(3);
        for (int j = 0; j < i; j++) {
            ret.add(new ItemStack(Block.cloth.blockID, 1, getFleeceColor()));
        }
        ((EntitySheep)(Object)this).worldObj.playSoundAtEntity((Entity)(Object)this, "mob.sheep.shear", 1.0F, 1.0F);
        return ret;
    }
}

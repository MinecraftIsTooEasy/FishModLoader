package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityMooshroom;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumParticle;
import net.minecraft.world.World;
import net.minecraftforge.common.IShearable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;

@Mixin(EntityMooshroom.class)
public abstract class EntityMooshroomMixin implements IShearable {

    @Override
    public boolean isShearable(ItemStack item, World world, int X, int Y, int Z) {
        return ((EntityMooshroom)(Object)this).getGrowingAge() >= 0;
    }

    @Override
    public ArrayList<ItemStack> onSheared(ItemStack item, World world, int X, int Y, int Z, int fortune) {
        EntityMooshroom self = (EntityMooshroom)(Object)this;
        self.setDead();
        EntityCow entitycow = new EntityCow(self.worldObj);
        // Copy basic entity properties
        self.worldObj.spawnEntityInWorld(entitycow);
        self.worldObj.spawnParticle(EnumParticle.hugeexplosion, self.posX, self.posY + (double)(self.height / 2.0F), self.posZ, 0.0D, 0.0D, 0.0D);

        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();
        for (int x = 0; x < 5; x++) {
            ret.add(new ItemStack(Block.mushroomRed));
        }
        return ret;
    }
}

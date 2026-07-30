package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityMooshroom;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumParticle;
import net.minecraft.world.World;
import net.minecraftforge.common.IShearable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;

@Mixin(EntityMooshroom.class)
public abstract class EntityMooshroomMixin implements IShearable {

    // worldObj/posX/posY/posZ/height are declared on Entity and getGrowingAge()
    // on EntityAgeable; @Shadow resolves inherited members too.
    @Shadow public World worldObj;
    @Shadow public double posX;
    @Shadow public double posY;
    @Shadow public double posZ;
    @Shadow public float height;

    @Shadow public abstract int getGrowingAge();

    @Shadow public abstract void setDead();

    @Override
    public boolean isShearable(ItemStack item, World world, int X, int Y, int Z) {
        return this.getGrowingAge() >= 0;
    }

    @Override
    public ArrayList<ItemStack> onSheared(ItemStack item, World world, int X, int Y, int Z, int fortune) {
        this.setDead();
        EntityCow entitycow = new EntityCow(this.worldObj);
        // Copy basic entity properties
        this.worldObj.spawnEntityInWorld(entitycow);
        this.worldObj.spawnParticle(EnumParticle.hugeexplosion, this.posX, this.posY + (double)(this.height / 2.0F), this.posZ, 0.0D, 0.0D, 0.0D);

        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();
        for (int x = 0; x < 5; x++) {
            ret.add(new ItemStack(Block.mushroomRed));
        }
        return ret;
    }
}

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
    @Shadow public abstract int getGrowingAge();
    @Shadow public abstract void setDead();
    @Shadow public World worldObj;
    @Shadow public double posX;
    @Shadow public double posY;
    @Shadow public double posZ;
    @Shadow public float rotationYaw;
    @Shadow public float rotationPitch;
    @Shadow public float renderYawOffset;
    @Shadow public float height;
    @Shadow public java.util.Random rand;

    @Shadow public abstract void setLocationAndAngles(double x, double y, double z, float yaw, float pitch);
    @Shadow public abstract float getHealth();
    @Shadow public abstract void setHealth(float health);

    @Override
    public boolean isShearable(ItemStack item, World world, int X, int Y, int Z) {
        return getGrowingAge() >= 0;
    }

    @Override
    public ArrayList<ItemStack> onSheared(ItemStack item, World world, int X, int Y, int Z, int fortune) {
        setDead();
        EntityCow entitycow = new EntityCow(worldObj);
        // Copy basic entity properties
        worldObj.spawnEntityInWorld(entitycow);
        worldObj.spawnParticle(EnumParticle.hugeexplosion, posX, posY + (double)(height / 2.0F), posZ, 0.0D, 0.0D, 0.0D);

        ArrayList<ItemStack> ret = new ArrayList<ItemStack>();
        for (int x = 0; x < 5; x++) {
            ret.add(new ItemStack(Block.mushroomRed));
        }
        return ret;
    }
}

package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.stats.AchievementList;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.Event;
import net.minecraftforge.event.entity.item.ItemExpireEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

@Mixin(EntityItem.class)
public abstract class EntityItemMixin {
    @Shadow public int age;
    @Shadow private int health;
    @Shadow public float hoverStart;
    @Shadow public int delayBeforeCanPickup;
    @Shadow public Random rand;
    @Shadow public World worldObj;

    @Shadow public abstract ItemStack getEntityItem();
    @Shadow public abstract void setDead();
    @Shadow protected void playSound(String par1Str, float par2, float par3) {}

    @Unique
    public int lifespan = 6000;

    @Inject(method = "<init>(Lnet/minecraft/world/World;DDDLnet/minecraft/item/ItemStack;)V", at = @At("RETURN"))
    private void fmlForgeOnInitWithStack(CallbackInfo ci) {
        ItemStack stack = this.getEntityItem();
        if (stack != null && stack.getItem() != null) {
            this.lifespan = 6000;
        }
    }

    @Inject(method = "onUpdate", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnEntityItemUpdate(CallbackInfo ci) {
        ItemStack stack = ((EntityItem)(Object)this).getDataWatcher().getWatchableObjectItemStack(10);
        if (stack != null && stack.getItem() != null) {
            // Forge hook: onEntityItemUpdate
        }
    }

    @Inject(method = "onUpdate", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/item/EntityItem;age:I", opcode = 181, ordinal = 1))
    private void fmlForgeCheckLifespan(CallbackInfo ci) {
        ItemStack item = ((EntityItem)(Object)this).getDataWatcher().getWatchableObjectItemStack(10);
        if (!this.worldObj.isRemote && this.age >= this.lifespan) {
            if (item != null) {
                ItemExpireEvent event = new ItemExpireEvent((EntityItem)(Object)this, 6000);
                if (MinecraftForge.EVENT_BUS.post(event)) {
                    this.lifespan += event.extraLife;
                } else {
                    this.setDead();
                }
            } else {
                this.setDead();
            }
        }
    }

    @Inject(method = "onUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/item/EntityItem;setDead()V", ordinal = 0))
    private void fmlForgeCheckStackSize(CallbackInfo ci) {
        ItemStack item = ((EntityItem)(Object)this).getDataWatcher().getWatchableObjectItemStack(10);
        if (item != null && item.stackSize <= 0) {
            this.setDead();
        }
    }

    @Inject(method = "writeEntityToNBT", at = @At("RETURN"))
    private void fmlForgeWriteLifespan(NBTTagCompound tag, CallbackInfo ci) {
        tag.setInteger("Lifespan", this.lifespan);
    }

    @Inject(method = "readEntityFromNBT", at = @At("RETURN"))
    private void fmlForgeReadLifespan(NBTTagCompound tag, CallbackInfo ci) {
        if (tag.hasKey("Lifespan")) {
            this.lifespan = tag.getInteger("Lifespan");
        }
    }

    /**
     * @reason Forge adds EntityItemPickupEvent and lifespan checks to onCollideWithPlayer
     */
    @Overwrite
    public void onCollideWithPlayer(EntityPlayer par1EntityPlayer) {
        if (!this.worldObj.isRemote) {
            if (this.delayBeforeCanPickup > 0) {
                return;
            }

            EntityItemPickupEvent event = new EntityItemPickupEvent(par1EntityPlayer, (EntityItem)(Object)this);
            if (MinecraftForge.EVENT_BUS.post(event)) {
                return;
            }

            ItemStack itemstack = this.getEntityItem();
            int i = itemstack.stackSize;

            if (this.delayBeforeCanPickup <= 0 && (event.getResult() == Event.Result.ALLOW || i <= 0 || par1EntityPlayer.inventory.addItemStackToInventory(itemstack))) {
                if (itemstack.itemID == Block.wood.blockID) {
                    par1EntityPlayer.triggerAchievement(AchievementList.mineWood);
                }

                if (itemstack.itemID == Item.leather.itemID) {
                    par1EntityPlayer.triggerAchievement(AchievementList.killCow);
                }

                if (itemstack.itemID == Item.diamond.itemID) {
                    par1EntityPlayer.triggerAchievement(AchievementList.diamonds);
                }

                if (itemstack.itemID == Item.ingotIron.itemID) {
                    par1EntityPlayer.triggerAchievement(AchievementList.acquireIron);
                }

                this.playSound("random.pop", 0.2F, ((this.rand.nextFloat() - this.rand.nextFloat()) * 0.7F + 1.0F) * 2.0F);
                par1EntityPlayer.onItemPickup((EntityItem)(Object)this, i);

                if (itemstack.stackSize <= 0) {
                    this.setDead();
                }
            }
        }
    }
}

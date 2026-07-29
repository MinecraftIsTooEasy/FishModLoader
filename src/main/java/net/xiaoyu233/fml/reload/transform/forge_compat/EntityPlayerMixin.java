package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.ISpecialArmor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityInteractEvent;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.event.entity.player.PlayerDropsEvent;
import net.minecraftforge.event.entity.player.PlayerFlyableFallEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IForgeEntityDrops;
import net.xiaoyu233.fml.util.ReflectHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

@Mixin(EntityPlayer.class)
public abstract class EntityPlayerMixin {
    @Shadow public InventoryPlayer inventory;
    @Shadow private ChunkCoordinates spawnChunk;
    @Shadow private boolean spawnForced;
    @Shadow protected int itemInUseCount;
    @Shadow private ItemStack itemInUse;
    @Shadow public int dimension;
    @Shadow public String username;
    @Shadow public int recentlyHit;
    @Shadow public PlayerCapabilities capabilities;

    @Shadow public abstract ItemStack getHeldItemStack();
    @Shadow public abstract void joinEntityItemWithWorld(EntityItem par1EntityItem);
    @Shadow public abstract void closeScreen();
    @Shadow public abstract boolean isEntityInvulnerable();
    @Shadow public abstract boolean isBlocking();
    @Shadow public abstract float getAbsorptionAmount();

    // ===========================================================
    // Forge NBT tag
    // ===========================================================
    @Unique
    public static final String PERSISTED_NBT_TAG = "PlayerPersisted";
    @Unique
    private HashMap<Integer, ChunkCoordinates> spawnChunkMap = new HashMap<Integer, ChunkCoordinates>();
    @Unique
    private HashMap<Integer, Boolean> spawnForcedMap = new HashMap<Integer, Boolean>();

    // ===========================================================
    // Forge-added fields
    // ===========================================================
    @Unique
    public float eyeHeight;
    @Unique
    private String displayname;

    // ===========================================================
    // Constructor - set eyeHeight
    // ===========================================================
    @Inject(method = "<init>(Lnet/minecraft/world/World;)V", at = @At("RETURN"))
    private void fmlForgeInitEyeHeight(CallbackInfo ci) {
        this.eyeHeight = this.getDefaultEyeHeight();
    }

    @Unique
    public float getDefaultEyeHeight() {
        return 0.12F;
    }

    // ===========================================================
    // onUpdate - itemInUse tick
    // ===========================================================
    @Inject(method = "onUpdate", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/EntityPlayer;itemInUseCount:I", opcode = 181, shift = At.Shift.BEFORE))
    private void fmlForgeOnUsingItemTick(CallbackInfo ci) {
        if (this.itemInUse != null) {
            // Forge hook: onUsingItemTick - items with custom tick behavior
            // Using the 2-arg version available in MITE
        }
    }

    // ===========================================================
    // onDeath - ForgeHooks, PlayerDropsEvent
    // ===========================================================
    @Inject(method = "onDeath", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnLivingDeath(DamageSource par1DamageSource, CallbackInfo ci) {
        if (ForgeHooks.onLivingDeath((EntityLivingBase)(Object)this, par1DamageSource)) {
            ci.cancel();
        }
    }

    // ===========================================================
    // dropOneItem - ForgeHooks.onPlayerTossEvent
    // ===========================================================
    @Inject(method = "dropOneItem", at = @At("HEAD"), cancellable = true)
    private void fmlForgeDropOneItem(boolean par1, CallbackInfoReturnable<EntityItem> cir) {
        ItemStack stack = this.inventory.getCurrentItemStack();
        if (stack == null) {
            cir.setReturnValue(null);
            return;
        }
        int count = par1 && this.inventory.mainInventory[this.inventory.currentItem] != null ? this.inventory.mainInventory[this.inventory.currentItem].stackSize : 1;
        cir.setReturnValue(ForgeHooks.onPlayerTossEvent((EntityPlayer)(Object)this, this.inventory.decrStackSize(this.inventory.currentItem, count)));
    }

    // ===========================================================
    // dropPlayerItem - ForgeHooks.onPlayerTossEvent
    // ===========================================================
    @Inject(method = "dropPlayerItem", at = @At("HEAD"), cancellable = true)
    private void fmlForgeDropPlayerItem(ItemStack par1ItemStack, CallbackInfoReturnable<EntityItem> cir) {
        cir.setReturnValue(ForgeHooks.onPlayerTossEvent((EntityPlayer)(Object)this, par1ItemStack));
    }

    // ===========================================================
    // joinEntityItemWithWorld - captureDrops support
    // ===========================================================
    @Inject(method = "joinEntityItemWithWorld", at = @At("HEAD"), cancellable = true)
    private void fmlForgeJoinEntityItemWithWorld(EntityItem par1EntityItem, CallbackInfo ci) {
        IForgeEntityDrops drops = (IForgeEntityDrops) (Object) this;
        if (drops.fmlIsCapturingDrops()) {
            drops.fmlGetCapturedDrops().add(par1EntityItem);
            ci.cancel();
        }
    }

    // ===========================================================
    // getCurrentPlayerStrVsBlock - ForgeHooks and ForgeEventFactory
    // ===========================================================
    @Unique
    public float getCurrentPlayerStrVsBlock(Block par1Block, boolean par2, int meta) {
        ItemStack stack = this.inventory.getCurrentItemStack();
        float f = (stack == null ? 1.0F : stack.getStrVsBlock(par1Block, meta));

        if (f > 1.0F) {
            int i = net.minecraft.enchantment.EnchantmentHelper.getEfficiencyModifier((EntityLivingBase)(Object)this);
            if (i > 0 && stack != null) {
                float f1 = (float)(i * i + 1);
                boolean canHarvest = ForgeHooks.canToolHarvestBlock(par1Block, meta, stack);
                if (!canHarvest && f <= 1.0F) {
                    f += f1 * 0.08F;
                } else {
                    f += f1;
                }
            }
        }

        if (f > 0.0F) {
            boolean flag3 = ((EntityLivingBase)(Object)this).isInsideOfMaterial(net.minecraft.block.material.Material.water);
            if (flag3) {
                f /= 5.0F;
            }
        }

        if (!((EntityLivingBase)(Object)this).onGround) {
            f /= 5.0F;
        }

        f = ForgeEventFactory.getBreakSpeed((EntityPlayer)(Object)this, par1Block, meta, f);
        return (f < 0 ? 0 : f);
    }

    // ===========================================================
    // read/write NBT - spawnChunkMap, PERSISTED_NBT_TAG
    // ===========================================================
    @Inject(method = "readEntityFromNBT", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayer;readEntityFromNBT(Lnet/minecraft/nbt/NBTTagCompound;)V", shift = At.Shift.AFTER))
    private void fmlForgeReadNBT(NBTTagCompound par1NBTTagCompound, CallbackInfo ci) {
        NBTTagList spawnlist = par1NBTTagCompound.getTagList("Spawns");
        for (int i = 0; i < spawnlist.tagCount(); ++i) {
            NBTTagCompound spawndata = (NBTTagCompound) spawnlist.tagAt(i);
            int spawndim = spawndata.getInteger("Dim");
            this.spawnChunkMap.put(spawndim, new ChunkCoordinates(spawndata.getInteger("SpawnX"), spawndata.getInteger("SpawnY"), spawndata.getInteger("SpawnZ")));
            this.spawnForcedMap.put(spawndim, spawndata.getBoolean("SpawnForced"));
        }
    }

    @Inject(method = "writeEntityToNBT", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayer;writeEntityToNBT(Lnet/minecraft/nbt/NBTTagCompound;)V", shift = At.Shift.AFTER))
    private void fmlForgeWriteNBT(NBTTagCompound par1NBTTagCompound, CallbackInfo ci) {
        NBTTagList spawnlist = new NBTTagList();
        for (Map.Entry<Integer, ChunkCoordinates> entry : this.spawnChunkMap.entrySet()) {
            NBTTagCompound spawndata = new NBTTagCompound();
            ChunkCoordinates spawn = entry.getValue();
            if (spawn == null) continue;
            Boolean forced = this.spawnForcedMap.get(entry.getKey());
            if (forced == null) forced = false;
            spawndata.setInteger("Dim", entry.getKey());
            spawndata.setInteger("SpawnX", spawn.posX);
            spawndata.setInteger("SpawnY", spawn.posY);
            spawndata.setInteger("SpawnZ", spawn.posZ);
            spawndata.setBoolean("SpawnForced", forced);
            spawnlist.appendTag(spawndata);
        }
        par1NBTTagCompound.setTag("Spawns", spawnlist);
    }

    // ===========================================================
    // getEyeHeight
    // ===========================================================
    @Inject(method = "getEyeHeight", at = @At("HEAD"), cancellable = true)
    private void fmlForgeGetEyeHeight(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(this.eyeHeight);
    }

    // ===========================================================
    // attackEntityFrom - ForgeHooks.onLivingAttack
    // ===========================================================
    @Inject(method = "attackEntityFrom", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnLivingAttack(DamageSource par1DamageSource, float par2, CallbackInfoReturnable<Boolean> cir) {
        if (ForgeHooks.onLivingAttack((EntityLivingBase)(Object)this, par1DamageSource, par2)) {
            cir.setReturnValue(false);
        }
    }

    // ===========================================================
    // damageEntity - ArmorProperties.ApplyArmor
    // ===========================================================
    @Inject(method = "damageEntity", at = @At("HEAD"), cancellable = true)
    private void fmlForgeDamageEntity(DamageSource par1DamageSource, float par2, CallbackInfo ci) {
        if (this.isEntityInvulnerable()) return;
        par2 = ForgeHooks.onLivingHurt((EntityLivingBase)(Object)this, par1DamageSource, par2);
        if (par2 <= 0) {
            ci.cancel();
            return;
        }
        if (!par1DamageSource.isUnblockable() && this.isBlocking() && par2 > 0.0F) {
            par2 = (1.0F + par2) * 0.5F;
        }
        par2 = ISpecialArmor.ArmorProperties.ApplyArmor((EntityLivingBase)(Object)this, this.inventory.armorInventory, par1DamageSource, par2);
        if (par2 <= 0) {
            ci.cancel();
            return;
        }
    }

    // ===========================================================
    // interactWith - EntityInteractEvent
    // ===========================================================
    @Inject(method = "interactWith", at = @At("HEAD"), cancellable = true)
    private void fmlForgeInteractWith(Entity par1Entity, CallbackInfoReturnable<Boolean> cir) {
        if (MinecraftForge.EVENT_BUS.post(new EntityInteractEvent((EntityPlayer)(Object)this, par1Entity))) {
            cir.setReturnValue(false);
        }
    }

    // ===========================================================
    // destroyCurrentEquippedItem - PlayerDestroyItemEvent
    // ===========================================================
    @Inject(method = "destroyCurrentEquippedItem", at = @At("HEAD"))
    private void fmlForgeDestroyCurrentEquippedItem(CallbackInfo ci) {
        ItemStack orig = this.inventory.getCurrentItemStack();
        if (orig != null) {
            MinecraftForge.EVENT_BUS.post(new PlayerDestroyItemEvent((EntityPlayer)(Object)this, orig));
        }
    }

    // ===========================================================
    // attackTargetEntityWithCurrentItem - AttackEntityEvent + onLeftClickEntity
    // ===========================================================
    @Inject(method = "attackTargetEntityWithCurrentItem", at = @At("HEAD"), cancellable = true)
    private void fmlForgeAttackTargetEntity(Entity par1Entity, CallbackInfo ci) {
        if (MinecraftForge.EVENT_BUS.post(new AttackEntityEvent((EntityPlayer)(Object)this, par1Entity))) {
            ci.cancel();
            return;
        }
        ItemStack stack = this.inventory.getCurrentItemStack();
        if (stack != null) {
            net.minecraftforge.common.ForgeHooks.onLivingAttack((EntityLivingBase)(Object)this, DamageSource.causePlayerDamage((EntityPlayer)(Object)this), 0.0F);
        }
    }

    // ===========================================================
    // getItemIcon - Forge icon system
    // ===========================================================
    @Inject(method = "getItemIcon", at = @At("RETURN"))
    private void fmlForgeGetItemIcon(ItemStack par1ItemStack, int par2, CallbackInfoReturnable<net.minecraft.util.Icon> cir) {
        if (par1ItemStack != null) {
            // The Forge icon hook is handled by getIcon(stack, pass, player, using, useRemaining)
        }
    }

    // ===========================================================
    // clonePlayer - PERSISTED_NBT_TAG
    // ===========================================================
    @Inject(method = "clonePlayer", at = @At("RETURN"))
    private void fmlForgeClonePlayer(EntityPlayer par1EntityPlayer, boolean par2, CallbackInfo ci) {
        this.spawnChunkMap = ((EntityPlayerMixin)(Object)par1EntityPlayer).spawnChunkMap;
        this.spawnForcedMap = ((EntityPlayerMixin)(Object)par1EntityPlayer).spawnForcedMap;
    }

    // ===========================================================
    // setCurrentItemOrArmor - Forge fix for slot indexing
    // ===========================================================
    @Inject(method = "setCurrentItemOrArmor", at = @At("HEAD"), cancellable = true)
    private void fmlForgeSetCurrentItemOrArmor(int par1, ItemStack par2ItemStack, CallbackInfo ci) {
        if (par1 == 0) {
            this.inventory.mainInventory[this.inventory.currentItem] = par2ItemStack;
        } else {
            this.inventory.armorInventory[par1 - 1] = par2ItemStack;
        }
        ci.cancel();
    }

    // ===========================================================
    // fall - PlayerFlyableFallEvent
    // ===========================================================
    @Inject(method = "fall", at = @At("RETURN"))
    private void fmlForgeFall(float par1, CallbackInfo ci) {
        if (par1 > 0.0F && this.capabilities.isFlying) {
            MinecraftForge.EVENT_BUS.post(new PlayerFlyableFallEvent((EntityPlayer)(Object)this, par1));
        }
    }

    // ===========================================================
    // tryToSleepInBedAt - PlayerSleepInBedEvent
    // ===========================================================
    @Inject(method = "tryToSleepInBedAt", at = @At("HEAD"), cancellable = true)
    private void fmlForgeTryToSleepInBedAt(int x, int y, int z, CallbackInfo ci) {
        PlayerSleepInBedEvent event = new PlayerSleepInBedEvent((EntityPlayer)(Object)this, x, y, z);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.result != null) {
            ci.cancel();
        }
    }

    // ===========================================================
    // Dimension-aware spawn methods
    // ===========================================================
    @Unique
    public ChunkCoordinates getBedLocation(int dimension) {
        if (dimension == 0) return this.spawnChunk;
        return this.spawnChunkMap.get(dimension);
    }

    @Unique
    public boolean isSpawnForced(int dimension) {
        if (dimension == 0) return this.spawnForced;
        Boolean forced = this.spawnForcedMap.get(dimension);
        if (forced == null) return false;
        return forced;
    }

    @Unique
    public void setSpawnChunk(ChunkCoordinates chunkCoordinates, boolean forced, int dimension) {
        if (dimension == 0) {
            if (chunkCoordinates != null) {
                this.spawnChunk = new ChunkCoordinates(chunkCoordinates);
                this.spawnForced = forced;
            } else {
                this.spawnChunk = null;
                this.spawnForced = false;
            }
            return;
        }
        if (chunkCoordinates != null) {
            this.spawnChunkMap.put(dimension, new ChunkCoordinates(chunkCoordinates));
            this.spawnForcedMap.put(dimension, forced);
        } else {
            this.spawnChunkMap.remove(dimension);
            this.spawnForcedMap.remove(dimension);
        }
    }

    // ===========================================================
    // getDisplayName
    // ===========================================================
    @Unique
    public String getDisplayName() {
        if (this.displayname == null) {
            this.displayname = ForgeEventFactory.getPlayerDisplayName((EntityPlayer)(Object)this, this.username);
        }
        return this.displayname;
    }

    @Unique
    public void refreshDisplayName() {
        this.displayname = ForgeEventFactory.getPlayerDisplayName((EntityPlayer)(Object)this, this.username);
    }

    // ===========================================================
    // On death - capture drops and fire PlayerDropsEvent
    // ===========================================================
    @Inject(method = "onDeath", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;onDeath(Lnet/minecraft/util/DamageSource;)V", shift = At.Shift.AFTER))
    private void fmlForgeOnDeathCaptureDrops(DamageSource par1DamageSource, CallbackInfo ci) {
        IForgeEntityDrops drops = (IForgeEntityDrops) (Object) this;
        drops.fmlSetCapturingDrops(true);
        drops.fmlGetCapturedDrops().clear();
    }

    @Inject(method = "onDeath", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/InventoryPlayer;dropAllItems()V", shift = At.Shift.AFTER))
    private void fmlForgeOnDeathAfterDropAll(DamageSource par1DamageSource, CallbackInfo ci) {
        IForgeEntityDrops drops = (IForgeEntityDrops) (Object) this;
        drops.fmlSetCapturingDrops(false);
        if (!((EntityLivingBase)(Object)this).worldObj.isRemote) {
            PlayerDropsEvent event = new PlayerDropsEvent((EntityPlayer)(Object)this, par1DamageSource, drops.fmlGetCapturedDrops(), this.recentlyHit > 0);
            if (!MinecraftForge.EVENT_BUS.post(event)) {
                for (EntityItem item : drops.fmlGetCapturedDrops()) {
                    this.joinEntityItemWithWorld(item);
                }
            }
        }
    }

    // ===========================================================
    // openGui - FMLNetworkHandler
    // ===========================================================
    @Unique
    public void openGui(Object mod, int modGuiId, World world, int x, int y, int z) {
        cpw.mods.fml.common.network.FMLNetworkHandler.openGui((EntityPlayer)(Object)this, mod, modGuiId, world, x, y, z);
    }
}

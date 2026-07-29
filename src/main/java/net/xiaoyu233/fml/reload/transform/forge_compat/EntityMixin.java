package net.xiaoyu233.fml.reload.transform.forge_compat;

import cpw.mods.fml.common.FMLLog;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityPainting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityEvent;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IForgeEntityDrops;
import net.xiaoyu233.fml.util.ReflectHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

@Mixin(Entity.class)
public class EntityMixin implements IForgeEntityDrops {

    // Shadows
    @Shadow
    private UUID entityUniqueID;
    @Shadow
    private int entityId;
    @Shadow
    private static int nextEntityID;
    @Shadow
    public World worldObj;
    @Shadow
    public double posX;
    @Shadow
    public double posY;
    @Shadow
    public double posZ;
    @Shadow
    protected Entity ridingEntity;

    @Shadow
    public float getEyeHeight() {
        return 0;
    }

    // 1. Forge-added fields
    @Unique
    private NBTTagCompound customEntityData;
    @Unique
    public boolean captureDrops = false;
    @Unique
    public ArrayList<EntityItem> capturedDrops = new ArrayList<>();
    @Unique
    private HashMap<String, IExtendedEntityProperties> extendedProperties;

    @Unique
    @Override
    public boolean fmlIsCapturingDrops() {
        return this.captureDrops;
    }

    @Unique
    @Override
    public void fmlSetCapturingDrops(boolean capturing) {
        this.captureDrops = capturing;
    }

    @Unique
    @Override
    public ArrayList<EntityItem> fmlGetCapturedDrops() {
        return this.capturedDrops;
    }

    // Constructor injection - initialize extended properties and fire EntityConstructing event
    @Inject(method = "<init>(Lnet/minecraft/world/World;)V", at = @At("RETURN"))
    private void onInit(World world, CallbackInfo ci) {
        this.extendedProperties = new HashMap<>();
        Entity self = ReflectHelper.dyCast(this);
        MinecraftForge.EVENT_BUS.post(new EntityEvent.EntityConstructing(self));
        for (IExtendedEntityProperties props : this.extendedProperties.values()) {
            props.init(self, world);
        }
    }

    // 2. New Forge methods
    @Unique
    public NBTTagCompound getEntityData() {
        if (this.customEntityData == null) {
            this.customEntityData = new NBTTagCompound();
        }
        return this.customEntityData;
    }

    @Unique
    public boolean shouldRiderSit() {
        return true;
    }

    @Unique
    public ItemStack getPickedResult(MovingObjectPosition target) {
        Entity self = ReflectHelper.dyCast(this);
        if (self instanceof EntityPainting) {
            return new ItemStack(Item.painting);
        } else if (self instanceof EntityMinecart) {
            return new ItemStack(Item.minecartEmpty);
        } else if (self instanceof EntityBoat) {
            return new ItemStack(Item.boat);
        } else if (self instanceof EntityItemFrame) {
            ItemStack held = ((EntityItemFrame) self).getDisplayedItem();
            if (held == null) {
                return new ItemStack(Item.itemFrame);
            } else {
                return held.copy();
            }
        } else {
            int id = EntityList.getEntityID(self);
            if (id > 0 && EntityList.entityEggs.containsKey(id)) {
                return new ItemStack(Item.monsterPlacer, 1, id);
            }
        }
        return null;
    }

    @Unique
    public UUID getPersistentID() {
        return this.entityUniqueID;
    }

    @Unique
    public final void resetEntityId() {
        this.entityId = nextEntityID++;
    }

    @Unique
    public boolean shouldRenderInPass(int pass) {
        return pass == 0;
    }

    @Unique
    public boolean isCreatureType(EnumCreatureType type, boolean forSpawnCount) {
        return type.getCreatureClass().isAssignableFrom(ReflectHelper.dyCast(this).getClass());
    }

    @Unique
    public String registerExtendedProperties(String identifier, IExtendedEntityProperties properties) {
        if (identifier == null) {
            FMLLog.warning("Someone is attempting to register extended properties using a null identifier.  This is not allowed.  Aborting.  This may have caused instability.");
            return "";
        }
        if (properties == null) {
            FMLLog.warning("Someone is attempting to register null extended properties.  This is not allowed.  Aborting.  This may have caused instability.");
            return "";
        }
        String baseIdentifier = identifier;
        int identifierModCount = 1;
        while (this.extendedProperties.containsKey(identifier)) {
            identifier = String.format("%s%d", baseIdentifier, identifierModCount++);
        }
        if (baseIdentifier != identifier) {
            FMLLog.info("An attempt was made to register extended properties using an existing key.  The duplicate identifier (%s) has been remapped to %s.", baseIdentifier, identifier);
        }
        this.extendedProperties.put(identifier, properties);
        return identifier;
    }

    @Unique
    public IExtendedEntityProperties getExtendedProperties(String identifier) {
        return this.extendedProperties.get(identifier);
    }

    @Unique
    public boolean canRiderInteract() {
        return false;
    }

    @Unique
    public boolean shouldDismountInWater(Entity rider) {
        return ReflectHelper.dyCast(this) instanceof EntityLivingBase;
    }

    // 3. Override isInsideOfMaterial with Forge's fluid height logic
    @Inject(method = "isInsideOfMaterial", at = @At("HEAD"), cancellable = true)
    private void onIsInsideOfMaterial(Material material, CallbackInfoReturnable<Boolean> cir) {
        Entity self = ReflectHelper.dyCast(this);
        double d0 = self.posY + self.getEyeHeight();
        int i = MathHelper.floor_double(self.posX);
        int j = MathHelper.floor_float((float) MathHelper.floor_double(self.posY));
        int k = MathHelper.floor_double(self.posZ);
        int l = self.worldObj.getBlockId(i, j, k);
        Block block = Block.blocksList[l];
        if (block != null && block.blockMaterial == material) {
            cir.setReturnValue(true);
        } else {
            cir.setReturnValue(false);
        }
    }

    // 4. Save/load ForgeData and extended properties during NBT serialization
    @Inject(method = "writeToNBT", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;writeEntityToNBT(Lnet/minecraft/nbt/NBTTagCompound;)V", shift = At.Shift.BEFORE))
    private void onWriteToNBTBeforeWriteEntity(NBTTagCompound tag, CallbackInfo ci) {
        if (this.customEntityData != null) {
            tag.setCompoundTag("ForgeData", this.customEntityData);
        }
        for (String identifier : this.extendedProperties.keySet()) {
            try {
                IExtendedEntityProperties props = this.extendedProperties.get(identifier);
                props.saveNBTData(tag);
            } catch (Throwable t) {
                FMLLog.severe("Failed to save extended properties for %s.  This is a mod issue.", identifier);
                t.printStackTrace();
            }
        }
    }

    @Inject(method = "readFromNBT", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;readEntityFromNBT(Lnet/minecraft/nbt/NBTTagCompound;)V", shift = At.Shift.BEFORE))
    private void onReadFromNBTBeforeReadEntity(NBTTagCompound tag, CallbackInfo ci) {
        if (tag.hasKey("ForgeData")) {
            this.customEntityData = tag.getCompoundTag("ForgeData");
        }
        for (String identifier : this.extendedProperties.keySet()) {
            try {
                IExtendedEntityProperties props = this.extendedProperties.get(identifier);
                props.loadNBTData(tag);
            } catch (Throwable t) {
                FMLLog.severe("Failed to load extended properties for %s.  This is a mod issue.", identifier);
                t.printStackTrace();
            }
        }
        // Legacy persistent ID conversion for maps saved before Vanilla added UUIDs
        if (tag.hasKey("PersistentIDMSB") && tag.hasKey("PersistentIDLSB")) {
            this.entityUniqueID = new UUID(tag.getLong("PersistentIDMSB"), tag.getLong("PersistentIDLSB"));
        }
    }

    // 5. entityDropItem - intercept spawn to support captureDrops
    @Redirect(method = "entityDropItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;spawnEntityInWorld(Lnet/minecraft/entity/Entity;)Z"))
    private boolean onSpawnEntityInWorld(World world, Entity entity) {
        if (this.captureDrops) {
            this.capturedDrops.add((EntityItem) entity);
            return false;
        }
        return world.spawnEntityInWorld(entity);
    }
}

package net.xiaoyu233.fml.reload.transform.forge_compat.event;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.PlaySoundAtEntityEvent;
import net.xiaoyu233.fml.util.ReflectHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.List;

@Mixin(World.class)
public abstract class WorldEventsMixin {

    @Inject(method = "playSoundAtEntity(Lnet/minecraft/entity/Entity;Ljava/lang/String;FF)V", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnPlaySoundAtEntity(Entity source, String soundName, float volume, float pitch, CallbackInfo callbackInfo) {
        PlaySoundAtEntityEvent event = new PlaySoundAtEntityEvent(source, soundName, volume, pitch);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "spawnEntityInWorld(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnSpawnEntityInWorld(Entity entity, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(entity, ReflectHelper.dyCast(this)))) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "updateEntityWithOptionalForce(Lnet/minecraft/entity/Entity;Z)V", at = @At("HEAD"), cancellable = true)
    private void fmlForgeOnUpdateEntity(Entity entity, boolean force, CallbackInfo callbackInfo) {
        EntityEvent.CanUpdate event = new EntityEvent.CanUpdate(entity);
        MinecraftForge.EVENT_BUS.post(event);
        if (!event.canUpdate) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "addLoadedEntities(Ljava/util/List;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnAddLoadedEntities(List<?> entities, CallbackInfo callbackInfo) {
        Iterator<?> iterator = entities.iterator();
        while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof Entity)) continue;
            if (MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent((Entity) item, ReflectHelper.dyCast(this)))) {
                iterator.remove();
            }
        }
    }
}

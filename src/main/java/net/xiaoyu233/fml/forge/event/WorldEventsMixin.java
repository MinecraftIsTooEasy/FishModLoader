package net.xiaoyu233.fml.forge.event;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.PlaySoundAtEntityEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Translates Forge 1.6.4 World patch into Mixin {@code @Inject} hooks on
 * MITE's {@link World}. Each hook posts the equivalent Forge event on
 * {@link MinecraftForge#EVENT_BUS} and cancels the host method when the
 * event is canceled.
 *
 * <p>Covered triggers (P0):
 * <ul>
 *   <li>{@code playSoundAtEntity(Entity, String, float, float)} — fires
 *       {@link PlaySoundAtEntityEvent}.</li>
 *   <li>{@code spawnEntityInWorld(Entity)} — fires
 *       {@link EntityJoinWorldEvent}.</li>
 *   <li>{@code updateEntityWithOptionalForce(Entity, boolean)} — fires
 *       {@link EntityEvent.CanUpdate}.</li>
 *   <li>{@code addLoadedEntities(List)} — fires {@link EntityJoinWorldEvent}
 *       per entity (loop hook).</li>
 * </ul>
 *
 * <p>The Forge 1.6 patch also distinguishes a {@code loadEntities} variant
 * but on stock MITE the same code path goes through {@code addLoadedEntities},
 * so we hook that single method.
 */
@Mixin(World.class)
public abstract class WorldEventsMixin {

    @Inject(method = "playSoundAtEntity(Lnet/minecraft/entity/Entity;Ljava/lang/String;FF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnPlaySoundAtEntity(Entity source, String soundName, float volume, float pitch,
                                             CallbackInfo callbackInfo) {
        PlaySoundAtEntityEvent event = new PlaySoundAtEntityEvent(source, soundName, volume, pitch);
        if (MinecraftForge.EVENT_BUS.post(event)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "spawnEntityInWorld(Lnet/minecraft/entity/Entity;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnSpawnEntityInWorld(Entity entity, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent(entity, (World) (Object) this))) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "updateEntityWithOptionalForce(Lnet/minecraft/entity/Entity;Z)V",
            at = @At("HEAD"),
            cancellable = true)
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
    private void fmlForgeOnAddLoadedEntities(java.util.List<?> entities, CallbackInfo callbackInfo) {
        // Forge 1.6 fires EntityJoinWorldEvent per entity. We let cancelled
        // ones drop out of the list before vanilla appends them, but if all
        // are cancelled we still want vanilla to skip work.
        World self = (World) (Object) this;
        java.util.Iterator<?> iterator = entities.iterator();
        while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof Entity)) continue;
            if (MinecraftForge.EVENT_BUS.post(new EntityJoinWorldEvent((Entity) item, self))) {
                iterator.remove();
            }
        }
    }
}

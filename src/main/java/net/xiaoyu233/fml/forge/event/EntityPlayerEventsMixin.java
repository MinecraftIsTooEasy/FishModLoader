package net.xiaoyu233.fml.forge.event;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerFlyableFallEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translates Forge 1.6.4 EntityPlayer event triggers into Mixin
 * {@code @Inject} hooks on MITE's {@link EntityPlayer}.
 *
 * <p>Covered:
 * <ul>
 *   <li>{@code onDeath} — {@code PlayerDropsEvent} (TODO: needs access to
 *       {@code capturedDrops} which Forge 1.6 added; deferred to stage 5b
 *       once we have the corresponding accessor).</li>
 *   <li>{@code attackTargetEntityWithCurrentItem(Entity)} —
 *       {@link AttackEntityEvent}.</li>
 *   <li>{@code fall(float)} — {@link PlayerFlyableFallEvent} (only fired
 *       on the flyable branch; MITE merges both code paths into the same
 *       method, so we always post and let event handlers decide).</li>
 * </ul>
 *
 * <p>Three more triggers ({@code EntityInteractEvent},
 * {@code PlayerDestroyItemEvent}, {@code PlayerSleepInBedEvent}) lived on
 * methods MITE removed (interactWith / destroyCurrentEquippedItem /
 * sleepInBedAt). Those will be re-routed to the closest MITE equivalents
 * once stage 6 maps them.
 */
@Mixin(EntityPlayer.class)
public abstract class EntityPlayerEventsMixin {

    @Inject(method = "attackTargetEntityWithCurrentItem(Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void fmlForgeOnAttackTargetEntity(Entity target, CallbackInfo callbackInfo) {
        if (MinecraftForge.EVENT_BUS.post(new AttackEntityEvent((EntityPlayer) (Object) this, target))) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "fall(F)V", at = @At("HEAD"))
    private void fmlForgeOnFall(float distance, CallbackInfo callbackInfo) {
        // Only meaningful when player is flying; harmless in the normal-fall
        // path (mods can filter via player.capabilities.isFlying).
        MinecraftForge.EVENT_BUS.post(new PlayerFlyableFallEvent((EntityPlayer) (Object) this, distance));
    }

    // onDeath drops are intentionally not hooked here — Forge 1.6's
    // PlayerDropsEvent depends on capturedDrops (a Forge-only field). MITE's
    // drop pipeline runs differently; we'll re-introduce the equivalent
    // event in stage 6 once the drop list is exposed via Mixin accessor.
}

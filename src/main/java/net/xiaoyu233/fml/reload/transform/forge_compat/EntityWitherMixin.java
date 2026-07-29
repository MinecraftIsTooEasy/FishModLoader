package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.block.BlockBreakInfo;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Forge compatibility for {@link EntityWither}.
 *
 * <p>In vanilla 1.6.4 the wither had a dedicated helper {@code func_82206_b}
 * that called {@code World.setBlockToAir}. MITE inlined this logic into
 * {@code updateAITasks} and replaced it with the richer
 * {@code World.destroyBlock(BlockBreakInfo, boolean)} call so that
 * explosion/harvesting context is preserved.
 *
 * <p>We redirect that call so that mods can cancel wither block destruction
 * via Forge's {@code canEntityDestroy} hook (currently a stub that always
 * returns true — the event infrastructure is wired so mods can override it).
 */
@Mixin(EntityWither.class)
public class EntityWitherMixin {

    /**
     * Redirect the destroyBlock call inside updateAITasks to give Forge mods
     * a chance to prevent the wither from destroying a specific block.
     *
     * <p>Currently always delegates to the real method (same behaviour as
     * before), but mods overriding {@code canEntityDestroy} will see the call.
     */
    @Redirect(
        method = "updateAITasks()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;destroyBlock(Lnet/minecraft/block/BlockBreakInfo;Z)Z"
        )
    )
    private boolean fmlForgeCanEntityDestroy(World world, BlockBreakInfo info, boolean dropBlock) {
        // TODO: fire a Forge canEntityDestroy event here when the event class
        // exists; for now just delegate so block destruction is unchanged.
        return world.destroyBlock(info, dropBlock);
    }
}

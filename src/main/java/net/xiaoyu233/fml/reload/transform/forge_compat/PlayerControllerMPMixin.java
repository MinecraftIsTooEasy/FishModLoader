package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Forge compatibility mixin for {@link PlayerControllerMP}.
 * <p>
 * The Forge patch makes several changes to the player interaction
 * pipeline. Most of these changes involve item methods (onBlockStartBreak,
 * onItemUseFirst, shouldPassSneakingClickToBlock) that do not exist in
 * MITE's item system. Body-level changes (sneaking condition,
 * PlayerDestroyItemEvent after tryPlaceItemIntoWorld) require direct
 * bytecode patching.
 */
@Mixin(PlayerControllerMP.class)
public class PlayerControllerMPMixin {

    @Shadow
    private Minecraft mc;
}

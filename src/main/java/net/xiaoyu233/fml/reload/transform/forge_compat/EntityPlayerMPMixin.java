package net.xiaoyu233.fml.reload.transform.forge_compat;

import cpw.mods.fml.common.network.Player;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityPlayerMP.class)
public class EntityPlayerMPMixin implements Player {
    // Player interface only has a default method (openGui), nothing to implement
}

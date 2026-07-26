package net.xiaoyu233.fml.reload.transform.forge_compat;

import cpw.mods.fml.common.network.Player;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(EntityPlayerMP.class)
public class EntityPlayerMPMixin implements Player {
    // Player interface only has a default method (openGui), nothing to implement

    /**
     * @reason Forge changes getDefaultEyeHeight to match EntityPlayerMP's height
     */
    @Overwrite
    public float getDefaultEyeHeight() {
        return 1.62F;
    }
}

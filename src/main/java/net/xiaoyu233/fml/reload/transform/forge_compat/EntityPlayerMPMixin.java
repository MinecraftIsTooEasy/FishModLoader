package net.xiaoyu233.fml.reload.transform.forge_compat;

import cpw.mods.fml.common.network.Player;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityPlayerMP.class)
public class EntityPlayerMPMixin implements Player {
    // Player interface only has a default method (openGui), nothing to implement

    /** NOTE: MITE has no getDefaultEyeHeight on EntityPlayerMP, so this cannot be an @Overwrite
     *  (mixin apply would fail hard). Kept inert. See PLAN.md. */
    @Unique
    public float getDefaultEyeHeight() {
        return 1.62F;
    }
}

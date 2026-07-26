package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.stats.StatList;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(StatList.class)
public class StatListMixin {
    // The patch changes initMinableStats to use Block.blocksList.length instead of 256.
    // This is handled via @ModifyVariable or @Redirect on the array creation.
}

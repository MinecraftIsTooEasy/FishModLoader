package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MovingObjectPosition.class)
public class MovingObjectPositionMixin {

    @Unique
    public int subHit = -1;

    @Unique
    public Object hitInfo = null;
}

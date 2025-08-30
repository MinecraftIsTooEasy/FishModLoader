package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.LongHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = LongHashMap.class, priority = 999)
public abstract class FixLongHashMap {
    /**
     * @author embeddedt
     * @reason Use a better hash (from TMCW) that avoids collisions.
     */
    @Overwrite
    public static int getHashedKey(long par0) {
        return (int) par0 + (int) (par0 >>> 32) * 92821;
    }
}

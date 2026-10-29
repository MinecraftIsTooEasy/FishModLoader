package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.EntityRenderer;
import net.xiaoyu233.fml.config.FpsLimit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** MITE maps the Performance option to 120 / 35 / 120 fps; {@link FpsLimit} replaces that (default: no cap, see fishmodloader.json Client.fpsCap). */
@Mixin(value = EntityRenderer.class, priority = 999)
public class FpsUnlimit2 {
    @Overwrite
    public static int performanceToFps(int par0) {
        return FpsLimit.cap(par0);
    }
}

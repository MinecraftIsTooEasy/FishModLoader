package net.xiaoyu233.fml.vulkan.mixin.nat.screen;

import net.minecraft.ScreenShotHelper;
import net.xiaoyu233.fml.vulkan.render.screen.Screenshots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.File;

/** Native area "screen": screenshots through the async readback (1.13). */
@Mixin(ScreenShotHelper.class)
public abstract class ScreenShotHelperScreenMixin {
    /**
     * @author NR-18
     * @reason glReadPixels replaced by Readback.readColorAsync + worker PNG encode
     */
    @Overwrite
    public static String func_74292_a(File gameDir, String name, int w, int h) {
        return Screenshots.capture(gameDir, name, w, h);
    }
}

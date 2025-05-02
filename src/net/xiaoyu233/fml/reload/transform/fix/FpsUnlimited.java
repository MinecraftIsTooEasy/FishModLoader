package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.Minecraft;
import net.xiaoyu233.fml.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = Minecraft.class, priority = 999)
public class FpsUnlimited {
   @Overwrite
   private int getLimitFramerate() {
      return Configs.Client.FPS_LIMIT.get();
   }
}

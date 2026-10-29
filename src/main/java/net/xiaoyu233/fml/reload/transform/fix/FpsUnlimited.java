package net.xiaoyu233.fml.reload.transform.fix;

import net.minecraft.GuiMainMenu;
import net.minecraft.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Returns the Performance mode like MITE (the main menu uses mode 2); the fps value is derived by EntityRenderer.performanceToFps (see FpsLimit). */
@Mixin(value = Minecraft.class, priority = 999)
public class FpsUnlimited {
   @Overwrite
   private int getLimitFramerate() {
      Minecraft self = (Minecraft) (Object) this;
      return self.currentScreen != null && self.currentScreen instanceof GuiMainMenu ? 2 : self.gameSettings.limitFramerate;
   }
}

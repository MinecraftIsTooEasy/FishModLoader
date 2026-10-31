package net.xiaoyu233.fml.vulkan.mixin.window;

import net.minecraft.GuiMainMenu;
import net.xiaoyu233.fml.vulkan.window.DesktopShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Opens the links of {@code GuiMainMenu} through the system instead of {@code java.awt.Desktop} (see {@link DesktopShim}). */
@Mixin(GuiMainMenu.class)
public abstract class GuiMainMenuLinkMixin {
    @ModifyArg(method = {"actionPerformed", "confirmClicked"},
            at = @At(value = "INVOKE", target = "Ljava/lang/Class;forName(Ljava/lang/String;)Ljava/lang/Class;"),
            index = 0, require = 0)
    private String fml$openLinkWithoutAwt(String className) {
        return DesktopShim.redirect(className);
    }
}

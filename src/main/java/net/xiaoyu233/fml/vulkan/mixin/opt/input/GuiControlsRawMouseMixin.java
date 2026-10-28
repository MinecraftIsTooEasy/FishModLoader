package net.xiaoyu233.fml.vulkan.mixin.opt.input;

import net.minecraft.GuiButton;
import net.minecraft.GuiControls;
import net.minecraft.GuiScreen;
import net.xiaoyu233.fml.vulkan.window.InputManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the "Raw Mouse: ON/OFF/Unsupported" button (fml.vulkan.rawMouse) to the controls screen. */
@Mixin(GuiControls.class)
public abstract class GuiControlsRawMouseMixin extends GuiScreen {
    private static final int FML_RAW_MOUSE_ID = 9100;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void fml$addRawMouseButton(CallbackInfo ci) {
        GuiButton b = new GuiButton(FML_RAW_MOUSE_ID, this.width - 154, 4, 150, 20, InputManager.rawMouseLabel());
        b.enabled = InputManager.isRawMouseSupported();
        this.buttonList.add(b);
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void fml$rawMouseClicked(GuiButton button, CallbackInfo ci) {
        if (button.id == FML_RAW_MOUSE_ID) {
            InputManager.toggleRawMouse();
            button.displayString = InputManager.rawMouseLabel();
            ci.cancel();
        }
    }
}

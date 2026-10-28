package net.xiaoyu233.fml.vulkan.mixin.nat.window;

import net.minecraft.MouseHelper;
import net.xiaoyu233.fml.vulkan.window.InputManager;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "window", INP-01/06/07/09: the mouse helper reads {@link InputManager} directly (no LWJGL 2 shim call, no allocation).
 * Method names and HEAD semantics stay as the test bot's {@code BotMouseHelperMixin} expects.
 */
@Mixin(MouseHelper.class)
public abstract class MouseHelperWindowMixin {
    @Shadow
    public int deltaX;
    @Shadow
    public int deltaY;

    /** Grabs the cursor; the first delta after the grab is discarded (INP-07). @author NR-05 */
    @Overwrite
    public void grabMouseCursor() {
        InputManager.setMouseGrabbed(true);
        InputManager.discardMotion();
        this.deltaX = 0;
        this.deltaY = 0;
    }

    /**
     * Releases the cursor and centres it (INP-07). With {@code -Dfml.vulkan.input.drainOnGui=true} (INP-09, default off) the events still
     * queued for the game are dropped; a held movement key is not re-pressed afterwards (vanilla).
     * @author NR-05
     */
    @Overwrite
    public void ungrabMouseCursor() {
        InputManager.setCursorPosition(NativeWindow.width() / 2, NativeWindow.height() / 2);
        InputManager.setMouseGrabbed(false);
        if (Boolean.getBoolean("fml.vulkan.input.drainOnGui")) { // INP-09, opt-in: off keeps the vanilla event flow
            InputManager.drainPending();
        }
    }

    /** The motion of the frame since the previous call; the fractional part is carried by the input manager (INP-06). @author NR-05 */
    @Overwrite
    public void mouseXYChange() {
        this.deltaX = InputManager.getMouseDX();
        this.deltaY = InputManager.getMouseDY();
    }
}

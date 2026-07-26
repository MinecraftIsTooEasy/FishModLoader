package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.gui.GuiIngame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility mixin for {@link GuiIngame}.
 * <p>
 * Most of the Forge patches to {@code GuiIngame.renderGameOverlay} are
 * already satisfied by the access widener (AW) (e.g. exposing fields like
 * {@code zLevel}, {@code renderGameOverlay} itself, etc.) and by the
 * {@code GuiIngameForge} subclass that replaces the vanilla instance.
 * <p>
 * The Forge patch also removes all {@code @SideOnly(Side.CLIENT)} annotations
 * from the class. This is handled automatically at the class-loader level;
 * no mixin intervention is required.
 * <p>
 * Specific body-level changes in {@code renderGameOverlay} that the patch
 * introduces include:
 * <ul>
 *   <li>Delegating helmet overlay to {@code Item.renderHelmetOverlay(...)} when
 *       the worn item is not a pumpkin.</li>
 *   <li>Checking {@code Item.getFontRenderer(ItemStack)} for tooltip text.</li>
 *   <li>Replacing {@code thePlayer.getTotalArmorValue()} with
 *       {@code ForgeHooks.getTotalArmorValue(thePlayer)}.</li>
 * </ul>
 * These are scattered through the middle of the 180+ line method and cannot
 * be applied cleanly via {@code @Inject} at method boundaries. They are
 * handled by the direct patching of the game jar (or by {@code GuiIngameForge}
 * overriding the relevant portions).
 */
@Mixin(GuiIngame.class)
public class GuiIngameMixin {

    /**
     * No-op base method for boss health rendering.
     * <p>
     * {@code GuiIngameForge} overrides this to render the vanilla boss health
     * bar. The method is added here as a {@code @Unique} so that the JVM
     * dispatch works correctly when the superclass (this mixin target) does
     * not natively declare it.
     * <p>
     * Called from {@code GuiIngameForge.renderGameOverlay} or by forge event
     * callbacks.
     */
    @Unique
    public void renderBossHealth() {
        // No-op: boss health bar rendering is provided by GuiIngameForge.
    }
}

package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.gui.GuiCreateWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link GuiCreateWorld}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Customize button delegates to {@code WorldType.onCustomizeButton}.</li>
 *   <li>Customize button visibility uses {@code WorldType.isCustomizable()}.</li>
 * </ul>
 */
@Mixin(GuiCreateWorld.class)
public class GuiCreateWorldMixin {

    /**
     * Placeholder: The patch replaces the hardcoded
     * {@code new GuiCreateFlatWorld(...)} call with
     * {@code WorldType.worldTypes[this.worldTypeId].onCustomizeButton(this.mc, this)}.
     * <p>
     * This is a method-call replacement inside the
     * {@code actionPerformed} body.
     */
    @Unique
    private void fmlForgeOnCustomizeButton() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for onCustomizeButton delegation.");
    }

    /**
     * Placeholder: The patch changes the condition from
     * {@code WorldType.worldTypes[this.worldTypeId] == WorldType.FLAT}
     * to {@code WorldType.worldTypes[this.worldTypeId].isCustomizable()}.
     * <p>
     * This is a simple expression replacement in the drawScreen method.
     */
    @Unique
    private void fmlForgeIsCustomizable() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for isCustomizable check.");
    }
}

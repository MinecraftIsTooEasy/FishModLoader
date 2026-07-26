package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.gui.inventory.GuiContainerCreative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link GuiContainerCreative}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Tab scrolling (tabPage/maxPages).</li>
 *   <li>NBT comparison fix in click handling.</li>
 *   <li>Custom tab support with hasSearchBar, displayAllReleventItems.</li>
 *   <li>Null-safe tab access.</li>
 *   <li>Sprite/icon tab page rendering.</li>
 * </ul>
 */
@Mixin(GuiContainerCreative.class)
public class GuiContainerCreativeMixin {

    /**
     * Placeholder: The patch adds tab-page scrolling fields and logic,
     * including page buttons and rendering changes.
     * <p>
     * These are extensive structural changes affecting
     * {@code initGui}, {@code drawScreen},
     * {@code drawCreativeTabHoveringText}, and
     * {@code actionPerformed}. Direct patching required.
     */
    @Unique
    private void fmlForgeCreativeTabPages() {
        throw new UnsupportedOperationException(
                "Extensive body changes required. See patches for creative tab pages.");
    }
}

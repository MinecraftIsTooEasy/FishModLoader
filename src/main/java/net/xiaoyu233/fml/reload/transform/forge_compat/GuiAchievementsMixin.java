package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.gui.achievement.GuiAchievements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link GuiAchievements}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Add {@link net.minecraftforge.common.AchievementPage} support.</li>
 *   <li>Page-navigation button.</li>
 *   <li>Filter achievements by current page.</li>
 * </ul>
 */
@Mixin(GuiAchievements.class)
public class GuiAchievementsMixin {

    /**
     * Placeholder: The patch adds field {@code currentPage},
     * {@code button}, and {@code minecraftAchievements} to support
     * achievement-page navigation. The constructor, {@code initGui},
     * {@code actionPerformed}, and rendering methods are modified.
     * <p>
     * These are structural changes requiring direct patching.
     */
    @Unique
    private void fmlForgeAchievementPages() {
        throw new UnsupportedOperationException(
                "Extensive body changes required. See patches for achievement pages.");
    }
}

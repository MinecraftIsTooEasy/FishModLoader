package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.gui.GuiChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link GuiChat}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>Auto-complete integrates with {@link net.minecraftforge.client.ClientCommandHandler}.</li>
 *   <li>Uses {@code EnumChatFormatting.func_110646_a} for formatting.</li>
 *   <li>Merges client-side command completions with server completions.</li>
 * </ul>
 */
@Mixin(GuiChat.class)
public class GuiChatMixin {

    /**
     * Placeholder: The patch adds
     * {@code ClientCommandHandler.instance.autoComplete(par1Str, par2Str)}
     * before the Packet203AutoComplete send in the tab-complete method.
     * <p>
     * This is a single-line insertion in the middle of a method body
     * and requires direct patching.
     */
    @Unique
    private void fmlForgeClientAutoComplete() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for ClientCommandHandler.autoComplete injection.");
    }

    /**
     * Placeholder: The patch merges
     * {@code ClientCommandHandler.instance.latestAutoComplete} into
     * the auto-complete array that the method iterates over.
     * <p>
     * This is a body-level change in the middle of the
     * {@code onAutocompleteResponse} method and requires direct
     * patching.
     */
    @Unique
    private void fmlForgeMergeAutoComplete() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for latestAutoComplete merge.");
    }
}

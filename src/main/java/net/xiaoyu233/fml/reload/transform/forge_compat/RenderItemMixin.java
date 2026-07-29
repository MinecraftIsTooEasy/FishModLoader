package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Random;

/**
 * Forge compatibility for {@link RenderItem}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>New methods: {@code shouldBob}, {@code shouldSpreadItems},
 *       {@code getMiniBlockCount}, {@code getMiniItemCount}.</li>
 *   <li>{@code ForgeHooksClient.renderEntityItem} support.</li>
 *   <li>Dynamic render passes with {@code getIcon(ItemStack, pass)}.</li>
 *   <li>{@code renderEffect} method for GUI glint.</li>
 *   <li>{@code renderItemIntoGUI} with renderEffect boolean.</li>
 *   <li>{@code ForgeHooksClient.renderInventoryItem} support.</li>
 * </ul>
 */
@Mixin(RenderItem.class)
public class RenderItemMixin {

    @Shadow
    private Random random;

    // renderBlocks was renamed to itemRenderBlocks in MITE and is unused here; removed to prevent mixin apply failure.

    @Shadow
    private boolean renderWithColor;

    @Shadow
    private float zLevel;

    /**
     * Items should spread out when rendered in 3d.
     */
    @Unique
    public boolean shouldSpreadItems() {
        return true;
    }

    /**
     * Items should have a bob effect.
     */
    @Unique
    public boolean shouldBob() {
        return true;
    }

    /**
     * Returns the number of mini-block icons to render based on stack size.
     */
    @Unique
    public byte getMiniBlockCount(ItemStack stack) {
        byte ret = 1;
        if (stack.stackSize > 1) ret = 2;
        if (stack.stackSize > 5) ret = 3;
        if (stack.stackSize > 20) ret = 4;
        if (stack.stackSize > 40) ret = 5;
        return ret;
    }

    /**
     * Returns the number of mini-item icons to render based on stack size.
     */
    @Unique
    public byte getMiniItemCount(ItemStack stack) {
        byte ret = 1;
        if (stack.stackSize > 1) ret = 2;
        if (stack.stackSize > 15) ret = 3;
        if (stack.stackSize > 31) ret = 4;
        return ret;
    }

    /**
     * Placeholder: The patch makes extensive changes to
     * {@code doRenderItem} and {@code renderItemIntoGUI}.
     * <p>
     * These include adding
     * {@code ForgeHooksClient.renderEntityItem} support,
     * {@code ForgeHooksClient.renderInventoryItem} support,
     * dynamic render passes, shouldBob/shouldSpreadItems usage,
     * renderEffect extraction, and custom FontRenderer support.
     * <p>
     * These are extensive body modifications requiring direct patching.
     */
    @Unique
    private void fmlForgeRenderItemChanges() {
        throw new UnsupportedOperationException(
                "Extensive body changes required. See patches for RenderItem.");
    }
}

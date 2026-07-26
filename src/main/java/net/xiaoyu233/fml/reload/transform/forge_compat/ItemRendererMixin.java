package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link ItemRenderer}.
 * <p>
 * Modifications:
 * <ul>
 *   <li>New overloaded {@code renderItem} with
 *       {@link net.minecraftforge.client.IItemRenderer.ItemRenderType} parameter.</li>
 *   <li>Custom item renderer support via
 *       {@link net.minecraftforge.client.MinecraftForgeClient#getItemRenderer}.</li>
 *   <li>Use {@code itemstack.getItem() instanceof ItemMap}.</li>
 *   <li>Multiple render passes with
 *       {@code getRenderPasses(itemDamage)}.</li>
 *   <li>Use {@code hasEffect(pass)} with pass parameter.</li>
 * </ul>
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    /**
     * Placeholder: The patch renames the existing
     * {@code renderItem(EntityLivingBase, ItemStack, int)} to add a
     * 4th {@code ItemRenderType} parameter, and adds a 3-arg overload
     * that calls the 4-arg version with {@code EQUIPPED}.
     * <p>
     * This is a method signature change that also adds custom renderer
     * logic. Requires direct patching.
     */
    @Unique
    private void fmlForgeRenderItemWithType() {
        throw new UnsupportedOperationException(
                "Signature change required. See patches for renderItem with ItemRenderType.");
    }

    /**
     * Placeholder: The patch changes the map rendering check from
     * {@code itemstack.itemID == Item.map.itemID} to
     * {@code itemstack.getItem() instanceof ItemMap}.
     * <p>
     * Also adds custom map renderer support.
     */
    @Unique
    private void fmlForgeRenderMap() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for ItemMap check and custom map renderer.");
    }

    /**
     * Placeholder: The patch changes the multiple-render-pass loop
     * in {@code renderItemInFirstPerson} from a fixed 2-pass to a
     * dynamic pass count using {@code getRenderPasses(itemDamage)}.
     */
    @Unique
    private void fmlForgeMultipleRenderPasses() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for dynamic render passes.");
    }
}

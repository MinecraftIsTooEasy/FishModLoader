package net.xiaoyu233.fml.reload.transform.forge_compat;

import net.minecraft.client.renderer.entity.RenderSnowMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Forge compatibility for {@link RenderSnowMan}.
 * <p>
 * The patch uses {@code ItemBlock} instanceof check and custom item
 * renderer support for the pumpkin head.
 */
@Mixin(RenderSnowMan.class)
public class RenderSnowManMixin {

    /**
     * Placeholder: The patch changes the item check from
     * {@code itemstack.getItem().itemID < 256} to
     * {@code itemstack.getItem() instanceof ItemBlock} and adds
     * custom item renderer support.
     * <p>
     * This is a body modification inside {@code renderEquippedItems}.
     */
    @Unique
    private void fmlForgeSnowManHead() {
        throw new UnsupportedOperationException(
                "Body modification required. See patches for snowman head rendering.");
    }
}

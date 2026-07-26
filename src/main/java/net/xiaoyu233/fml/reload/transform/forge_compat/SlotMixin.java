package net.xiaoyu233.fml.reload.transform.forge_compat;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.inventory.Slot;
import net.minecraft.util.Icon;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public class SlotMixin {
    @Shadow
    private int slotIndex;

    /**
     * Custom background icon for this slot, normally null which causes no background to be drawn.
     */
    @Unique
    protected Icon backgroundIcon = null;

    /**
     * Background texture file assigned to this slot, if any.
     * Vanilla "/gui/items.png" is used if this is null.
     */
    @Unique
    @SideOnly(Side.CLIENT)
    protected ResourceLocation texture;

    /**
     * Returns the custom background icon index instead of null.
     */
    @Inject(method = "getBackgroundIconIndex()Lnet/minecraft/util/Icon;",
            at = @At("RETURN"),
            cancellable = true)
    private void fmlForgeGetBackgroundIconIndex(CallbackInfoReturnable<Icon> cir) {
        cir.setReturnValue(this.backgroundIcon);
    }

    /**
     * Gets the path of the texture file to use for the background image of this slot when drawing the GUI.
     *
     * @return The texture file that will be used in GuiContainer.drawSlotInventory for the slot background.
     */
    @Unique
    @SideOnly(Side.CLIENT)
    public ResourceLocation getBackgroundIconTexture() {
        return this.texture == null ? TextureMap.locationItemsTexture : this.texture;
    }

    /**
     * Sets which icon index to use as the background image of the slot when it's empty.
     *
     * @param icon The icon to use, null for none
     */
    @Unique
    public void setBackgroundIcon(Icon icon) {
        this.backgroundIcon = icon;
    }

    /**
     * Gets the custom background icon index for this slot.
     *
     * @return The background icon, or null if none is set
     */
    @Unique
    public Icon getBackgroundIcon() {
        return this.backgroundIcon;
    }

    /**
     * Sets the texture file to use for the background image of the slot when it's empty.
     *
     * @param textureFilename Path of texture file to use, or null to use "/gui/items.png"
     */
    @Unique
    @SideOnly(Side.CLIENT)
    public void setBackgroundIconTexture(ResourceLocation texture) {
        this.texture = texture;
    }

    /**
     * Retrieves the index in the inventory for this slot.
     *
     * @return Index in associated inventory for this slot.
     */
    @Unique
    public int getSlotIndex() {
        return this.slotIndex;
    }
}

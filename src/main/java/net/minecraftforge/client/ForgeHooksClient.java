package net.minecraftforge.client;

import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.IPlantable;

/**
 * STUB. The original ForgeHooksClient is in tight contact with vanilla
 * client internals (private Minecraft.renderEngine, Item.getArmorTexture,
 * Tessellator.renderingWorldRenderer, Block.isBed) which MITE removed or
 * renamed. We keep the public method surface that Forge mods call so they
 * can compile/load; full behaviour returns benign defaults.
 *
 * <p>Stage 7b will re-implement these against MITE's rendering pipeline
 * via Mixin.
 */
public class ForgeHooksClient {

    /** Current render pass — used by transparency-aware block renderers. */
    public static int renderPass = 0;
    /** OpenGL stencil-bit count requested by Forge mods on the framebuffer. */
    public static int stencilBits = 0;

    public static String getArmorTexture(Entity entity, ItemStack armor, String defaultTexture, int slot, int layer) {
        return defaultTexture;
    }

    public static void renderEquippedItem(int renderViewId, float partialTicks) {}

    public static boolean postMouseEvent() {
        return false;
    }

    public static float getOffsetFOV(EntityClientPlayerMP player, float fov) {
        return fov;
    }

    public static void onTextureStitchedPre(net.minecraft.client.renderer.texture.TextureMap map) {}

    public static void onTextureStitchedPost(net.minecraft.client.renderer.texture.TextureMap map) {}

    public static ModelBiped getArmorModel(EntityLivingBase entity, ItemStack itemStack, int slot, ModelBiped baseModel) {
        return baseModel;
    }

    public static void orientBedCamera(net.minecraft.world.IBlockAccess world, int x, int y, int z,
                                       Block block, EntityLivingBase entity) {
        // best-effort no-op; mods relying on bed-orientation get the vanilla one
    }

    public static boolean canRenderInPass(Block block, int pass) {
        return pass == 0;
    }

    public static boolean fillCustomPlantModel(IPlantable plantable, int meta) {
        return false;
    }

    public static RenderManager getRenderManager() {
        return RenderManager.instance;
    }

    public static TextureManager engine() {
        // MITE made Minecraft.renderEngine private; a real port goes through
        // an AT or Mixin accessor. Until then this returns null.
        return null;
    }
}

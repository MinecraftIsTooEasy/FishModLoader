package net.xiaoyu233.fml.vulkan.mixin.nat.item;

import net.minecraft.DynamicTexture;
import net.minecraft.EntityPlayer;
import net.minecraft.MapColor;
import net.minecraft.MapCoord;
import net.minecraft.MapData;
import net.minecraft.MapItemRenderer;
import net.minecraft.ResourceLocation;
import net.minecraft.Tessellator;
import net.minecraft.TextureManager;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * NR-14: native MapItemRenderer. Same arithmetic as MITE; the 128x128 pixel update goes through
 * {@link Textures#uploadArgb} (versioned dynamic texture, recorded in the upload command buffer, no render-pass
 * break) and GL state/matrix calls are replaced by {@link Draw}. Method name and descriptor are unchanged so the
 * Twilight Forest WrapOperation on renderMap keeps matching.
 */
@Mixin(MapItemRenderer.class)
public abstract class MapItemRendererItemMixin {
    @Shadow @Final
    private static ResourceLocation field_111277_a;
    @Shadow @Final
    private DynamicTexture bufferedImage;
    @Shadow
    private int[] intArray;
    @Shadow @Final
    private ResourceLocation field_111276_e;

    /** @author NR-14 @reason native Vulkan map rendering */
    @Overwrite
    public void renderMap(EntityPlayer var1, TextureManager var2, MapData var3) {
        int[] px = this.intArray;
        for (int var4 = 0; var4 < 16384; var4++) {
            byte var5 = var3.colors[var4];
            if (var5 / 4 == 0) {
                px[var4] = (var4 + var4 / 128 & 1) * 8 + 16 << 24;
            } else {
                int var6 = MapColor.mapColorArray[var5 / 4].colorValue;
                int var7 = var5 & 3;
                short var8 = 220;
                if (var7 == 2) {
                    var8 = 255;
                }
                if (var7 == 0) {
                    var8 = 180;
                }
                int var9 = (var6 >> 16 & 0xFF) * var8 / 255;
                int var10 = (var6 >> 8 & 0xFF) * var8 / 255;
                int var11 = (var6 & 0xFF) * var8 / 255;
                px[var4] = 0xFF000000 | var9 << 16 | var10 << 8 | var11;
            }
        }

        Textures.uploadArgb(this.bufferedImage.getGlTextureId(), px, 128, 128, 0, 0, false, false);
        byte var15 = 0;
        byte var16 = 0;
        Tessellator var17 = Tessellator.instance;
        float var18 = 0.0F;
        var2.bindTexture(this.field_111276_e);
        Draw.blend(true);
        Draw.blendFunc(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Draw.alphaTest(false);
        var17.startDrawingQuads();
        var17.addVertexWithUV(var15 + 0 + var18, var16 + 128 - var18, -0.01F, 0.0, 1.0);
        var17.addVertexWithUV(var15 + 128 - var18, var16 + 128 - var18, -0.01F, 1.0, 1.0);
        var17.addVertexWithUV(var15 + 128 - var18, var16 + 0 + var18, -0.01F, 1.0, 0.0);
        var17.addVertexWithUV(var15 + 0 + var18, var16 + 0 + var18, -0.01F, 0.0, 0.0);
        var17.draw();
        Draw.alphaTest(true);
        Draw.blend(false);
        var2.bindTexture(field_111277_a);
        int var19 = 0;

        for (Object var20 : var3.playersVisibleOnMap.values()) {
            MapCoord var21 = (MapCoord) var20;
            Draw.pushPose();
            Draw.translate(var15 + var21.centerX / 2.0F + 64.0F, var16 + var21.centerZ / 2.0F + 64.0F, -0.02F);
            Draw.rotate(var21.iconRotation * 360 / 16.0F, 0.0F, 0.0F, 1.0F);
            Draw.scale(4.0F, 4.0F, 3.0F);
            Draw.translate(-0.125F, 0.125F, 0.0F);
            float var22 = (var21.iconSize % 4 + 0) / 4.0F;
            float var12 = (var21.iconSize / 4 + 0) / 4.0F;
            float var13 = (var21.iconSize % 4 + 1) / 4.0F;
            float var14 = (var21.iconSize / 4 + 1) / 4.0F;
            var17.startDrawingQuads();
            var17.addVertexWithUV(-1.0, 1.0, var19 * 0.001F, var22, var12);
            var17.addVertexWithUV(1.0, 1.0, var19 * 0.001F, var13, var12);
            var17.addVertexWithUV(1.0, -1.0, var19 * 0.001F, var13, var14);
            var17.addVertexWithUV(-1.0, -1.0, var19 * 0.001F, var22, var14);
            var17.draw();
            Draw.popPose();
            var19++;
        }
    }
}

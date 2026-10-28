package net.xiaoyu233.fml.vulkan.mixin.nat.gui;

import net.minecraft.*;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** NR-17: second GuiIngame mixin (disjoint methods: renderPumpkinBlur, renderVisionDim, renderRunegateEffect, renderVignette, func_130015_b, renderInventorySlot, renderBossHealth). GuiIngameGuiMixin owns renderGameOverlay, func_96136_a, func_110327_a, drawTournamentScore, drawAllottedTime. */
@Mixin(GuiIngame.class)
public abstract class GuiIngameEffectsGuiMixin extends Gui {
    @Shadow @Final private Minecraft mc;
    @Shadow @Final private static ResourceLocation vignetteTexPath;
    @Shadow @Final private static ResourceLocation pumpkinBlurTexPath;
    @Shadow @Final private static RenderItem itemRenderer;
    @Shadow public float prevVignetteBrightness;

    @Overwrite
    private void renderBossHealth() {
        if (BossStatus.bossName != null && BossStatus.statusBarLength > 0) {
            BossStatus.statusBarLength--;
            FontRenderer var1 = this.mc.fontRenderer;
            ScaledResolution var2 = new ScaledResolution(this.mc.gameSettings, this.mc.displayWidth, this.mc.displayHeight);
            int var3 = var2.getScaledWidth();
            short var4 = 182;
            int var5 = var3 / 2 - var4 / 2;
            int var6 = (int) (BossStatus.healthScale * (var4 + 1));
            byte var7 = 12;
            this.drawTexturedModalRect(var5, var7, 0, 74, var4, 5);
            this.drawTexturedModalRect(var5, var7, 0, 74, var4, 5);
            if (var6 > 0) {
                this.drawTexturedModalRect(var5, var7, 0, 79, var6, 5);
            }

            String var8 = BossStatus.bossName;
            var1.drawStringWithShadow(var8, var3 / 2 - var1.getStringWidth(var8) / 2, var7 - 10, 16777215);
            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            this.mc.getTextureManager().bindTexture(icons);
        }
    }

    @Overwrite
    private void renderPumpkinBlur(int par1, int par2) {
        Draw.depthTest(false);
        Draw.depthMask(false);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        Draw.alphaTest(false);
        this.mc.getTextureManager().bindTexture(pumpkinBlurTexPath);
        Tessellator var3 = Tessellator.instance;
        var3.startDrawingQuads();
        var3.addVertexWithUV(0.0, par2, -90.0, 0.0, 1.0);
        var3.addVertexWithUV(par1, par2, -90.0, 1.0, 1.0);
        var3.addVertexWithUV(par1, 0.0, -90.0, 1.0, 0.0);
        var3.addVertexWithUV(0.0, 0.0, -90.0, 0.0, 0.0);
        var3.draw();
        Draw.depthMask(true);
        Draw.depthTest(true);
        Draw.alphaTest(true);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Overwrite
    private void renderVisionDim(int par1, int par2, float vision_dimming) {
        boolean gl_texture_2d = Draw.texturingEnabled();
        Draw.texturing(false);
        Draw.depthTest(false);
        Draw.depthMask(false);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Draw.color(0.0F, 0.0F, 0.0F, Math.min(vision_dimming, 1.0F));
        Draw.alphaTest(false);
        Tessellator var3 = Tessellator.instance;
        var3.startDrawingQuads();
        var3.addVertexWithUV(0.0, par2, -90.0, 0.0, 1.0);
        var3.addVertexWithUV(par1, par2, -90.0, 1.0, 1.0);
        var3.addVertexWithUV(par1, 0.0, -90.0, 1.0, 0.0);
        var3.addVertexWithUV(0.0, 0.0, -90.0, 0.0, 0.0);
        var3.draw();
        Draw.depthMask(true);
        Draw.depthTest(true);
        Draw.alphaTest(true);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        if (gl_texture_2d) {
            Draw.texturing(true);
        }
    }

    @Overwrite
    private void renderRunegateEffect(int par1, int par2) {
        Draw.depthTest(false);
        Draw.depthMask(false);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        int r;
        int g;
        int b;
        if (this.mc.theWorld.isOverworld()) {
            r = 53;
            g = 159;
            b = 255;
        } else if (this.mc.theWorld.isUnderworld()) {
            r = 68;
            g = 1;
            b = 180;
        } else if (this.mc.theWorld.isTheNether()) {
            r = 228;
            g = 123;
            b = 78;
        } else {
            r = 255;
            g = 255;
            b = 255;
        }

        Draw.color(r / 255.0F, g / 255.0F, b / 255.0F, Math.min(this.mc.thePlayer.runegate_counter / 20.0F, 1.0F));
        Draw.alphaTest(false);
        Draw.texturing(false);
        Tessellator var3 = Tessellator.instance;
        var3.startDrawingQuads();
        var3.addVertexWithUV(0.0, par2, -90.0, 0.0, 1.0);
        var3.addVertexWithUV(par1, par2, -90.0, 1.0, 1.0);
        var3.addVertexWithUV(par1, 0.0, -90.0, 1.0, 0.0);
        var3.addVertexWithUV(0.0, 0.0, -90.0, 0.0, 0.0);
        var3.draw();
        Draw.depthMask(true);
        Draw.depthTest(true);
        Draw.alphaTest(true);
        Draw.texturing(true);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Overwrite
    private void renderVignette(float par1, int par2, int par3) {
        par1 = 1.0F - par1;
        if (par1 < 0.0F) {
            par1 = 0.0F;
        }

        if (par1 > 1.0F) {
            par1 = 1.0F;
        }

        this.prevVignetteBrightness = (float) (this.prevVignetteBrightness + (par1 - this.prevVignetteBrightness) * 0.01);
        if (this.mc.theWorld.provider.drawGuiVignette()) {
            Draw.depthTest(false);
            Draw.depthMask(false);
            Draw.blendFunc(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_COLOR);
            Draw.color(this.prevVignetteBrightness, this.prevVignetteBrightness, this.prevVignetteBrightness, 1.0F);
            this.mc.getTextureManager().bindTexture(vignetteTexPath);
            Tessellator var4 = Tessellator.instance;
            var4.startDrawingQuads();
            var4.addVertexWithUV(0.0, par3, -90.0, 0.0, 1.0);
            var4.addVertexWithUV(par2, par3, -90.0, 1.0, 1.0);
            var4.addVertexWithUV(par2, 0.0, -90.0, 1.0, 0.0);
            var4.addVertexWithUV(0.0, 0.0, -90.0, 0.0, 0.0);
            var4.draw();
        } else {
            this.prevVignetteBrightness = 1.0F;
        }

        Draw.depthMask(true);
        Draw.depthTest(true);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
    }

    @Overwrite
    private void func_130015_b(float par1, int par2, int par3) {
        if (par1 < 1.0F) {
            par1 *= par1;
            par1 *= par1;
            par1 = par1 * 0.8F + 0.2F;
        }

        Draw.alphaTest(false);
        Draw.depthTest(false);
        Draw.depthMask(false);
        Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
        Draw.color(1.0F, 1.0F, 1.0F, par1);
        Icon var4 = Block.portal.getBlockTextureFromSide(1);
        this.mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        float var5 = var4.getMinU();
        float var6 = var4.getMinV();
        float var7 = var4.getMaxU();
        float var8 = var4.getMaxV();
        Tessellator var9 = Tessellator.instance;
        var9.startDrawingQuads();
        var9.addVertexWithUV(0.0, par3, -90.0, var5, var8);
        var9.addVertexWithUV(par2, par3, -90.0, var7, var8);
        var9.addVertexWithUV(par2, 0.0, -90.0, var7, var6);
        var9.addVertexWithUV(0.0, 0.0, -90.0, var5, var6);
        var9.draw();
        Draw.depthMask(true);
        Draw.depthTest(true);
        Draw.alphaTest(true);
        Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Overwrite
    private void renderInventorySlot(int par1, int par2, int par3, float par4) {
        ItemStack var5 = this.mc.thePlayer.inventory.mainInventory[par1];
        if (var5 != null) {
            float var6 = var5.animationsToGo - par4;
            if (var6 > 0.0F) {
                Draw.pushPose();
                float var7 = 1.0F + var6 / 5.0F;
                Draw.translate((float) (par2 + 8), (float) (par3 + 12), 0.0F);
                Draw.scale(1.0F / var7, (var7 + 1.0F) / 2.0F, 1.0F);
                Draw.translate((float) -(par2 + 8), (float) -(par3 + 12), 0.0F);
            }

            itemRenderer.renderItemAndEffectIntoGUI(this.mc.fontRenderer, this.mc.getTextureManager(), var5, par2, par3);
            if (var6 > 0.0F) {
                Draw.popPose();
            }

            itemRenderer.renderItemOverlayIntoGUI(this.mc.fontRenderer, this.mc.getTextureManager(), var5, par2, par3);
        }
    }
}

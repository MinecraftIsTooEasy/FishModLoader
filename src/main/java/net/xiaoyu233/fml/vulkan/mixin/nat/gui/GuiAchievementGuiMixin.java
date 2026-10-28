package net.xiaoyu233.fml.vulkan.mixin.nat.gui;

import net.minecraft.Achievement;
import net.minecraft.Gui;
import net.minecraft.GuiAchievement;
import net.minecraft.Minecraft;
import net.minecraft.RenderHelper;
import net.minecraft.RenderItem;
import net.minecraft.ResourceLocation;
import net.minecraft.ScaledResolution;
import net.minecraft.client.main.Main;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "gui": achievement toast with its own viewport, ortho projection and depth clear (as MITE, no restore). */
@Mixin(GuiAchievement.class)
public abstract class GuiAchievementGuiMixin extends Gui {
    @Shadow @Final
    private static ResourceLocation achievementTextures;
    @Shadow
    private Minecraft theGame;
    @Shadow
    private int achievementWindowWidth;
    @Shadow
    private int achievementWindowHeight;
    @Shadow
    private String achievementGetLocalText;
    @Shadow
    private String achievementStatName;
    @Shadow
    private Achievement theAchievement;
    @Shadow
    private long achievementTime;
    @Shadow
    private RenderItem itemRender;
    @Shadow
    private boolean haveAchiement;

    /**
     * @author fml-native
     * @reason native renderer: Frame.viewport / Draw.projection instead of GL matrix calls
     */
    @Overwrite
    private void updateAchievementWindowScale() {
        Frame.viewport(0, 0, this.theGame.displayWidth, this.theGame.displayHeight);
        Draw.projection().loadIdentity();
        Draw.pose().loadIdentity();
        this.achievementWindowWidth = this.theGame.displayWidth;
        this.achievementWindowHeight = this.theGame.displayHeight;
        ScaledResolution var1 = new ScaledResolution(this.theGame.gameSettings, this.theGame.displayWidth, this.theGame.displayHeight);
        this.achievementWindowWidth = var1.getScaledWidth();
        this.achievementWindowHeight = var1.getScaledHeight();
        Frame.clearDepth();
        Draw.projection().loadIdentity();
        Draw.projection().ortho(0.0, this.achievementWindowWidth, this.achievementWindowHeight, 0.0, 1000.0, 3000.0);
        Draw.pose().loadIdentity();
        Draw.translate(0.0F, 0.0F, -2000.0F);
    }

    /**
     * @author fml-native
     * @reason native renderer: Draw state instead of GL11
     */
    @Overwrite
    public void updateAchievementWindow() {
        if (Minecraft.theMinecraft.gameSettings.gui_mode == 0 && !Main.is_MITE_DS) {
            if (this.theAchievement != null && this.achievementTime != 0L) {
                double var1 = (Minecraft.getSystemTime() - this.achievementTime) / 3000.0;
                if (this.haveAchiement || !(var1 < 0.0) && !(var1 > 1.0)) {
                    this.updateAchievementWindowScale();
                    Draw.depthTest(false);
                    Draw.depthMask(false);
                    double var3 = var1 * 2.0;
                    if (var3 > 1.0) {
                        var3 = 2.0 - var3;
                    }

                    var3 *= 4.0;
                    var3 = 1.0 - var3;
                    if (var3 < 0.0) {
                        var3 = 0.0;
                    }

                    var3 *= var3;
                    var3 *= var3;
                    int var5 = this.achievementWindowWidth - 160;
                    int var6 = 0 - (int) (var3 * 36.0);
                    Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
                    Draw.texturing(true);
                    this.theGame.getTextureManager().bindTexture(achievementTextures);
                    Draw.lighting(false);
                    this.drawTexturedModalRect(var5, var6, 96, 202, 160, 32);
                    if (this.haveAchiement) {
                        this.theGame.fontRenderer.drawSplitString(this.achievementStatName, var5 + 30, var6 + 7, 120, -1);
                    } else {
                        this.theGame.fontRenderer.drawString(this.achievementGetLocalText, var5 + 30, var6 + 7, -256);
                        this.theGame.fontRenderer.drawString(this.achievementStatName, var5 + 30, var6 + 18, -1);
                    }

                    RenderHelper.enableGUIStandardItemLighting();
                    Draw.lighting(false);
                    Draw.rescaleNormal(true);
                    Draw.colorMaterialEnabled(true);
                    Draw.lighting(true);
                    this.itemRender.renderItemAndEffectIntoGUI(this.theGame.fontRenderer, this.theGame.getTextureManager(),
                        this.theAchievement.theItemStack, var5 + 8, var6 + 8);
                    Draw.lighting(false);
                    Draw.depthMask(true);
                    Draw.depthTest(true);
                } else {
                    this.achievementTime = 0L;
                }
            }
        }
    }
}

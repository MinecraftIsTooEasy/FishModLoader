package net.xiaoyu233.fml.vulkan.mixin.nat.gui;

import java.util.List;
import net.minecraft.ChatLine;
import net.minecraft.Gui;
import net.minecraft.GuiMITEDS;
import net.minecraft.GuiNewChat;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.StringUtils;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "gui": chat drawing through Draw pose/blend state. */
@Mixin(GuiNewChat.class)
public abstract class GuiNewChatGuiMixin extends Gui {
    @Shadow @Final
    private Minecraft mc;
    @Shadow @Final
    private List field_96134_d;
    @Shadow
    private int field_73768_d;
    @Shadow
    private boolean field_73769_e;

    @Shadow
    public abstract int func_96127_i();

    @Shadow
    public abstract float func_96131_h();

    @Shadow
    public abstract int func_96126_f();

    @Shadow
    public abstract boolean getChatOpen();

    /**
     * @author fml-native
     * @reason native renderer: Draw pose/blend instead of GL11
     */
    @Overwrite
    public void drawChat(int par1) {
        if (this.mc.gameSettings.gui_mode == 0) {
            if (this.mc.gameSettings.chatVisibility != 2) {
                int var2 = this.func_96127_i();
                boolean var3 = false;
                int var4 = 0;
                int var5 = this.field_96134_d.size();
                float var6 = this.mc.gameSettings.chatOpacity * 0.9F + 0.1F;
                if (var5 > 0) {
                    if (this.getChatOpen()) {
                        var3 = true;
                    }

                    float var7 = this.func_96131_h();
                    int var8 = MathHelper.ceiling_float_int(this.func_96126_f() / var7);
                    Draw.pushPose();
                    Draw.translate(2.0F, 20.0F, 0.0F);
                    Draw.scale(var7, var7, 1.0F);

                    for (int var9 = 0; var9 + this.field_73768_d < this.field_96134_d.size() && var9 < var2; var9++) {
                        ChatLine var10 = (ChatLine) this.field_96134_d.get(var9 + this.field_73768_d);
                        if (var10 != null) {
                            int var11 = par1 - var10.getUpdatedCounter();
                            if (var11 < 200 || var3) {
                                double var12 = var11 / 200.0;
                                var12 = 1.0 - var12;
                                var12 *= 10.0;
                                if (var12 < 0.0) {
                                    var12 = 0.0;
                                }

                                if (var12 > 1.0) {
                                    var12 = 1.0;
                                }

                                var12 *= var12;
                                int var14 = (int) (255.0 * var12);
                                if (var3) {
                                    var14 = 255;
                                }

                                var14 = (int) (var14 * var6);
                                var4++;
                                if (var14 > 3) {
                                    byte var15 = 0;
                                    int var16 = -var9 * 9;
                                    var16 -= 18;
                                    if (!(this.mc.currentScreen instanceof GuiMITEDS)) {
                                        drawRect(var15, var16 - 9, var15 + var8 + 4, var16, var14 / 2 << 24);
                                    }

                                    Draw.blend(true);
                                    String var17 = var10.getChatLineString();
                                    if (!this.mc.gameSettings.chatColours) {
                                        var17 = StringUtils.stripControlCodes(var17);
                                    }

                                    this.mc.fontRenderer.drawStringWithShadow(var17, var15, var16 - 8, 16777215 + (var14 << 24));
                                }
                            }
                        }
                    }

                    if (var3) {
                        int var181 = this.mc.fontRenderer.FONT_HEIGHT;
                        Draw.translate(-3.0F, 0.0F, 0.0F);
                        int var18x = var5 * var181 + var5;
                        int var11 = var4 * var181 + var4;
                        int var20 = this.field_73768_d * var11 / var5;
                        int var13 = var11 * var11 / var18x;
                        var20 += var181 * 2;
                        var13 -= var181 * 2 + 2;
                        if (var18x != var11) {
                            int var14 = var20 > 0 ? 170 : 96;
                            int var19 = this.field_73769_e ? 13382451 : 3355562;
                            drawRect(0, -var20, 2, -var20 - var13, var19 + (var14 << 24));
                            drawRect(2, -var20, 1, -var20 - var13, 13421772 + (var14 << 24));
                        }
                    }

                    Draw.popPose();
                }
            }
        }
    }
}

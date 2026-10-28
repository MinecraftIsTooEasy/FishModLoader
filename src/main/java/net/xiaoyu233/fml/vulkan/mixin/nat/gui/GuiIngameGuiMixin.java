package net.xiaoyu233.fml.vulkan.mixin.nat.gui;

import java.awt.Color;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import net.minecraft.*;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "gui": the in-game HUD. Every GL statement of the MITE methods is replaced by the {@link Draw} statement of design 3.1; all
 * non-render logic, MITE overlays, hooks and text are kept. Hotbar/icon rects go through the Gui primitives (GuiBatch), so they merge.
 * The private effect methods (vignette, pumpkin, ...) are implemented in {@link GuiIngameEffectsGuiMixin}.
 */
@Mixin(GuiIngame.class)
public abstract class GuiIngameGuiMixin extends Gui {
    @Shadow @Final private Random rand;
    @Shadow @Final private Minecraft mc;
    @Shadow @Final private GuiNewChat persistantChatGUI;
    @Shadow private int updateCounter;
    @Shadow private String recordPlaying;
    @Shadow private int recordPlayingUpFor;
    @Shadow private boolean recordIsPlaying;
    @Shadow private int remainingHighlightTicks;
    @Shadow private ItemStack highlightingItemStack;
    @Shadow @Final protected static ResourceLocation MITE_icons;
    @Shadow public static long display_overburdened_cpu_icon_until_ms;
    @Shadow public int curse_notification_counter;
    @Shadow private String last_highlighting_item_stack_text;
    @Shadow public static int allotted_time;
    @Shadow public static int server_load;
    @Shadow @Final private static ResourceLocation widgetsTexPath;

    @Shadow protected abstract void renderVignette(float brightness, int width, int height);
    @Shadow protected abstract void renderPumpkinBlur(int width, int height);
    @Shadow protected abstract void renderVisionDim(int width, int height, float dimming);
    @Shadow protected abstract void renderRunegateEffect(int width, int height);
    @Shadow protected abstract void renderBossHealth();
    @Shadow protected abstract void renderInventorySlot(int slot, int x, int y, float partialTicks);

    /**
     * MITE dedicated-server (GuiMITEDS) chat path: the chat is drawn 190 px down the screen. The GL matrix calls become {@link Draw} pose calls.
     *
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void drawChatForMITEDS() {
        Draw.pushPose();
        Draw.translate(0.0F, 190.0F, 0.0F);
        this.persistantChatGUI.drawChat(this.updateCounter);
        Draw.popPose();
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
   public void renderGameOverlay(float par1, boolean par2, int par3, int par4) {
      ScaledResolution var5 = new ScaledResolution(this.mc.gameSettings, this.mc.displayWidth, this.mc.displayHeight);
      int var6 = var5.getScaledWidth();
      int var7 = var5.getScaledHeight();
      FontRenderer var8 = this.mc.fontRenderer;
      this.mc.entityRenderer.setupOverlayRendering();
      if (!this.mc.thePlayer.isGhost()) {
         Draw.blend(true);
         int skylight_subtracted = this.mc.theWorld.skylightSubtracted;
         int skylight_subtracted_ignoring_rain_and_thunder = this.mc.theWorld.skylight_subtracted_ignoring_rain_and_thunder;
         this.mc.theWorld.skylightSubtracted = 0;
         this.mc.theWorld.skylight_subtracted_ignoring_rain_and_thunder = 0;
         float vignette_brightness = this.mc.thePlayer.getBrightness(par1);
         this.mc.theWorld.skylightSubtracted = skylight_subtracted;
         this.mc.theWorld.skylight_subtracted_ignoring_rain_and_thunder = skylight_subtracted_ignoring_rain_and_thunder;
         if (this.mc.thePlayer.is_cursed && vignette_brightness > 0.0F) {
            vignette_brightness = 0.0F;
         }

         if ((Minecraft.isFancyGraphicsEnabled() || this.mc.thePlayer.is_cursed) && this.mc.gameSettings.gui_mode != 2) {
            this.renderVignette(vignette_brightness, var6, var7);
         } else {
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
         }

         if (this.mc.gameSettings.thirdPersonView == 0 && this.mc.thePlayer.isWearingPumpkinHelmet()) {
            this.renderPumpkinBlur(var6, var7);
         }

         if (!this.mc.thePlayer.isPotionActive(Potion.confusion)) {
            float var10 = this.mc.thePlayer.prevTimeInPortal + (this.mc.thePlayer.timeInPortal - this.mc.thePlayer.prevTimeInPortal) * par1;
         }

         float interpolated_vision_dimming = this.mc.thePlayer.vision_dimming - par1 * 0.01F;
         if (interpolated_vision_dimming > 0.01F) {
            this.renderVisionDim(var6, var7, Math.min(interpolated_vision_dimming, 0.9F));
         }

         if (this.mc.thePlayer.runegate_counter > 0) {
            this.renderRunegateEffect(var6, var7);
         }

         int eye_block_x = this.mc.thePlayer.getBlockPosX();
         int eye_block_y = MathHelper.floor_double(this.mc.thePlayer.getEyePosY() - 0.05F);
         int eye_block_z = this.mc.thePlayer.getBlockPosZ();
         Block block = this.mc.theWorld.getBlock(eye_block_x, eye_block_y, eye_block_z);
         boolean prevent_xray_vision = this.mc.theWorld.isBlockSolid(block, eye_block_x, eye_block_y, eye_block_z) && block != Block.glass;
         if (prevent_xray_vision && !block.isOpaqueStandardFormCube(this.mc.theWorld, eye_block_x, eye_block_y, eye_block_z)) {
            prevent_xray_vision = false;
         }

         if (prevent_xray_vision) {
            Draw.depthTest(false);
            Draw.alphaTest(false);
            drawRect(0, 0, var6, var7, -16777216);
            Draw.alphaTest(true);
            Draw.depthTest(true);
         }

         int sleep_counter = this.mc.thePlayer.falling_asleep_counter;
         if (sleep_counter > 0) {
            int alpha = Math.min(255 * sleep_counter / 100, 255);
            this.mc.mcProfiler.startSection("sleep");
            Draw.depthTest(false);
            Draw.alphaTest(false);
            drawRect(0, 0, var6, var7, alpha << 24);
            Draw.alphaTest(true);
            Draw.depthTest(true);
            this.mc.mcProfiler.endSection();
         }

         if (!this.mc.playerController.enableEverythingIsScrewedUpMode() && this.mc.gameSettings.gui_mode == 0) {
            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            if (display_overburdened_cpu_icon_until_ms >= System.currentTimeMillis()) {
               this.mc.getTextureManager().bindTexture(MITE_icons);
               this.drawTexturedModalRect(var6 - 17, 2, 0, 0, 16, 16);
            }

            this.mc.getTextureManager().bindTexture(widgetsTexPath);
            InventoryPlayer var31 = this.mc.thePlayer.inventory;
            this.zLevel = -90.0F;
            this.drawTexturedModalRect(var6 / 2 - 91, var7 - 22, 0, 0, 182, 22);
            if (this.mc.playerController.auto_use_mode_item != null) {
               Draw.color(0.8F, 1.0F, 0.7F, 1.0F);
            }

            this.drawTexturedModalRect(var6 / 2 - 91 - 1 + var31.currentItem * 20, var7 - 22 - 1, 0, 22, 24, 22);
            if (this.mc.playerController.auto_use_mode_item != null) {
               Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            }

            this.mc.getTextureManager().bindTexture(icons);
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.ONE_MINUS_DST_COLOR, BlendFactor.ONE_MINUS_SRC_COLOR);
            this.drawTexturedModalRect(var6 / 2 - 7, var7 / 2 - 7, 0, 0, 16, 16);
            Draw.blend(false);
            this.mc.mcProfiler.startSection("bossHealth");
            this.renderBossHealth();
            this.mc.mcProfiler.endSection();
            if (this.mc.playerController.shouldDrawHUD()) {
               this.func_110327_a(var6, var7);
            }

            Draw.blend(false);
            this.mc.mcProfiler.startSection("actionBar");
            Draw.rescaleNormal(true);
            RenderHelper.enableGUIStandardItemLighting();

            for (int var11 = 0; var11 < 9; var11++) {
               int var12 = var6 / 2 - 90 + var11 * 20 + 2;
               int var13 = var7 - 16 - 3;
               this.renderInventorySlot(var11, var12, var13, par1);
            }

            RenderHelper.disableStandardItemLighting();
            Draw.rescaleNormal(false);
            this.mc.mcProfiler.endSection();
         }

         int var32 = 16777215;
         Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
         int var11 = var6 / 2 - 91;
         if (this.mc.thePlayer.isRidingHorse() && this.mc.gameSettings.gui_mode == 0) {
            this.mc.mcProfiler.startSection("jumpBar");
            this.mc.getTextureManager().bindTexture(Gui.icons);
            float var33 = this.mc.thePlayer.getHorseJumpPower();
            short var37 = 182;
            int var14 = (int)(var33 * (var37 + 1));
            int var15 = var7 - 32 + 3;
            this.drawTexturedModalRect(var11, var15, 0, 84, var37, 5);
            if (var14 > 0) {
               this.drawTexturedModalRect(var11, var15, 0, 89, var14, 5);
            }

            this.mc.mcProfiler.endSection();
         } else if (this.mc.playerController.func_78763_f() && this.mc.gameSettings.gui_mode == 0 && !(this.mc.currentScreen instanceof GuiScreenBook)) {
            this.mc.mcProfiler.startSection("expBar");
            this.mc.getTextureManager().bindTexture(Gui.icons);
            short var37 = 182;
            int var14 = (int)(this.mc.thePlayer.getLevelProgress() * (var37 + 1));
            int var15 = var7 - 32 + 3;
            this.drawTexturedModalRect(var11, var15, 0, 64, var37, 5);
            if (var14 > 0) {
               this.drawTexturedModalRect(var11, var15, 0, 69, var14, 5);
            }

            this.mc.mcProfiler.endSection();
            if (this.mc.thePlayer.getExperienceLevel() != 0 && !(this.mc.currentScreen instanceof GuiScreenBook)) {
               this.mc.mcProfiler.startSection("expLevel");
               boolean var35 = false;
               var14 = var35 ? 16777215 : 8453920;
               if (this.mc.thePlayer.getExperienceLevel() < 0) {
                  var14 = 16716563;
               }

               String var42 = "" + this.mc.thePlayer.getExperienceLevel();
               int var16 = (var6 - var8.getStringWidth(var42)) / 2;
               int var17 = var7 - 31 - 4;
               boolean var18 = false;
               var8.drawString(var42, var16 + 1, var17, 0);
               var8.drawString(var42, var16 - 1, var17, 0);
               var8.drawString(var42, var16, var17 + 1, 0);
               var8.drawString(var42, var16, var17 - 1, 0);
               var8.drawString(var42, var16, var17, var14);
               this.mc.mcProfiler.endSection();
            }
         }

         if (this.curse_notification_counter > 0 && this.mc.thePlayer.is_cursed) {
            FontRenderer fr = this.mc.fontRenderer;
            ScaledResolution sr = var5;
            String text = Translator.get(this.mc.thePlayer.is_cursed ? "curse.notify" : "curse.lifted");
            int var14 = var7 - 59;
            if (!this.mc.playerController.shouldDrawHUD()) {
               var14 += 14;
            }

            int alpha = (int)(this.curse_notification_counter * 256.0F / 10.0F);
            if (alpha > 255) {
               alpha = 255;
            }

            if (alpha > 0) {
               Draw.blend(true);
               Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
               fr.drawStringWithShadow(
                  EnumChatFormatting.DARK_PURPLE + text, (sr.getScaledWidth() - fr.getStringWidth(text)) / 2, var14, 16777215 + (alpha << 24)
               );
               Draw.blend(false);
            }

            this.remainingHighlightTicks = 0;
         }

         if (this.mc.gameSettings.heldItemTooltips && this.mc.gameSettings.gui_mode == 0) {
            this.mc.mcProfiler.startSection("toolHighlight");
            if (this.remainingHighlightTicks > 0 && this.highlightingItemStack != null) {
               String var36 = this.highlightingItemStack.getMITEStyleDisplayName();
               int var13 = (var6 - var8.getStringWidth(var36)) / 2;
               int var14 = var7 - 59;
               if (!this.mc.playerController.shouldDrawHUD()) {
                  var14 += 14;
               }

               int var15 = (int)(this.remainingHighlightTicks * 256.0F / 10.0F);
               if (var15 > 255) {
                  var15 = 255;
               }

               if (var15 > 0) {
                  Draw.pushPose();
                  Draw.blend(true);
                  Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
                  var8.drawStringWithShadow(var36, var13, var14, 16777215 + (var15 << 24));
                  Draw.blend(false);
                  Draw.popPose();
               }

               this.last_highlighting_item_stack_text = var36;
            }

            if (this.highlightingItemStack == null) {
               this.last_highlighting_item_stack_text = null;
            }

            this.mc.mcProfiler.endSection();
         }

         if (this.mc.isDemo()) {
            this.mc.mcProfiler.startSection("demo");
            String var36 = "";
            if (this.mc.theWorld.getTotalWorldTime() >= 120500L) {
               var36 = I18n.getString("demo.demoExpired");
            } else {
               var36 = I18n.getStringParams("demo.remainingTime", StringUtils.ticksToElapsedTime((int)(120500L - this.mc.theWorld.getTotalWorldTime())));
            }

            int var13 = var8.getStringWidth(var36);
            var8.drawStringWithShadow(var36, var6 - var13 - 10, 5, 16777215);
            this.mc.mcProfiler.endSection();
         }

         if (DedicatedServer.tournament_type == EnumTournamentType.score) {
            this.mc.thePlayer.delta_tournament_score_opacity -= 2;
            if (this.mc.thePlayer.delta_tournament_score_opacity < 0) {
               this.mc.thePlayer.delta_tournament_score = 0;
               this.mc.thePlayer.delta_tournament_score_opacity = 0;
            }

            this.mc.last_known_delta_tournament_score = this.mc.thePlayer.delta_tournament_score;
            this.mc.last_known_delta_tournament_score_opacity = this.mc.thePlayer.delta_tournament_score_opacity;
            this.mc.last_known_tournament_score = this.mc.thePlayer.tournament_score;
         }

         int row = 0;
         if (Minecraft.getErrorMessage() != null) {
            this.drawString(var8, Minecraft.getErrorMessage(), 2, 2 + 10 * row++, 16716563);
            this.drawString(var8, "Press [c] to clear error message.", 2, 2 + 10 * row++, 16716563);
         }

         if (this.mc.gameSettings.showDebugInfo && this.mc.gameSettings.gui_mode == 0) {
            if (DedicatedServer.tournament_type == EnumTournamentType.score) {
               this.drawTournamentScore(row++, 2, var8);
            }

            if (allotted_time >= 0) {
               this.drawAllottedTime(row++, 2, var8);
            }
         }

         if (Minecraft.inDevMode() && this.mc.gameSettings.gui_mode == 0) {
            if (server_load >= 0) {
               ScaledResolution sr = var5;
               String text = server_load + "%";
               this.drawString(var8, text, sr.getScaledWidth() - var8.getStringWidth(text) - 2, 2, 14737632);
            }

            this.drawString(
               var8,
               "Legs ("
                  + MathHelper.floor_double(this.mc.thePlayer.posX)
                  + ", "
                  + MathHelper.floor_double(this.mc.thePlayer.posY - this.mc.thePlayer.yOffset)
                  + ", "
                  + MathHelper.floor_double(this.mc.thePlayer.posZ)
                  + ")  yaw="
                  + StringHelper.formatFloat(this.mc.thePlayer.rotationYaw, 1, 1)
                  + "  "
                  + this.mc.thePlayer.getDirectionFromYaw()
                  + " pitch="
                  + StringHelper.formatFloat(this.mc.thePlayer.rotationPitch, 1, 1)
                  + "   FPS="
                  + Minecraft.last_fps
                  + " ("
                  + Minecraft.last_fp10s
                  + ")",
               2,
               2 + 10 * row++,
               14737632
            );
            if (Debug.flag) {
               this.drawString(var8, "FLAG", 320, 2, 16716563);
            }
         }

         if (Debug.is_active && this.mc.gameSettings.gui_mode == 0) {
            this.drawString(var8, "Counter: " + Debug.general_counter, 2, 2 + 10 * row++, 14737632);
            if (Debug.biome_info != null) {
               this.drawString(var8, Debug.biome_info, 2, 2 + 10 * row++, 14737632);
            }

            if (Debug.selected_object_info != null) {
               this.drawString(var8, Debug.selected_object_info, 2, 2 + 10 * row++, 14737632);
            }

            if (Debug.equipped_item_info != null) {
               this.drawString(var8, Debug.equipped_item_info, 2, 2 + 10 * row++, 14737632);
            }

            if (Debug.general_info != null) {
               this.drawString(var8, Debug.general_info, 2, 2 + 10 * row++, 14737632);
            }

            if (Debug.general_info_client != null) {
               this.drawString(var8, "[Client] " + Debug.general_info_client, 2, 2 + 10 * row++, 14737632);
            }

            if (Debug.general_info_server != null) {
               this.drawString(var8, "[Server] " + Debug.general_info_server, 2, 2 + 10 * row++, 14737632);
            }

            row += 2;
            this.drawString(var8, "Player entityId: " + this.mc.thePlayer.entityId + ", username: " + this.mc.thePlayer.getEntityName(), 2, 2 + 10 * row++, 14737632);
            this.drawString(
               var8,
               "O:"
                  + this.mc.theWorld.getWorldInfo().getWorldTotalTime(0)
                  + " U:"
                  + this.mc.theWorld.getWorldInfo().getWorldTotalTime(-2)
                  + " N:"
                  + this.mc.theWorld.getWorldInfo().getWorldTotalTime(-1)
                  + " E:"
                  + this.mc.theWorld.getWorldInfo().getWorldTotalTime(1),
               2,
               2 + 10 * row++,
               14737632
            );
            WeatherEvent event = this.mc.theWorld.getCurrentWeatherEvent();
            String s;
            if (event != null) {
               s = "Current rain: " + event.start + " to " + event.end;
            } else {
               event = this.mc.theWorld.getNextWeatherEvent(false);
               if (event != null) {
                  s = "Next rain: " + event.start + " to " + event.end;
               } else {
                  event = this.mc.theWorld.getPreviousWeatherEvent(false);
                  s = event == null ? "No rain today" : "Previous rain: " + event.start + " to " + event.end;
               }
            }

            this.drawString(var8, s, 2, 2 + 10 * row++, 14737632);
            event = this.mc.theWorld.getCurrentWeatherEvent(true, false);
            if (event != null) {
               s = "Current storm: " + event.start_of_storm + " to " + event.end_of_storm;
            } else {
               event = this.mc.theWorld.getNextWeatherEvent(true);
               if (event != null) {
                  s = "Next storm: " + event.start_of_storm + " to " + event.end_of_storm;
               } else {
                  event = this.mc.theWorld.getPreviousWeatherEvent(true);
                  s = event == null ? "No storm today" : "Previous storm: " + event.start_of_storm + " to " + event.end_of_storm;
               }
            }

            this.drawString(var8, s, 2, 2 + 10 * row++, 14737632);
            this.drawString(
               var8,
               "Client Pools: " + AxisAlignedBB.getAABBPool().getlistAABBsize() + " | " + this.mc.theWorld.getWorldVec3Pool().getPoolSize(),
               2,
               2 + 10 * row++,
               14737632
            );
            this.drawString(var8, "Server Pools: " + Minecraft.server_pools_string, 2, 2 + 10 * row++, 14737632);
            row++;
            this.drawString(
               var8,
               "Atk: "
                  + StringHelper.formatFloat(this.mc.thePlayer.calcRawMeleeDamageVs(null), 1, 1)
                  + "  Prt:"
                  + StringHelper.formatFloat(this.mc.thePlayer.getTotalProtection(null), 1, 1),
               2,
               2 + 10 * row++,
               14737632
            );
            this.drawString(
               var8,
               "Look: "
                  + MathHelper.getNormalizedVector(this.mc.thePlayer.rotationYaw, this.mc.thePlayer.rotationPitch, this.mc.theWorld.getWorldVec3Pool())
                     .toStringCompact(),
               2,
               2 + 10 * row++,
               14737632
            );
            this.drawString(var8, "fxLayers" + this.mc.effectRenderer.getStatsString(), 2, 2 + 10 * row++, 14737632);
            Chunk chunk = this.mc.thePlayer.getChunkFromPosition();
            this.drawString(
               var8,
               "Chunk: "
                  + chunk.xPosition
                  + ","
                  + chunk.zPosition
                  + " ["
                  + (this.mc.thePlayer.getFootBlockPosY() >> 4)
                  + "] FPP="
                  + StringHelper.formatDouble(EntityRenderer.getProximityToNearestFogPost(this.mc.thePlayer), 3, 3),
               2,
               2 + 10 * row++,
               14737632
            );
            MinecraftServer mc_server = MinecraftServer.getServer();
            if (mc_server != null) {
               WorldServer world_server = mc_server.worldServerForDimension(this.mc.thePlayer.dimension);
               this.drawString(
                  var8,
                  "Mobs high: " + world_server.countMobs(false, true) + " / " + world_server.last_mob_spawn_limit_at_60_or_higher,
                  2,
                  2 + 10 * row++,
                  14737632
               );
               this.drawString(
                  var8, "Mobs low:  " + world_server.countMobs(true, false) + " / " + world_server.last_mob_spawn_limit_under_60, 2, 2 + 10 * row++, 14737632
               );
            }

            this.drawString(
               var8,
               "Mem: "
                  + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024L / 1024L
                  + " / "
                  + Runtime.getRuntime().totalMemory() / 1024L / 1024L,
               2,
               2 + 10 * row++,
               14737632
            );
         } else if (this.mc.gameSettings.showDebugInfo && this.mc.gameSettings.gui_mode == 0) {
            this.mc.mcProfiler.startSection("debug");
            this.mc.mcProfiler.endSection();
            if (DedicatedServer.tournament_type != EnumTournamentType.score && allotted_time < 0) {
               this.drawString(var8, "" + Minecraft.last_fps, 2, 2 + 10 * row++, 14737632);
            }
         }

         if (this.recordPlayingUpFor > 0) {
            this.mc.mcProfiler.startSection("overlayMessage");
            float var33 = this.recordPlayingUpFor - par1;
            int var13 = (int)(var33 * 255.0F / 20.0F);
            if (var13 > 255) {
               var13 = 255;
            }

            if (var13 > 8) {
               Draw.pushPose();
               Draw.translate(var6 / 2, var7 - 68, 0.0F);
               Draw.blend(true);
               Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
               int var14 = 16777215;
               if (this.recordIsPlaying) {
                  var14 = Color.HSBtoRGB(var33 / 50.0F, 0.7F, 0.6F) & 16777215;
               }

               var8.drawString(this.recordPlaying, -var8.getStringWidth(this.recordPlaying) / 2, -4, var14 + (var13 << 24 & 0xFF000000));
               Draw.blend(false);
               Draw.popPose();
            }

            this.mc.mcProfiler.endSection();
         }

         ScoreObjective var43 = this.mc.theWorld.getScoreboard().func_96539_a(1);
         if (var43 != null) {
            this.func_96136_a(var43, var7, var6, var8);
         }

         Draw.blend(true);
         Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
         Draw.alphaTest(false);
         Draw.pushPose();
         Draw.translate(0.0F, var7 - 48, 0.0F);
         this.mc.mcProfiler.startSection("chat");
         this.persistantChatGUI.drawChat(this.updateCounter);
         this.mc.mcProfiler.endSection();
         Draw.popPose();
         var43 = this.mc.theWorld.getScoreboard().func_96539_a(0);
         if (this.mc.gameSettings.keyBindPlayerList.pressed
            && (!this.mc.isIntegratedServerRunning() || this.mc.thePlayer.sendQueue.playerInfoList.size() > 1 || var43 != null)) {
            this.mc.mcProfiler.startSection("playerList");
            NetClientHandler var41 = this.mc.thePlayer.sendQueue;
            List var44 = var41.playerInfoList;
            int var15 = var41.currentServerMaxPlayers;
            int var16 = var15;

            int var17;
            for (var17 = 1; var16 > 20; var16 = (var15 + var17 - 1) / var17) {
               var17++;
            }

            int var45 = 300 / var17;
            if (var45 > 150) {
               var45 = 150;
            }

            int var19 = (var6 - var17 * var45) / 2;
            byte var47 = 10;
            drawRect(var19 - 1, var47 - 1, var19 + var45 * var17, var47 + 9 * var16, Integer.MIN_VALUE);

            // Pass 1: all cell background rects (no text overlaps them), so they merge in the GUI batch.
            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            Draw.alphaTest(true);
            for (int var21 = 0; var21 < var15; var21++) {
               int var22 = var19 + var21 % var17 * var45;
               int var23 = var47 + var21 / var17 * 9;
               drawRect(var22, var23, var22 + var45 - 1, var23 + 8, 553648127);
            }

            // Pass 2: names, scores, ping icons, in MITE's per-row order.
            int players_skipped = 0;

            for (int var21 = 0; var21 < var15; var21++) {
               int var22 = var19 + var21 % var17 * var45;
               int var23 = var47 + var21 / var17 * 9;
               int var23_alt = var47 + (var21 - players_skipped) / var17 * 9;
               Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
               Draw.alphaTest(true);
               if (var21 < var44.size()) {
                  GuiPlayerInfo var49 = (GuiPlayerInfo)var44.get(var21);
                  ScorePlayerTeam var48 = this.mc.theWorld.getScoreboard().getPlayersTeam(var49.name);
                  String var52 = ScorePlayerTeam.formatPlayerName(var48, var49.name);
                  if ("avernite".equals(var52) && DedicatedServer.isTournament()) {
                     players_skipped++;
                  } else {
                     var8.drawStringWithShadow(var52, var22, var23_alt, 16777215);
                     if (var43 != null) {
                        int var27 = var22 + var8.getStringWidth(var52) + 5;
                        int var28 = var22 + var45 - 12 - 5;
                        if (var28 - var27 > 5) {
                           Score var29 = var43.getScoreboard().func_96529_a(var49.name, var43);
                           String var30 = EnumChatFormatting.YELLOW + "" + var29.getScorePoints();
                           var8.drawStringWithShadow(var30, var28 - var8.getStringWidth(var30), var23_alt, 16777215);
                        }
                     } else {
                        int var27 = var22 + var8.getStringWidth(var52) + 5;
                        int var28 = var22 + var45 - 12 - 5;
                        if (var28 - var27 > 5) {
                           String level;
                           if (var49.level < 0) {
                              level = EnumChatFormatting.RED + "" + var49.level;
                           } else if (var49.level == 0) {
                              level = EnumChatFormatting.GRAY + "" + var49.level;
                           } else {
                              level = EnumChatFormatting.GREEN + "+" + var49.level;
                           }

                           var8.drawStringWithShadow(level, var28 - var8.getStringWidth(level), var23_alt, 16777215);
                        }
                     }

                     Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
                     this.mc.getTextureManager().bindTexture(icons);
                     byte var53 = 0;
                     boolean var51 = false;
                     byte var50;
                     if (var49.responseTime < 0) {
                        var50 = 5;
                     } else if (var49.responseTime < 150) {
                        var50 = 0;
                     } else if (var49.responseTime < 300) {
                        var50 = 1;
                     } else if (var49.responseTime < 600) {
                        var50 = 2;
                     } else if (var49.responseTime < 1000) {
                        var50 = 3;
                     } else {
                        var50 = 4;
                     }

                     this.zLevel += 100.0F;
                     this.drawTexturedModalRect(var22 + var45 - 12, var23_alt, 0 + var53 * 10, 176 + var50 * 8, 10, 8);
                     this.zLevel -= 100.0F;
                  }
               }
            }
         }

         Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
         Draw.lighting(false);
         Draw.alphaTest(true);
      }
   }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
   private void func_96136_a(ScoreObjective par1ScoreObjective, int par2, int par3, FontRenderer par4FontRenderer) {
      Scoreboard var5 = par1ScoreObjective.getScoreboard();
      Collection var6 = var5.func_96534_i(par1ScoreObjective);
      if (var6.size() <= 15) {
         int var7 = par4FontRenderer.getStringWidth(par1ScoreObjective.getDisplayName());

         for (Object var9o : var6) {
            Score var9 = (Score) var9o;
            ScorePlayerTeam var10 = var5.getPlayersTeam(var9.getPlayerName());
            String var11 = ScorePlayerTeam.formatPlayerName(var10, var9.getPlayerName()) + ": " + EnumChatFormatting.RED + var9.getScorePoints();
            var7 = Math.max(var7, par4FontRenderer.getStringWidth(var11));
         }

         int var22 = var6.size() * par4FontRenderer.FONT_HEIGHT;
         int var23 = par2 / 2 + var22 / 3;
         byte var25 = 3;
         int var24 = par3 - var7 - var25;
         int var12 = 0;

         for (Object var14o : var6) {
            Score var14 = (Score) var14o;
            var12++;
            ScorePlayerTeam var15 = var5.getPlayersTeam(var14.getPlayerName());
            String var16 = ScorePlayerTeam.formatPlayerName(var15, var14.getPlayerName());
            String var17 = EnumChatFormatting.RED + "" + var14.getScorePoints();
            int var19 = var23 - var12 * par4FontRenderer.FONT_HEIGHT;
            int var20 = par3 - var25 + 2;
            drawRect(var24 - 2, var19, var20, var19 + par4FontRenderer.FONT_HEIGHT, 1342177280);
            par4FontRenderer.drawString(var16, var24, var19, 553648127);
            par4FontRenderer.drawString(var17, var20 - par4FontRenderer.getStringWidth(var17), var19, 553648127);
            if (var12 == var6.size()) {
               String var21 = par1ScoreObjective.getDisplayName();
               drawRect(var24 - 2, var19 - par4FontRenderer.FONT_HEIGHT - 1, var20, var19 - 1, 1610612736);
               drawRect(var24 - 2, var19 - 1, var20, var19, 1342177280);
               par4FontRenderer.drawString(
                  var21, var24 + var7 / 2 - par4FontRenderer.getStringWidth(var21) / 2, var19 - par4FontRenderer.FONT_HEIGHT, 553648127
               );
            }
         }
      }
   }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
   private void func_110327_a(int par1, int par2) {
      boolean var3 = this.mc.thePlayer.hurtResistantTime / 3 % 2 == 1;
      if (this.mc.thePlayer.hurtResistantTime < 10) {
         var3 = false;
      }

      int var4 = MathHelper.ceiling_float_int(this.mc.thePlayer.getHealth());
      int var5 = MathHelper.ceiling_float_int(this.mc.thePlayer.prevHealth);
      this.rand.setSeed(this.updateCounter * 312871);
      FoodStats var7 = this.mc.thePlayer.getFoodStats();
      int var8 = var7.getNutrition();
      AttributeInstance var10 = this.mc.thePlayer.getEntityAttribute(SharedMonsterAttributes.maxHealth);
      int var11 = par1 / 2 - 91;
      int var12 = par1 / 2 + 91;
      int var13 = par2 - 39;
      float var14 = (float)var10.getAttributeValue();
      float var15 = this.mc.thePlayer.getAbsorptionAmount();
      int var16 = MathHelper.ceiling_float_int((var14 + var15) / 2.0F / 10.0F);
      int var17 = Math.max(10 - (var16 - 2), 3);
      int var18 = var13 - (var16 - 1) * var17 - 10;
      float var19 = var15;
      float total_protection = this.mc.thePlayer.getTotalProtection(null);
      int var20 = MathHelper.ceiling_float_int(total_protection);
      int var21 = -1;
      if (this.mc.thePlayer.isPotionActive(Potion.regeneration)) {
         var21 = this.updateCounter % MathHelper.ceiling_float_int(var14 + 5.0F);
      }

      this.mc.mcProfiler.startSection("armor");

      for (int var22 = 0; var22 < 10; var22++) {
         if (total_protection > 0.0F || this.mc.thePlayer.isWearingArmor()) {
            int var23 = var11 + var22 * 8;
            if (var22 * 2 + 1 < var20) {
               this.drawTexturedModalRect(var23, var18, 34, 9, 9, 9);
            }

            if (var22 * 2 + 1 == var20) {
               this.drawTexturedModalRect(var23, var18, 25, 9, 9, 9);
            }

            if (var22 * 2 + 1 > var20) {
               this.drawTexturedModalRect(var23, var18, 16, 9, 9, 9);
            }
         }
      }

      this.mc.mcProfiler.endStartSection("health");

      for (int var391 = MathHelper.ceiling_float_int((var14 + var15) / 2.0F) - 1; var391 >= 0; var391--) {
         int var23 = 16;
         if (this.mc.thePlayer.isPotionActive(Potion.poison)) {
            var23 += 36;
         } else if (this.mc.thePlayer.isPotionActive(Potion.wither)) {
            var23 += 72;
         }

         byte var24 = 0;
         if (var3) {
            var24 = 1;
         }

         int var25 = MathHelper.ceiling_float_int((var391 + 1) / 10.0F) - 1;
         int var26 = var11 + var391 % 10 * 8;
         int var27 = var13 - var25 * var17;
         if (var4 <= 4) {
            var27 += this.rand.nextInt(2);
         }

         if (var391 == var21) {
            var27 -= 2;
         }

         byte var28 = 0;
         if (this.mc.theWorld.getWorldInfo().isHardcoreModeEnabled()) {
            var28 = 5;
         }

         if (var391 < this.mc.thePlayer.getMaxHealth() / 2.0F) {
            this.drawTexturedModalRect(var26, var27, 16 + var24 * 9, 9 * var28, 9, 9);
         }

         if (var3) {
            if (var391 * 2 + 1 < var5) {
               this.drawTexturedModalRect(var26, var27, var23 + 54, 9 * var28, 9, 9);
            }

            if (var391 * 2 + 1 == var5) {
               this.drawTexturedModalRect(var26, var27, var23 + 63, 9 * var28, 9, 9);
            }
         }

         if (var19 > 0.0F) {
            if (var19 == var15 && var15 % 2.0F == 1.0F) {
               this.drawTexturedModalRect(var26, var27, var23 + 153, 9 * var28, 9, 9);
            } else {
               this.drawTexturedModalRect(var26, var27, var23 + 144, 9 * var28, 9, 9);
            }

            var19 -= 2.0F;
         } else {
            if (var391 * 2 + 1 < var4) {
               this.drawTexturedModalRect(var26, var27, var23 + 36, 9 * var28, 9, 9);
            }

            if (var391 * 2 + 1 == var4) {
               this.drawTexturedModalRect(var26, var27, var23 + 45, 9 * var28, 9, 9);
            }
         }
      }

      Entity var34 = this.mc.thePlayer.ridingEntity;
      if (var34 != null && !(var34 instanceof EntityBoat)) {
         if (var34 instanceof EntityLivingBase) {
            this.mc.mcProfiler.endStartSection("mountHealth");
            EntityLivingBase var38 = (EntityLivingBase)var34;
            int var35 = (int)Math.ceil(var38.getHealth());
            float var37 = var38.getMaxHealth();
            int var26 = (int)(var37 + 0.5F) / 2;
            if (var26 > 30) {
               var26 = 30;
            }

            int var27 = var13;

            for (int var39 = 0; var26 > 0; var39 += 20) {
               int var29 = Math.min(var26, 10);
               var26 -= var29;

               for (int var30 = 0; var30 < var29; var30++) {
                  byte var31 = 52;
                  byte var32 = 0;
                  int var33 = var12 - var30 * 8 - 9;
                  this.drawTexturedModalRect(var33, var27, var31 + var32 * 9, 9, 9, 9);
                  if (var30 * 2 + 1 + var39 < var35) {
                     this.drawTexturedModalRect(var33, var27, var31 + 36, 9, 9, 9);
                  }

                  if (var30 * 2 + 1 + var39 == var35) {
                     this.drawTexturedModalRect(var33, var27, var31 + 45, 9, 9, 9);
                  }
               }

               var27 -= 10;
            }
         }
      } else {
         this.mc.mcProfiler.endStartSection("food");

         for (int var23 = 0; var23 < 10; var23++) {
            int var35 = var13;
            int var25 = 16;
            byte var36 = 0;
            if (this.mc.thePlayer.isPotionActive(Potion.hunger)) {
               var25 += 36;
               var36 = 13;
            }

            if (this.mc.thePlayer.isHungry() && this.updateCounter % (var8 * 3 + 1) == 0) {
               var35 = var13 + (this.rand.nextInt(3) - 1);
            }

            int var27 = var12 - var23 * 8 - 9;
            if (var23 < this.mc.thePlayer.getFoodStats().getNutritionLimit() / 2) {
               this.drawTexturedModalRect(var27, var35, 16 + var36 * 9, 27, 9, 9);
            }

            if (var23 * 2 + 1 < var8) {
               this.drawTexturedModalRect(var27, var35, var25 + 36, 27, 9, 9);
            }

            if (var23 * 2 + 1 == var8) {
               this.drawTexturedModalRect(var27, var35, var25 + 45, 27, 9, 9);
            }
         }
      }

      this.mc.mcProfiler.endStartSection("air");
      if (this.mc.thePlayer.isInsideOfMaterial(Material.water)) {
         int var23 = this.mc.thePlayer.getAir();
         int var35 = MathHelper.ceiling_double_int((var23 - 2) * 10.0 / 300.0);
         int var25 = MathHelper.ceiling_double_int(var23 * 10.0 / 300.0) - var35;

         for (int var26 = 0; var26 < var35 + var25; var26++) {
            if (var26 < var35) {
               this.drawTexturedModalRect(var12 - var26 * 8 - 9, var18, 16, 18, 9, 9);
            } else {
               this.drawTexturedModalRect(var12 - var26 * 8 - 9, var18, 25, 18, 9, 9);
            }
         }
      }

      this.mc.mcProfiler.endSection();
   }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
   private void drawTournamentScore(int row, int col, FontRenderer var8) {
      String tournament_score = "" + this.mc.thePlayer.tournament_score;
      this.drawString(var8, tournament_score, col, 2 + 10 * row++, this.mc.thePlayer.tournament_score < 0 ? 16716563 : 8453920);
      if (this.mc.thePlayer.delta_tournament_score != 0 && this.mc.thePlayer.delta_tournament_score_opacity > 4) {
         int effective_opacity = MathHelper.clamp_int(this.mc.thePlayer.delta_tournament_score_opacity, 0, 255) * 256 * 256 * 256;
         Draw.blend(true);
         Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
         String delta_tournament_score = (this.mc.thePlayer.delta_tournament_score < 1 ? "" : "+") + this.mc.thePlayer.delta_tournament_score;
         row--;
         col += var8.getStringWidth(tournament_score);
         this.drawString(var8, " (", col, 2 + 10 * row, 12632256 + effective_opacity);
         col += var8.getStringWidth(" (");
         this.drawString(
            var8,
            delta_tournament_score,
            col,
            2 + 10 * row,
            this.mc.thePlayer.delta_tournament_score < 0 ? 16716563 + effective_opacity : 8453920 + effective_opacity
         );
         col += var8.getStringWidth(delta_tournament_score);
         this.drawString(var8, ")", col, 2 + 10 * row, 12632256 + effective_opacity);
         row++;
         Draw.blend(false);
      }
   }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
   private void drawAllottedTime(int row, int col, FontRenderer var8) {
      int seconds = allotted_time / 20;
      int hours = seconds / 3600;
      seconds -= hours * 3600;
      int minutes = seconds / 60;
      seconds -= minutes * 60;
      this.drawString(
         var8,
         hours + ":" + (minutes < 10 ? "0" + minutes : minutes) + ":" + (seconds < 10 ? "0" + seconds : seconds),
         col,
         2 + 10 * row++,
         this.mc.thePlayer.tournament_score < 0 ? 16716563 : 8453920
      );
   }
}

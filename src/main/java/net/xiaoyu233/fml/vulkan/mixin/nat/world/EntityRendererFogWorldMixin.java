package net.xiaoyu233.fml.vulkan.mixin.nat.world;

import java.util.Random;
import net.minecraft.ActiveRenderInfo;
import net.minecraft.Block;
import net.minecraft.EnchantmentHelper;
import net.minecraft.EntityLivingBase;
import net.minecraft.EntityRenderer;
import net.minecraft.EnumSkyBlock;
import net.minecraft.Material;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.Potion;
import net.minecraft.Vec3;
import net.minecraft.World;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.render.state.ColorMaterial;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.render.state.FogMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Native area "world" (NR-12), fog part: updateFogColor, setupFog (see {@link EntityRendererWorldMixin}).
 * The readers of {@code capability_gl_nv_fog_distance} live in the overwritten setupFog, so the field is dead here; its
 * <clinit> GLContext call is not handled by this mixin (open item, see {@link EntityRendererWorldMixin}).
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererFogWorldMixin {
    @Shadow private Minecraft mc;
    @Shadow private float farPlaneDistance;
    @Shadow private boolean cloudFog;
    @Shadow float fogColorRed;
    @Shadow float fogColorGreen;
    @Shadow float fogColorBlue;
    @Shadow private float field_82831_U;
    @Shadow private float field_82832_V;
    @Shadow private float skylight_brightness_used_for_fog;
    @Shadow public static boolean disable_fog;
    @Shadow private static Random random_for_fog_events;
    @Shadow private static double distance_from_biome_that_can_be_foggy;
    @Shadow private static long distance_from_biome_that_can_be_foggy_tick;
    @Shadow private static double distance_from_biome_that_can_be_foggy_last_viewer_pos_x;
    @Shadow private static double distance_from_biome_that_can_be_foggy_last_viewer_pos_z;
    @Shadow private static World distance_from_biome_that_can_be_foggy_last_viewer_world;

    @Shadow private double getInterpolatedPosYForVoidFog(EntityLivingBase viewer, float partial_tick) { throw new AssertionError(); }
    @Shadow public double getDistanceToNearestBiomeThatCanBeFoggy(double pos_x, double pos_z) { throw new AssertionError(); }

    /** @author FML-Vulkan @reason native fog colour (Frame.clearColor) */
    @Overwrite
    private void updateFogColor(float par1) {
       WorldClient var2 = this.mc.theWorld;
       EntityLivingBase var3 = this.mc.renderViewEntity;
       float var4 = 1.0F / (4 - this.mc.gameSettings.getRenderDistance());
       var4 = 1.0F - (float)Math.pow(var4, 0.25);
       Vec3 var5 = var2.getSkyColor(this.mc.renderViewEntity, par1);
       float var6 = (float)var5.xCoord;
       float var7 = (float)var5.yCoord;
       float var8 = (float)var5.zCoord;
       Vec3 var9 = var2.getFogColor(par1, var3);
       this.fogColorRed = (float)var9.xCoord;
       this.fogColorGreen = (float)var9.yCoord;
       this.fogColorBlue = (float)var9.zCoord;
       if (this.mc.gameSettings.getRenderDistance() < 2) {
          Vec3 var10 = MathHelper.sin(var2.getCelestialAngleRadians(par1)) > 0.0F
             ? var2.getWorldVec3Pool().getVecFromPool(-1.0, 0.0, 0.0)
             : var2.getWorldVec3Pool().getVecFromPool(1.0, 0.0, 0.0);
          float var11 = (float)var3.getLook(par1).dotProduct(var10);
          if (var11 < 0.0F) {
             var11 = 0.0F;
          }

          if (var11 > 0.0F) {
             float[] var12 = var2.provider.calcSunriseSunsetColors(var2.getCelestialAngle(par1), par1);
             if (var12 != null) {
                var11 *= var12[3];
                this.fogColorRed = this.fogColorRed * (1.0F - var11) + var12[0] * var11;
                this.fogColorGreen = this.fogColorGreen * (1.0F - var11) + var12[1] * var11;
                this.fogColorBlue = this.fogColorBlue * (1.0F - var11) + var12[2] * var11;
             }
          }
       }

       this.fogColorRed = this.fogColorRed + (var6 - this.fogColorRed) * var4;
       this.fogColorGreen = this.fogColorGreen + (var7 - this.fogColorGreen) * var4;
       this.fogColorBlue = this.fogColorBlue + (var8 - this.fogColorBlue) * var4;
       float var19 = var2.getRainStrength(par1);
       if (var19 > 0.0F) {
          float var11 = 1.0F - var19 * 0.5F;
          float var20 = 1.0F - var19 * 0.4F;
          this.fogColorRed *= var11;
          this.fogColorGreen *= var11;
          this.fogColorBlue *= var20;
       }

       float var11 = var2.getWeightedThunderStrength(par1);
       if (var11 > 0.0F) {
          float var20 = 1.0F - var11 * 0.5F;
          this.fogColorRed *= var20;
          this.fogColorGreen *= var20;
          this.fogColorBlue *= var20;
       }

       int var21 = ActiveRenderInfo.getBlockIdAtEntityViewpoint(this.mc.theWorld, var3, par1);
       if (this.cloudFog) {
          Vec3 var13 = var2.getCloudColour(par1);
          this.fogColorRed = (float)var13.xCoord;
          this.fogColorGreen = (float)var13.yCoord;
          this.fogColorBlue = (float)var13.zCoord;
       } else if (var21 != 0 && Block.blocksList[var21].blockMaterial == Material.water) {
          float var22 = EnchantmentHelper.getRespiration(var3) * 0.2F;
          this.fogColorRed = 0.02F + var22;
          this.fogColorGreen = 0.02F + var22;
          this.fogColorBlue = 0.2F + var22;
       } else if (var21 != 0 && Block.blocksList[var21].blockMaterial == Material.lava) {
          this.fogColorRed = 0.6F;
          this.fogColorGreen = 0.1F;
          this.fogColorBlue = 0.0F;
       }

       double var14 = this.mc.theWorld.hasSkylight() ? this.getInterpolatedPosYForVoidFog(var3, par1) * var2.provider.getVoidFogYFactor() : 1.0;
       if (var3.isPotionActive(Potion.blindness)) {
          int var16 = var3.getActivePotionEffect(Potion.blindness).getDuration();
          if (var16 < 20) {
             var14 *= 1.0F - var16 / 20.0F;
          } else {
             var14 = 0.0;
          }
       }

       if (var14 < 1.0) {
          if (var14 < 0.0) {
             var14 = 0.0;
          }

          var14 *= var14;
          this.fogColorRed = (float)(this.fogColorRed * var14);
          this.fogColorGreen = (float)(this.fogColorGreen * var14);
          this.fogColorBlue = (float)(this.fogColorBlue * var14);
       }

       if (this.field_82831_U > 0.0F) {
          float var23 = this.field_82832_V + (this.field_82831_U - this.field_82832_V) * par1;
          this.fogColorRed = this.fogColorRed * (1.0F - var23) + this.fogColorRed * 0.7F * var23;
          this.fogColorGreen = this.fogColorGreen * (1.0F - var23) + this.fogColorGreen * 0.6F * var23;
          this.fogColorBlue = this.fogColorBlue * (1.0F - var23) + this.fogColorBlue * 0.6F * var23;
       }

       if (this.mc.gameSettings.anaglyph) {
          float var23 = (this.fogColorRed * 30.0F + this.fogColorGreen * 59.0F + this.fogColorBlue * 11.0F) / 100.0F;
          float var17 = (this.fogColorRed * 30.0F + this.fogColorGreen * 70.0F) / 100.0F;
          float var18 = (this.fogColorRed * 30.0F + this.fogColorBlue * 70.0F) / 100.0F;
          this.fogColorRed = var23;
          this.fogColorGreen = var17;
          this.fogColorBlue = var18;
       }

       Frame.clearColor(this.fogColorRed, this.fogColorGreen, this.fogColorBlue, 0.0F);
    }

    /** @author FML-Vulkan @reason native fog state (Draw.fog*) */
    @Overwrite
    private void setupFog(int par1, float par2) {
       EntityLivingBase var3 = this.mc.renderViewEntity;
       boolean var4 = false;
       if (disable_fog) {
          var4 = true;
       }

       if (par1 == 999) {
          Draw.fogColor(0.0F, 0.0F, 0.0F, 1.0F);
          Draw.fogMode(FogMode.LINEAR);
          Draw.fogStart( 0.0F);
          Draw.fogEnd( 8.0F);

          Draw.fogStart( 0.0F);
       } else {
          Draw.fogColor(this.fogColorRed, this.fogColorGreen, this.fogColorBlue, 1.0F);
          Draw.normal(0.0F, -1.0F, 0.0F);
          Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
          int var5 = ActiveRenderInfo.getBlockIdAtEntityViewpoint(this.mc.theWorld, var3, par2);
          if (var3.isPotionActive(Potion.blindness)) {
             float var6 = 5.0F;
             int var7 = var3.getActivePotionEffect(Potion.blindness).getDuration();
             if (var7 < 20) {
                var6 = 5.0F + (this.farPlaneDistance - 5.0F) * (1.0F - var7 / 20.0F);
             }

             Draw.fogMode(FogMode.LINEAR);
             if (par1 < 0) {
                Draw.fogStart( 0.0F);
                Draw.fogEnd( var6 * 0.8F);
             } else {
                Draw.fogStart( var6 * 0.25F);
                Draw.fogEnd( var6);
             }

          } else if (this.cloudFog) {
             Draw.fogMode(FogMode.EXP);
             Draw.fogDensity( 0.1F);
          } else if (var5 > 0 && Block.blocksList[var5].blockMaterial == Material.water) {
             Draw.fogMode(FogMode.EXP);
             if (var3.isPotionActive(Potion.waterBreathing)) {
                Draw.fogDensity( 0.05F);
             } else {
                Draw.fogDensity( 0.1F - EnchantmentHelper.getRespiration(var3) * 0.03F);
             }
          } else if (var5 > 0 && Block.blocksList[var5].blockMaterial == Material.lava) {
             Draw.fogMode(FogMode.EXP);
             Draw.fogDensity( 2.0F);
          } else {
             float var6 = this.farPlaneDistance;
             if (!var4) {
                if (this.mc.theWorld.provider.getWorldHasVoidFog()) {
                   Vec3 eye_pos = var3.getEyePos();
                   int skylight_brightness_at_eye_pos = this.mc
                      .theWorld
                      .getSkyBlockTypeBrightness(EnumSkyBlock.Sky, eye_pos.getBlockX(), eye_pos.getBlockY(), eye_pos.getBlockZ());
                   int effective_fps = Minecraft.last_fps < 1 ? 1 : Minecraft.last_fps;
                   if (this.skylight_brightness_used_for_fog < skylight_brightness_at_eye_pos) {
                      float delta = skylight_brightness_at_eye_pos - this.skylight_brightness_used_for_fog;
                      float max_change = !(delta < 12.0F) && eye_pos.getBlockY() >= 0 ? delta : 0.6F / effective_fps;
                      this.skylight_brightness_used_for_fog = this.skylight_brightness_used_for_fog + Math.min(delta, max_change);
                   } else if (this.skylight_brightness_used_for_fog > skylight_brightness_at_eye_pos) {
                      float delta = this.skylight_brightness_used_for_fog - skylight_brightness_at_eye_pos;
                      float max_change = !(delta < 12.0F) && eye_pos.getBlockY() >= 0 ? delta : 0.6F / effective_fps;
                      this.skylight_brightness_used_for_fog = this.skylight_brightness_used_for_fog - Math.min(delta, max_change);
                   }

                   double interpolated_pos_y = MathHelper.getInterpolatedValue(var3.lastTickPosY, var3.posY, par2);
                   double var10 = this.skylight_brightness_used_for_fog / 16.0 + (interpolated_pos_y + 4.0) / 20.0;
                   if (var10 < 0.0) {
                      var10 = 0.0;
                   }

                   double power = 1.0 + (16.0 - interpolated_pos_y) / 2.0;
                   if (power > 1.0) {
                      var10 = Math.pow(var10, power);
                   }

                   float var9 = 100.0F * (float)var10;
                   if (var9 < 5.0F) {
                      var9 = 5.0F;
                   }

                   if (var6 > var9) {
                      var6 = var9;
                   }

                   if (var6 < this.farPlaneDistance) {
                      var6 = (float)(var6 / Math.max(Math.sqrt(EntityRenderer.getProximityToNearestFogPost(var3)), 0.01F));
                   }

                   if (var6 < 24.0F) {
                      var6 = 24.0F;
                   }

                   if (var6 > 96.0F && this.mc.theWorld.isOverworld()) {
                      long shifted_total_world_time = this.mc.theWorld.getTotalWorldTime() - 12000L;
                      int shifted_day_of_world = World.getDayOfWorld(shifted_total_world_time);
                      if (shifted_day_of_world > 7) {
                         random_for_fog_events.setSeed(shifted_day_of_world * 365024131 * this.mc.theWorld.getWorldCreationTime() * 672784657L);
                         random_for_fog_events.nextInt();
                         if (random_for_fog_events.nextInt(7) == 0) {
                            float fog_max_strength = 96.0F + random_for_fog_events.nextFloat() * (var6 - 96.0F) * 0.75F;
                            long ticks_from_midnight = this.mc.theWorld.getAdjustedTimeOfDay();
                            boolean is_dusk = false;
                            if (ticks_from_midnight > 12000L) {
                               ticks_from_midnight = 24000L - ticks_from_midnight;
                               is_dusk = true;
                            }

                            float day_cycle_factor = MathHelper.clamp_float((float)(8000L - ticks_from_midnight) / (is_dusk ? 4000.0F : 2000.0F), 0.0F, 1.0F);
                            if (day_cycle_factor > 0.0F) {
                               if (distance_from_biome_that_can_be_foggy_last_viewer_world != this.mc.theWorld
                                  || distance_from_biome_that_can_be_foggy_tick != this.mc.theWorld.getTotalWorldTime()) {
                                  boolean player_moved = distance_from_biome_that_can_be_foggy_last_viewer_world != this.mc.theWorld
                                     || distance_from_biome_that_can_be_foggy_last_viewer_pos_x != var3.posX
                                     || distance_from_biome_that_can_be_foggy_last_viewer_pos_z != var3.posZ;
                                  if (player_moved) {
                                     distance_from_biome_that_can_be_foggy = this.getDistanceToNearestBiomeThatCanBeFoggy(var3.posX, var3.posZ);
                                     distance_from_biome_that_can_be_foggy_last_viewer_pos_x = var3.posX;
                                     distance_from_biome_that_can_be_foggy_last_viewer_pos_z = var3.posZ;
                                  }

                                  distance_from_biome_that_can_be_foggy_tick = this.mc.theWorld.getTotalWorldTime();
                               }

                               float distance_from_biome_that_can_be_foggy_factor = Math.max(1.0F - (float)(distance_from_biome_that_can_be_foggy / 32.0), 0.0F);
                               float final_factor = day_cycle_factor * distance_from_biome_that_can_be_foggy_factor;
                               if (final_factor > 0.0F) {
                                  float fog_strength = fog_max_strength * final_factor + var6 * (1.0F - final_factor);
                                  if (var6 > fog_strength) {
                                     var6 = fog_strength;
                                  }
                               }
                            }
                         }
                      }
                   }

                   if (var6 > this.farPlaneDistance) {
                      var6 = this.farPlaneDistance;
                   }
                } else if (this.mc.theWorld.isUnderworld()) {
                   var6 = 128.0F;
                }
             }

             Draw.fogMode(FogMode.LINEAR);
             if (par1 < 0) {
                Draw.fogStart( 0.0F);
                Draw.fogEnd( var6 * 0.8F);
             } else {
                Draw.fogStart( var6 * 0.25F);
                Draw.fogEnd( var6);
             }


             if (this.mc.theWorld.provider.doesXZShowFog(var3.getBlockPosX(), var3.getEyeBlockPosY(), var3.getBlockPosZ())) {
                Draw.fogStart( var6 * 0.05F);
                Draw.fogEnd( Math.min(var6, 192.0F) * 0.5F);
             }
          }

          Draw.colorMaterialEnabled(true);
          Draw.colorMaterial(ColorMaterial.AMBIENT);
       }
    }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.world;

import java.util.List;
import net.minecraft.Entity;
import net.minecraft.EntityLiving;
import net.minecraft.EntityLivingBase;
import net.minecraft.EntityPlayer;
import net.minecraft.EntityWaterMob;
import net.minecraft.ICamera;
import net.minecraft.Minecraft;
import net.minecraft.Packet87SetDespawnCounters;
import net.minecraft.RaycastCollision;
import net.minecraft.RenderGlobal;
import net.minecraft.RenderManager;
import net.minecraft.TileEntity;
import net.minecraft.TileEntityRenderer;
import net.minecraft.Vec3;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.render.state.Lighting;
import net.xiaoyu233.fml.vulkan.render.state.Phase;
import net.xiaoyu233.fml.vulkan.render.world.EntityVisibility;
import net.xiaoyu233.fml.vulkan.render.world.ShadowCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "world": RenderGlobal.renderEntities with MITE's logic/order, native lighting and ENT-04/05 draw culling (NR-12). */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalWorldMixin {
    @Shadow public List tileEntities;
    @Shadow private WorldClient theWorld;
    @Shadow private Minecraft mc;
    @Shadow private int renderEntitiesStartupCounter;
    @Shadow private int countEntitiesTotal;
    @Shadow private int countEntitiesRendered;
    @Shadow private int countEntitiesHidden;
    @Shadow private long time_to_send_next_packet87;

    /**
     * @author FML-Vulkan NR-12
     * @reason native entity phase; MITE predicate chain preserved in EntityVisibility.mitePasses
     */
    @Overwrite
    public void renderEntities(Vec3 par1Vec3, ICamera par2ICamera, float par3) {
        if (this.renderEntitiesStartupCounter > 0) {
            this.renderEntitiesStartupCounter--;
        } else {
            Frame.beginPhase(Phase.ENTITIES);
            this.theWorld.theProfiler.startSection("prepare");
            TileEntityRenderer.instance.cacheActiveRenderInfo(this.theWorld, this.mc.getTextureManager(), this.mc.fontRenderer, this.mc.renderViewEntity, par3);
            RaycastCollision rc = ((EntityPlayer) this.mc.renderViewEntity).getSelectedObject(par3, false);
            EntityLiving pointed_entity_living = rc == null ? null : (rc.getEntityHit() instanceof EntityLiving ? (EntityLiving) rc.getEntityHit() : null);
            RenderManager.instance.cacheActiveRenderInfo(
                    this.theWorld, this.mc.getTextureManager(), this.mc.fontRenderer, this.mc.renderViewEntity, pointed_entity_living, this.mc.gameSettings, par3);
            this.countEntitiesTotal = 0;
            this.countEntitiesRendered = 0;
            this.countEntitiesHidden = 0;
            EntityLivingBase var4 = this.mc.renderViewEntity;
            RenderManager.renderPosX = var4.lastTickPosX + (var4.posX - var4.lastTickPosX) * par3;
            RenderManager.renderPosY = var4.lastTickPosY + (var4.posY - var4.lastTickPosY) * par3;
            RenderManager.renderPosZ = var4.lastTickPosZ + (var4.posZ - var4.lastTickPosZ) * par3;
            TileEntityRenderer.staticPlayerX = var4.lastTickPosX + (var4.posX - var4.lastTickPosX) * par3;
            TileEntityRenderer.staticPlayerY = var4.lastTickPosY + (var4.posY - var4.lastTickPosY) * par3;
            TileEntityRenderer.staticPlayerZ = var4.lastTickPosZ + (var4.posZ - var4.lastTickPosZ) * par3;
            this.mc.entityRenderer.enableLightmap(par3);
            this.theWorld.theProfiler.endStartSection("global");
            List var5 = this.theWorld.getLoadedEntityList();
            this.countEntitiesTotal = var5.size();

            for (int var6 = 0; var6 < this.theWorld.weatherEffects.size(); var6++) {
                Entity var7 = (Entity) this.theWorld.weatherEffects.get(var6);
                this.countEntitiesRendered++;
                if (var7.isInRangeToRenderVec3D(par1Vec3)) {
                    RenderManager.instance.renderEntity(var7, par3);
                }
            }

            this.theWorld.theProfiler.endStartSection("entities");
            Packet87SetDespawnCounters packet87 = System.currentTimeMillis() >= this.time_to_send_next_packet87 ? new Packet87SetDespawnCounters() : null;

            for (int var15 = 0; var15 < var5.size(); var15++) {
                Entity var7 = (Entity) var5.get(var15);
                boolean render_override = EntityVisibility.renderOverride(var7, this.mc);
                boolean accepted = EntityVisibility.mitePasses(var7, par1Vec3, par2ICamera, this.mc, render_override);
                ShadowCheck.onEntityDecision(var7, accepted, par1Vec3, par2ICamera, render_override);
                if (accepted) {
                    this.countEntitiesRendered++;
                    boolean drawn = EntityVisibility.inVisibleSections(var7, render_override);
                    if (drawn) {
                        double prevX = var7.lastTickPosX;
                        double prevY = var7.lastTickPosY;
                        double prevZ = var7.lastTickPosZ;
                        RenderManager.instance.renderEntity(var7, par3);
                        ShadowCheck.onLastTickPosWrite(var7, prevX, prevY, prevZ);
                    } else {
                        EntityVisibility.syncCulledEntity(var7, this.mc);
                    }
                    ShadowCheck.onDrawDecision(var7, drawn);
                    if (packet87 != null
                            && var7 instanceof EntityLiving
                            && var7.getAsEntityLiving().isConsideredInViewOfPlayerForDespawningPurposes(Minecraft.getClientPlayer())) {
                        packet87.add(var7.entityId, (short) (var7 instanceof EntityWaterMob ? -9600 : -400));
                    }
                }
            }

            if (packet87 != null) {
                this.time_to_send_next_packet87 = System.currentTimeMillis() + 1000L;
                if (packet87.entries > 0) {
                    Minecraft.getClientPlayer().sendQueue.addToSendQueue(packet87);
                }
            }

            this.theWorld.theProfiler.endStartSection("tileentities");
            Lighting.enableStandard();

            for (int var16 = 0; var16 < this.tileEntities.size(); var16++) {
                TileEntity te = (TileEntity) this.tileEntities.get(var16);
                if (EntityVisibility.tileEntityVisible(te)) {
                    TileEntityRenderer.instance.renderTileEntity(te, par3);
                }
            }

            this.mc.entityRenderer.disableLightmap(par3);
            this.theWorld.theProfiler.endSection();
        }
    }
}

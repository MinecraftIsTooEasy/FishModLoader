package net.xiaoyu233.fml.vulkan.mixin.nat.terrain;

import net.minecraft.EntityLivingBase;
import net.minecraft.ICamera;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.RenderGlobal;
import net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "terrain": per-frame methods of RenderGlobal forward to TerrainRenderer (NR-09, 2.2). */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalTerrainMixin {
    @Shadow
    private Minecraft mc;
    @Shadow
    private int renderDistance;
    @Shadow
    double prevSortX;
    @Shadow
    double prevSortY;
    @Shadow
    double prevSortZ;
    @Shadow
    public boolean include_all_world_renderers_in_next_sorting;

    @Shadow
    public abstract void loadRenderers();

    @Shadow
    private void markRenderersForNewPosition(int x, int y, int z) {
    }

    /** MITE's per-frame ring maintenance: render-distance change and the "moved more than 4 blocks" reposition. */
    private void fml$maintainRing(EntityLivingBase viewer) {
        this.include_all_world_renderers_in_next_sorting = false;
        if (this.mc.gameSettings.getRenderDistance() != this.renderDistance) {
            this.loadRenderers();
        }
        double dx = viewer.posX - this.prevSortX;
        double dy = viewer.posY - this.prevSortY;
        double dz = viewer.posZ - this.prevSortZ;
        if (dx * dx + dy * dy + dz * dz > 16.0) {
            this.prevSortX = viewer.posX;
            this.prevSortY = viewer.posY;
            this.prevSortZ = viewer.posZ;
            this.markRenderersForNewPosition(MathHelper.floor_double(viewer.posX), MathHelper.floor_double(viewer.posY),
                    MathHelper.floor_double(viewer.posZ));
        }
    }

    /**
     * @author fml-native
     * @reason native renderer: pass 0 draws the opaque terrain (returns 0), pass 1 the translucent terrain
     */
    @Overwrite
    public int sortAndRender(EntityLivingBase viewer, int pass, double partial) {
        this.fml$maintainRing(viewer);
        if (pass == 0) {
            TerrainRenderer.drawOpaque();
            return 0;
        }
        return TerrainRenderer.drawTranslucent();
    }

    /**
     * @author fml-native
     * @reason native renderer: occlusion queries do not exist
     */
    @Overwrite
    private void checkOcclusionQueryResult(int from, int to) {
    }

    /**
     * @author fml-native
     * @reason native renderer: drawing is done by TerrainRenderer
     */
    @Overwrite
    private int renderSortedRenderers(int from, int to, int pass, double partial) {
        return 0;
    }

    /**
     * @author fml-native
     * @reason native renderer: no RenderList batches
     */
    @Overwrite
    public void renderAllRenderLists(int pass, double partial) {
    }

    /**
     * @author fml-native
     * @reason native renderer: legacy synchronous rebuild loop is replaced by TerrainRenderer.updateChunks
     */
    @Overwrite
    public boolean XXXupdateRenderersMITE(EntityLivingBase viewer, boolean force, int iteration) {
        return true;
    }

    /**
     * @author fml-native
     * @reason native renderer: section rebuilds are scheduled by TerrainRenderer.updateChunks
     */
    @Overwrite
    public boolean updateRenderers(EntityLivingBase viewer, boolean force) {
        if (this.mc.force_rendering_for_screenshot) {
            force = true;
            this.mc.force_rendering_for_screenshot = false;
        }
        return TerrainRenderer.updateChunks(viewer, 0L, force);
    }

    /**
     * @author fml-native
     * @reason native renderer: frustum clipping and visibility are done by TerrainRenderer.setupFrame
     */
    @Overwrite
    public void clipRenderersByFrustum(ICamera camera, float partial) {
        EntityLivingBase viewer = this.mc.renderViewEntity;
        if (viewer == null) {
            return;
        }
        this.fml$maintainRing(viewer);
        double x = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partial;
        double y = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partial;
        double z = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partial;
        TerrainRenderer.setupFrame(camera, x, y, z, partial);
    }
}

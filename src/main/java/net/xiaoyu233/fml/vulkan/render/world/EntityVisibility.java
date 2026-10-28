package net.xiaoyu233.fml.vulkan.render.world;

import net.minecraft.Chunk;
import net.minecraft.Entity;
import net.minecraft.EntityDragon;
import net.minecraft.EntityDragonPart;
import net.minecraft.EntityItem;
import net.minecraft.EntityLiving;
import net.minecraft.EntityPlayer;
import net.minecraft.ICamera;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.RenderGlobal;
import net.minecraft.Vec3;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer;

/**
 * Per-entity decision of {@code RenderGlobal.renderEntities} (NR-12): MITE's predicate chain (range, frustum, ignoreFrustumCheck, rider,
 * other players, leash, render override, viewer in first person, chunk exists) evaluated exactly as MITE, plus the ENT-04/05 visible-section
 * cull that may only remove draws AFTER MITE's tests accepted the entity (so the Packet87 despawn-counter set stays MITE's).
 */
public final class EntityVisibility {
    private static final boolean CULL = Boolean.getBoolean("fml.vulkan.entityCull");

    private EntityVisibility() {
    }

    /** MITE's {@code render_override} (near non-player entity, dragon / dragon part). */
    public static boolean renderOverride(Entity e, Minecraft mc) {
        if (e instanceof EntityDragon || e instanceof EntityDragonPart) {
            return true;
        }
        return e != mc.thePlayer && e.getDistanceSqToEntity(mc.thePlayer) < 16.0;
    }

    /** MITE's complete accept test; this set (not the ENT-04 culled one) feeds the Packet87 despawn counters. */
    public static boolean mitePasses(Entity e, Vec3 viewPos, ICamera camera, Minecraft mc, boolean renderOverride) {
        boolean var8 = renderOverride
                ? true
                : e.isInRangeToRenderVec3D(viewPos)
                && (e.ignoreFrustumCheck
                || camera.isBoundingBoxInFrustum(e.boundingBox)
                || e.riddenByEntity == mc.thePlayer
                || e instanceof EntityPlayer && e != mc.thePlayer);
        if (!var8 && e instanceof EntityLiving) {
            EntityLiving var9 = (EntityLiving) e;
            if (var9.getLeashed() && var9.getLeashedToEntity() != null) {
                Entity var10 = var9.getLeashedToEntity();
                var8 = camera.isBoundingBoxInFrustum(var10.boundingBox);
            }
        }
        return var8
                && (e != mc.renderViewEntity || mc.gameSettings.thirdPersonView != 0 || mc.renderViewEntity.inBed())
                && (mc.theWorld.blockExists(MathHelper.floor_double(e.posX), 0, MathHelper.floor_double(e.posZ)) || renderOverride);
    }

    /** ENT-04 for an entity whose render_override state is computed here (see {@link #inVisibleSections(Entity, boolean)}). */
    public static boolean inVisibleSections(Entity e) {
        return !CULL || inVisibleSections(e, renderOverride(e, Minecraft.getMinecraft()));
    }

    /**
     * ENT-04 (conservative): false only when every section overlapping the entity's box grown by 1 block is outside the visible set.
     * Never culls render_override entities (near non-player entities, dragon and dragon parts); always true when culling is off.
     */
    public static boolean inVisibleSections(Entity e, boolean renderOverride) {
        if (!CULL || renderOverride) {
            return true;
        }
        int x0 = MathHelper.floor_double(e.boundingBox.minX - 1.0);
        int x1 = MathHelper.floor_double(e.boundingBox.maxX + 1.0);
        int y0 = MathHelper.floor_double(e.boundingBox.minY - 1.0);
        int y1 = MathHelper.floor_double(e.boundingBox.maxY + 1.0);
        int z0 = MathHelper.floor_double(e.boundingBox.minZ - 1.0);
        int z1 = MathHelper.floor_double(e.boundingBox.maxZ + 1.0);
        // Sections are 16 blocks; the representative block of each overlapped section is clamped into the box range.
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
            int bx = Math.max(x0, sx << 4);
            for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                int by = Math.max(y0, sy << 4);
                for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                    int bz = Math.max(z0, sz << 4);
                    if (TerrainRenderer.isBlockVisible(bx, by, bz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** ENT-05 for tile entities. */
    public static boolean tileEntityVisible(net.minecraft.TileEntity te) {
        return !CULL || TerrainRenderer.isBlockVisible(te.xCoord, te.yCoord, te.zCoord);
    }

    /**
     * Side effects of MITE's {@code RenderManager.renderEntity} for an accepted entity whose draw was culled by ENT-04: the EntityItem early
     * returns, the world-renderer index cache ({@code index_of_last_applicable_world_renderer}) and the {@code ticksExisted == 0}
     * {@code lastTickPos} reset. The draw itself is skipped; these writes are reproduced so the entity state matches MITE's.
     */
    public static void syncCulledEntity(Entity e, Minecraft mc) {
        if (e instanceof EntityItem) {
            if (e.ticksExisted < 1) {
                return;
            }
            if (e.ticksExisted < 5 && e.worldObj.doBlockCollisionBoundsIntersectWithBB(
                    e.getBlockPosX(), e.getBlockPosY(), e.getBlockPosZ(), e.boundingBox)) {
                return;
            }
        }
        Chunk chunk = e.worldObj.getChunkFromBlockCoords(e.getBlockPosX(), e.getBlockPosZ());
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        int x = e.getBlockPosX();
        int y = e.getBlockPosY();
        int z = e.getBlockPosZ();
        RenderGlobal renderGlobal = mc.renderGlobal;
        WorldRenderer worldRenderer = null;
        if (e.index_of_last_applicable_world_renderer < renderGlobal.worldRenderers.length) {
            worldRenderer = renderGlobal.worldRenderers[e.index_of_last_applicable_world_renderer];
            if (worldRenderer == null || !worldRenderer.isRenderingCoords(x, y, z)) {
                worldRenderer = null;
            }
        }
        if (worldRenderer == null) {
            worldRenderer = renderGlobal.getWorldRendererFor(x, y, z, e);
        }
        if (worldRenderer != null && worldRenderer.isInitialized && e.ticksExisted == 0) {
            e.lastTickPosX = e.posX;
            e.lastTickPosY = e.posY;
            e.lastTickPosZ = e.posZ;
        }
    }
}

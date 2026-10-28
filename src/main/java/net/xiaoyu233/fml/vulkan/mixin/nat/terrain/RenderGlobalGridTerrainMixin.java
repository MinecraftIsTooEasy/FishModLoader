package net.xiaoyu233.fml.vulkan.mixin.nat.terrain;

import java.util.List;
import net.minecraft.Block;
import net.minecraft.Entity;
import net.minecraft.EntityLivingBase;
import net.minecraft.MathHelper;
import net.minecraft.Minecraft;
import net.minecraft.RenderBlocks;
import net.minecraft.RenderGlobal;
import net.minecraft.RenderManager;
import net.minecraft.World;
import net.minecraft.WorldClient;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.render.mesh.MeshWorkers;
import net.xiaoyu233.fml.vulkan.render.terrain.TerrainGrid;
import net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "terrain": section grid, lookup and dirty marking of RenderGlobal (NR-09, 2.1). */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalGridTerrainMixin {
    @Shadow
    private WorldClient theWorld;
    @Shadow
    private Minecraft mc;
    @Shadow
    private List worldRenderersToUpdate;
    @Shadow
    private WorldRenderer[] sortedWorldRenderers;
    @Shadow
    public WorldRenderer[] worldRenderers;
    @Shadow
    private int renderChunksWide;
    @Shadow
    private int renderChunksTall;
    @Shadow
    private int renderChunksDeep;
    @Shadow
    private int glRenderListBase;
    @Shadow
    private RenderBlocks globalRenderBlocks;
    @Shadow
    private int renderDistance;
    @Shadow
    private int renderEntitiesStartupCounter;
    @Shadow
    public List tileEntities;
    @Shadow
    double prevSortX;
    @Shadow
    double prevSortY;
    @Shadow
    double prevSortZ;
    @Shadow
    private int minBlockX;
    @Shadow
    private int minBlockY;
    @Shadow
    private int minBlockZ;
    @Shadow
    private int maxBlockX;
    @Shadow
    private int maxBlockY;
    @Shadow
    private int maxBlockZ;

    /**
     * @author fml-native
     * @reason native renderer: world change also resets the mesh workers and the terrain state
     */
    @Overwrite
    public void setWorldAndLoadRenderers(WorldClient world) {
        if (this.theWorld != null) {
            this.theWorld.removeWorldAccess((RenderGlobal) (Object) this);
        }
        this.prevSortX = -9999.0;
        this.prevSortY = -9999.0;
        this.prevSortZ = -9999.0;
        RenderManager.instance.set(world);
        this.theWorld = world;
        this.globalRenderBlocks = new RenderBlocks(world);
        MeshWorkers.bumpEpoch("setWorld");
        TerrainRenderer.onWorldChanged(world);
        if (world != null) {
            world.addWorldAccess((RenderGlobal) (Object) this);
            this.loadRenderers();
        }
    }

    /**
     * @author fml-native
     * @reason native renderer: dirty marking goes through the terrain scheduler
     */
    @Overwrite
    public void markAllRenderersDirty() {
        for (int i = 0; i < this.worldRenderers.length; i++) {
            WorldRenderer r = this.worldRenderers[i];
            r.needsUpdate = true;
            TerrainRenderer.markDirty(r);
        }
    }

    /**
     * @author fml-native
     * @reason native renderer: dirty marking goes through the terrain scheduler
     */
    @Overwrite
    public void markAllRenderersUninitialized() {
        for (int i = 0; i < this.worldRenderers.length; i++) {
            WorldRenderer r = this.worldRenderers[i];
            r.isInitialized = false;
            r.needsUpdate = true;
            TerrainRenderer.markDirty(r);
        }
    }

    /**
     * @author fml-native
     * @reason native renderer: O(1) ring-index lookup (CUL-02) instead of a linear scan
     */
    @Overwrite
    public WorldRenderer getWorldRendererFor(int x, int y, int z, Entity entityForCachingIndex) {
        int index = TerrainGrid.indexFor(x, y, z);
        if (index < 0 || this.worldRenderers == null || index >= this.worldRenderers.length) {
            return null;
        }
        WorldRenderer r = this.worldRenderers[index];
        if (r != null && r.isRenderingCoords(x, y, z)) {
            if (entityForCachingIndex != null) {
                entityForCachingIndex.index_of_last_applicable_world_renderer = index;
            }
            return r;
        }
        return null;
    }

    /**
     * @author fml-native
     * @reason native renderer: O(1) ring-index lookup (CUL-02)
     */
    @Overwrite
    public WorldRenderer getWorldRendererFor(int x, int y, int z) {
        return this.getWorldRendererFor(x, y, z, null);
    }

    /**
     * @author fml-native
     * @reason native renderer: same ring grid as MITE without display lists or occlusion queries; hands the grid to TerrainRenderer
     */
    @Overwrite
    public void loadRenderers() {
        if (this.theWorld == null) {
            return;
        }
        Block.leaves.setGraphicsLevel(this.mc.gameSettings.isFancyGraphicsEnabled());
        this.renderDistance = this.mc.gameSettings.getRenderDistance();
        if (this.worldRenderers != null) {
            for (int i = 0; i < this.worldRenderers.length; i++) {
                this.worldRenderers[i].stopRendering();
            }
        }
        MeshWorkers.bumpEpoch("loadRenderers");

        int size = 64 << 3 - this.renderDistance;
        if (size > 400) {
            size = 400;
        }
        this.renderChunksWide = size / 16 + 1;
        this.renderChunksTall = 16;
        this.renderChunksDeep = size / 16 + 1;
        int wide = this.renderChunksWide;
        int tall = this.renderChunksTall;
        int deep = this.renderChunksDeep;
        this.worldRenderers = new WorldRenderer[wide * tall * deep];
        this.sortedWorldRenderers = new WorldRenderer[wide * tall * deep];
        int listId = 0;
        int chunkIndex = 0;
        this.minBlockX = 0;
        this.minBlockY = 0;
        this.minBlockZ = 0;
        this.maxBlockX = wide;
        this.maxBlockY = tall;
        this.maxBlockZ = deep;

        for (int i = 0; i < this.worldRenderersToUpdate.size(); i++) {
            ((WorldRenderer) this.worldRenderersToUpdate.get(i)).needsUpdate = false;
        }
        this.worldRenderersToUpdate.clear();
        this.tileEntities.clear();

        for (int x = 0; x < wide; x++) {
            for (int y = 0; y < tall; y++) {
                for (int z = 0; z < deep; z++) {
                    int idx = (z * tall + y) * wide + x;
                    WorldRenderer r = new WorldRenderer(this.theWorld, this.tileEntities, x * 16, y * 16, z * 16, this.glRenderListBase + listId);
                    r.isWaitingOnOcclusionQuery = false;
                    r.isVisible = true;
                    r.isInFrustum = true;
                    r.chunkIndex = chunkIndex++;
                    r.markDirty();
                    this.worldRenderers[idx] = r;
                    this.sortedWorldRenderers[idx] = r;
                    listId += 3;
                }
            }
        }

        TerrainRenderer.onLoadRenderers(this.worldRenderers, wide, tall, deep);

        EntityLivingBase viewer = this.mc.renderViewEntity;
        if (viewer != null) {
            this.markRenderersForNewPosition(MathHelper.floor_double(viewer.posX), MathHelper.floor_double(viewer.posY),
                    MathHelper.floor_double(viewer.posZ));
        }
        this.renderEntitiesStartupCounter = 2;
    }

    /**
     * @author fml-native
     * @reason native renderer: terrain debug counters come from the visibility BFS
     */
    @Overwrite
    public String getDebugInfoRenders() {
        return TerrainRenderer.debugInfo();
    }

    /**
     * @author fml-native
     * @reason native renderer: MITE ring arithmetic, but only changed renderers are marked dirty and the array is no longer sorted
     */
    @Overwrite
    private void markRenderersForNewPosition(int px, int py, int pz) {
        px -= 8;
        py -= 8;
        pz -= 8;
        this.minBlockX = Integer.MAX_VALUE;
        this.minBlockY = Integer.MAX_VALUE;
        this.minBlockZ = Integer.MAX_VALUE;
        this.maxBlockX = Integer.MIN_VALUE;
        this.maxBlockY = Integer.MIN_VALUE;
        this.maxBlockZ = Integer.MIN_VALUE;
        int span = this.renderChunksWide * 16;
        int half = span / 2;

        for (int ix = 0; ix < this.renderChunksWide; ix++) {
            int bx = ix * 16;
            int wrapX = bx + half - px;
            if (wrapX < 0) {
                wrapX -= span - 1;
            }
            wrapX /= span;
            bx -= wrapX * span;
            if (bx < this.minBlockX) {
                this.minBlockX = bx;
            }
            if (bx > this.maxBlockX) {
                this.maxBlockX = bx;
            }

            for (int iz = 0; iz < this.renderChunksDeep; iz++) {
                int bz = iz * 16;
                int wrapZ = bz + half - pz;
                if (wrapZ < 0) {
                    wrapZ -= span - 1;
                }
                wrapZ /= span;
                bz -= wrapZ * span;
                if (bz < this.minBlockZ) {
                    this.minBlockZ = bz;
                }
                if (bz > this.maxBlockZ) {
                    this.maxBlockZ = bz;
                }

                for (int iy = 0; iy < this.renderChunksTall; iy++) {
                    int by = iy * 16;
                    if (by < this.minBlockY) {
                        this.minBlockY = by;
                    }
                    if (by > this.maxBlockY) {
                        this.maxBlockY = by;
                    }
                    WorldRenderer r = this.worldRenderers[(iz * this.renderChunksTall + iy) * this.renderChunksWide + ix];
                    boolean wasDirty = r.needsUpdate;
                    r.setPosition(bx, by, bz);
                    if (!wasDirty && r.needsUpdate) {
                        TerrainRenderer.markDirty(r);
                    }
                }
            }
        }
    }

    /**
     * @author fml-native
     * @reason native renderer: MITE bucket/wrap arithmetic, dirty marks go to the terrain scheduler
     */
    @Overwrite
    public void markBlocksForUpdate(int x0, int y0, int z0, int x1, int y1, int z1) {
        int bx0 = MathHelper.bucketInt(x0, 16);
        int by0 = MathHelper.bucketInt(y0, 16);
        int bz0 = MathHelper.bucketInt(z0, 16);
        int bx1 = MathHelper.bucketInt(x1, 16);
        int by1 = MathHelper.bucketInt(y1, 16);
        int bz1 = MathHelper.bucketInt(z1, 16);

        for (int bx = bx0; bx <= bx1; bx++) {
            int ix = bx % this.renderChunksWide;
            if (ix < 0) {
                ix += this.renderChunksWide;
            }
            for (int by = by0; by <= by1; by++) {
                int iy = by % this.renderChunksTall;
                if (iy < 0) {
                    iy += this.renderChunksTall;
                }
                for (int bz = bz0; bz <= bz1; bz++) {
                    int iz = bz % this.renderChunksDeep;
                    if (iz < 0) {
                        iz += this.renderChunksDeep;
                    }
                    WorldRenderer r = this.worldRenderers[(iz * this.renderChunksTall + iy) * this.renderChunksWide + ix];
                    if (r != null && !r.needsUpdate) {
                        r.markDirty();
                        TerrainRenderer.markDirty(r);
                    }
                }
            }
        }
    }

    /**
     * @author fml-native
     * @reason native renderer: there are no per-section display lists to delete
     */
    @Overwrite
    public void deleteAllDisplayLists() {
        net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer.releaseGrid();
    }
}

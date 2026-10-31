package net.xiaoyu233.fml.vulkan.mixin.nat.terrain;

import java.util.HashSet;
import java.util.List;
import net.minecraft.AxisAlignedBB;
import net.minecraft.Minecraft;
import net.minecraft.TileEntity;
import net.minecraft.WorldRenderer;
import net.xiaoyu233.fml.vulkan.render.terrain.Section;
import net.xiaoyu233.fml.vulkan.render.terrain.SectionAccess;
import net.xiaoyu233.fml.vulkan.render.terrain.SectionCommit;
import net.xiaoyu233.fml.vulkan.render.terrain.TerrainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Native area "terrain": adds {@link SectionAccess} to WorldRenderer and replaces its display-list code with the native section pipeline. */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererTerrainMixin implements SectionAccess, SectionCommit.SectionTileAccess {
    @Shadow
    private int posX;
    @Shadow
    private int posY;
    @Shadow
    private int posZ;
    @Shadow
    private List tileEntities;
    @Shadow
    public List tileEntityRenderers;
    @Shadow
    public int posXMinus;
    @Shadow
    public int posYMinus;
    @Shadow
    public int posZMinus;
    @Shadow
    public int posXClip;
    @Shadow
    public int posYClip;
    @Shadow
    public int posZClip;
    @Shadow
    public int posXPlus;
    @Shadow
    public int posYPlus;
    @Shadow
    public int posZPlus;
    @Shadow
    public boolean isInFrustum;
    @Shadow
    public boolean isInitialized;
    @Shadow
    public boolean[] skipRenderPass;
    @Shadow
    public AxisAlignedBB rendererBoundingBox;

    @Unique
    private Section fml$section;

    @Override
    public Section fml$section() {
        if (fml$section == null) {
            fml$section = Section.create((WorldRenderer) (Object) this);
        }
        return fml$section;
    }

    /**
     * @author fml-native
     * @reason no display-list occlusion box; position changes mark the native section dirty
     */
    @Overwrite
    public void setPosition(int par1, int par2, int par3) {
        if (par1 != this.posX || par2 != this.posY || par3 != this.posZ) {
            this.setDontDraw();
            this.posX = par1;
            this.posY = par2;
            this.posZ = par3;
            this.posXPlus = par1 + 8;
            this.posYPlus = par2 + 8;
            this.posZPlus = par3 + 8;
            this.posXClip = par1 & 1023;
            this.posYClip = par2;
            this.posZClip = par3 & 1023;
            this.posXMinus = par1 - this.posXClip;
            this.posYMinus = par2 - this.posYClip;
            this.posZMinus = par3 - this.posZClip;
            float var4 = 6.0F;
            this.rendererBoundingBox = AxisAlignedBB.getBoundingBox(par1 - var4, par2 - var4, par3 - var4, par1 + 16 + var4, par2 + 16 + var4, par3 + 16 + var4);
            this.markDirty();
        }
    }

    /**
     * @author fml-native
     * @reason native section build + commit
     */
    @Overwrite
    public void updateRenderer() {
        TerrainRenderer.markDirty((WorldRenderer) (Object) this);
        TerrainRenderer.updateChunks(Minecraft.getMinecraft().renderViewEntity, 0L, true);
    }

    /**
     * @author fml-native
     * @reason display lists are gone
     */
    @Overwrite
    public int getGLCallListForPass(int par1) {
        return -1;
    }

    /**
     * @author fml-native
     * @reason no occlusion query lists
     */
    @Overwrite
    public void callOcclusionQueryList() {
    }

    /**
     * @author fml-native
     * @reason no GL translation
     */
    @Overwrite
    private void setupGLTranslation() {
    }

    /**
     * @author fml-native
     * @reason MITE behaviour, native section state is reset on the next build
     */
    @Overwrite
    public void setDontDraw() {
        for (int i = 0; i < 2; i++) {
            this.skipRenderPass[i] = true;
        }
        this.isInFrustum = false;
        this.isInitialized = false;
    }

    /**
     * @author fml-native
     * @reason routes through the native terrain renderer
     */
    @Overwrite
    public void markDirty() {
        TerrainRenderer.markDirty((WorldRenderer) (Object) this);
    }

    /** Render-thread scratch sets for fml$mergeTileEntities (lazily created, cleared after each use so they keep no tile entities). */
    @Unique
    private static HashSet fml$oldTiles;
    @Unique
    private static HashSet fml$newTiles;

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void fml$mergeTileEntities(List<TileEntity> now) {
        if (fml$oldTiles == null) {
            fml$oldTiles = new HashSet();
            fml$newTiles = new HashSet();
        }
        HashSet old = fml$oldTiles;
        HashSet cur = fml$newTiles;
        old.addAll(this.tileEntityRenderers);
        this.tileEntityRenderers.clear();
        this.tileEntityRenderers.addAll(now);
        cur.addAll(this.tileEntityRenderers);
        // added = cur \ old, applied to the shared list
        for (Object e : cur) {
            if (!old.contains(e)) {
                this.tileEntities.add(e);
            }
        }
        // removed = old \ cur, applied to the shared list
        old.removeAll(cur);
        this.tileEntities.removeAll(old);
        old.clear();
        cur.clear();
    }
}

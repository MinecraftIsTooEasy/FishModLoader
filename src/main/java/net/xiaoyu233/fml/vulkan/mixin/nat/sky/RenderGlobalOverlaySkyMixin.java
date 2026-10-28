package net.xiaoyu233.fml.vulkan.mixin.nat.sky;

import java.util.Map;
import net.minecraft.AxisAlignedBB;
import net.minecraft.EntityPlayer;
import net.minecraft.Icon;
import net.minecraft.RaycastCollision;
import net.minecraft.RenderBlocks;
import net.minecraft.RenderGlobal;
import net.minecraft.Tessellator;
import net.minecraft.TextureManager;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.render.sky.BlockDamageRenderer;
import net.xiaoyu233.fml.vulkan.render.sky.SelectionBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "sky": drawBlockDamageTexture, drawSelectionBox, drawOutlinedBoundingBox (subtask overlays). */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalOverlaySkyMixin {
    @Shadow
    private WorldClient theWorld;
    @Shadow @Final
    private TextureManager renderEngine;
    @Shadow
    private RenderBlocks globalRenderBlocks;
    @Shadow
    private Icon[] destroyBlockIcons;
    @Shadow
    private Map damagedBlocks;

    /**
     * @author fml-native
     * @reason native renderer: damage overlay re-meshed into the DRAW target
     */
    @Overwrite
    public void drawBlockDamageTexture(Tessellator tessellator, EntityPlayer player, float partialTicks) {
        BlockDamageRenderer.draw(this.damagedBlocks, this.theWorld, this.renderEngine, this.globalRenderBlocks, this.destroyBlockIcons,
                tessellator, player, partialTicks);
    }

    /**
     * @author fml-native
     * @reason native renderer: selection box with native state
     */
    @Overwrite
    public void drawSelectionBox(EntityPlayer player, RaycastCollision rc, int pass, float partialTicks) {
        SelectionBox.draw(this.theWorld, player, rc, pass, partialTicks);
    }

    /**
     * @author fml-native
     * @reason native renderer: outlined box through the LINES program
     */
    @Overwrite
    private void drawOutlinedBoundingBox(AxisAlignedBB box) {
        SelectionBox.drawOutlinedBoundingBox(box);
    }
}

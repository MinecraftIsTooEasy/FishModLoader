package net.xiaoyu233.fml.vulkan.render.sky;

import net.minecraft.AxisAlignedBB;
import net.minecraft.Block;
import net.minecraft.EntityPlayer;
import net.minecraft.RaycastCollision;
import net.minecraft.Tessellator;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

/** Selection box and outlined bounding box (3.12), NR-11. Line strips/lines go through Tessellator (NativeTess LINES program). */
public final class SelectionBox {
    private SelectionBox() {
    }

    /** Body of {@code RenderGlobal.drawSelectionBox}. */
    public static void draw(WorldClient world, EntityPlayer player, RaycastCollision rc, int pass, float partialTicks) {
        if (pass == 0 && rc.isBlock()) {
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            Draw.color(0.0F, 0.0F, 0.0F, 0.4F);
            Draw.lineWidth(2.0F);
            Draw.texturing(false);
            Draw.depthMask(false);
            float expand = 0.002F;
            Block block = rc.getBlockHit();
            double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
            double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
            double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
            drawOutlinedBoundingBox(block.getSelectedBoundingBoxFromPool(world, rc.block_hit_x, rc.block_hit_y, rc.block_hit_z)
                    .expand(expand, expand, expand).getOffsetBoundingBox(-x, -y, -z));
            Draw.depthMask(true);
            Draw.texturing(true);
            Draw.blend(false);
        }
    }

    /** Body of {@code RenderGlobal.drawOutlinedBoundingBox}: 2 line strips + 4 lines, LINES program, 2 px. */
    public static void drawOutlinedBoundingBox(AxisAlignedBB b) {
        Tessellator t = Tessellator.instance;
        t.startDrawing(3);
        t.addVertex(b.minX, b.minY, b.minZ);
        t.addVertex(b.maxX, b.minY, b.minZ);
        t.addVertex(b.maxX, b.minY, b.maxZ);
        t.addVertex(b.minX, b.minY, b.maxZ);
        t.addVertex(b.minX, b.minY, b.minZ);
        t.draw();
        t.startDrawing(3);
        t.addVertex(b.minX, b.maxY, b.minZ);
        t.addVertex(b.maxX, b.maxY, b.minZ);
        t.addVertex(b.maxX, b.maxY, b.maxZ);
        t.addVertex(b.minX, b.maxY, b.maxZ);
        t.addVertex(b.minX, b.maxY, b.minZ);
        t.draw();
        t.startDrawing(1);
        t.addVertex(b.minX, b.minY, b.minZ);
        t.addVertex(b.minX, b.maxY, b.minZ);
        t.addVertex(b.maxX, b.minY, b.minZ);
        t.addVertex(b.maxX, b.maxY, b.minZ);
        t.addVertex(b.maxX, b.minY, b.maxZ);
        t.addVertex(b.maxX, b.maxY, b.maxZ);
        t.addVertex(b.minX, b.minY, b.maxZ);
        t.addVertex(b.minX, b.maxY, b.maxZ);
        t.draw();
    }
}

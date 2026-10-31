package net.xiaoyu233.fml.vulkan.render.item;

import net.minecraft.Block;
import net.minecraft.RenderBlocks;
import net.minecraft.Tessellator;

/** Static helpers for RenderBlocks.renderBlockAsItem (NR-14). Exact MITE arithmetic and call order. */
public final class BlockItemModels {
    private BlockItemModels() {
    }

    /**
     * The six per-face startDrawingQuads/setNormal/renderFace/draw sequences of MITE's
     * renderBlockAsItem, using the render bounds currently set on {@code rb}.
     *
     * @param withMeta true: icons through getBlockIconFromSideAndMetadata(block, side, meta); false: getBlockIconFromSide
     */
    public static void drawSixFaces(RenderBlocks rb, Tessellator tess, Block block, int meta, boolean withMeta) {
        tess.startDrawingQuads();
        tess.setNormal(0.0F, -1.0F, 0.0F);
        rb.renderFaceYNeg(block, 0.0, 0.0, 0.0, icon(rb, block, 0, meta, withMeta));
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(0.0F, 1.0F, 0.0F);
        rb.renderFaceYPos(block, 0.0, 0.0, 0.0, icon(rb, block, 1, meta, withMeta));
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(0.0F, 0.0F, -1.0F);
        rb.renderFaceZNeg(block, 0.0, 0.0, 0.0, icon(rb, block, 2, meta, withMeta));
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(0.0F, 0.0F, 1.0F);
        rb.renderFaceZPos(block, 0.0, 0.0, 0.0, icon(rb, block, 3, meta, withMeta));
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(-1.0F, 0.0F, 0.0F);
        rb.renderFaceXNeg(block, 0.0, 0.0, 0.0, icon(rb, block, 4, meta, withMeta));
        tess.draw();
        tess.startDrawingQuads();
        tess.setNormal(1.0F, 0.0F, 0.0F);
        rb.renderFaceXPos(block, 0.0, 0.0, 0.0, icon(rb, block, 5, meta, withMeta));
        tess.draw();
    }

    /** The six faces without startDrawingQuads/draw (render type 27, one shared draw). */
    public static void addSixFaces(RenderBlocks rb, Tessellator tess, Block block) {
        tess.setNormal(0.0F, -1.0F, 0.0F);
        rb.renderFaceYNeg(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 0));
        tess.setNormal(0.0F, 1.0F, 0.0F);
        rb.renderFaceYPos(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 1));
        tess.setNormal(0.0F, 0.0F, -1.0F);
        rb.renderFaceZNeg(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 2));
        tess.setNormal(0.0F, 0.0F, 1.0F);
        rb.renderFaceZPos(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 3));
        tess.setNormal(-1.0F, 0.0F, 0.0F);
        rb.renderFaceXNeg(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 4));
        tess.setNormal(1.0F, 0.0F, 0.0F);
        rb.renderFaceXPos(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 5));
    }

    private static net.minecraft.Icon icon(RenderBlocks rb, Block block, int side, int meta, boolean withMeta) {
        return withMeta ? rb.getBlockIconFromSideAndMetadata(block, side, meta) : rb.getBlockIconFromSide(block, side);
    }
}

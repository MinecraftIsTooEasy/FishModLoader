package net.xiaoyu233.fml.vulkan.mixin.nat.item;

import net.minecraft.Block;
import net.minecraft.BlockAnvil;
import net.minecraft.BlockFurnace;
import net.minecraft.BlockHopper;
import net.minecraft.ChestItemRenderHelper;
import net.minecraft.RenderBlocks;
import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.render.item.BlockItemModels;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** NR-14: native RenderBlocks.renderBlockAsItem (per-face draws, Draw.* instead of GL11). */
@Mixin(RenderBlocks.class)
public abstract class RenderBlocksItemMixin {
    @Shadow
    private double renderMaxY;

    @Shadow
    private boolean renderBlockAnvilOrient(BlockAnvil anvil, int x, int y, int z, int meta, boolean item) {
        throw new AssertionError();
    }

    @Shadow
    private boolean renderBlockHopperMetadata(BlockHopper hopper, int x, int y, int z, int meta, boolean item) {
        throw new AssertionError();
    }

    /**
     * @author fml-native
     * @reason native renderer: GL11 matrix/colour calls replaced by Draw
     */
    @Overwrite
    public void renderBlockAsItem(Block block, int meta, float brightness) {
        final RenderBlocks rb = (RenderBlocks) (Object) this;
        Tessellator tess = Tessellator.instance;
        boolean isGrass = block.blockID == Block.grass.blockID;
        if (block == Block.dispenser || block == Block.dropper || block instanceof BlockFurnace) {
            meta = 3;
        }

        if (rb.useInventoryTint) {
            int color = block.getRenderColor(meta);
            if (isGrass) {
                color = 16777215;
            }
            float r = (color >> 16 & 0xFF) / 255.0F;
            float g = (color >> 8 & 0xFF) / 255.0F;
            float b = (color & 0xFF) / 255.0F;
            Draw.color(r * brightness, g * brightness, b * brightness, 1.0F);
        }

        int type = block.getRenderType();
        if (block.isAlwaysStandardFormCube()) {
            rb.setRenderBoundsForStandardFormBlock();
        } else {
            rb.setRenderBoundsForNonStandardFormBlock(block);
        }

        if (type != 0 && type != 31 && type != 39 && type != 16 && type != 26) {
            if (type == 1) {
                tess.startDrawingQuads();
                tess.setNormal(0.0F, -1.0F, 0.0F);
                rb.drawCrossedSquares(block, meta, -0.5, -0.5, -0.5, 1.0F);
                tess.draw();
            } else if (type == 19) {
                tess.startDrawingQuads();
                tess.setNormal(0.0F, -1.0F, 0.0F);
                block.setBlockBoundsForItemRender(meta);
                rb.renderBlockStemSmall(block, meta, this.renderMaxY, -0.5, -0.5, -0.5);
                tess.draw();
            } else if (type == 23) {
                tess.startDrawingQuads();
                tess.setNormal(0.0F, -1.0F, 0.0F);
                block.setBlockBoundsForItemRender(meta);
                tess.draw();
            } else if (type == 13) {
                block.setBlockBoundsForItemRender(meta);
                Draw.translate(-0.5F, -0.5F, -0.5F);
                float d = 0.0625F;
                tess.startDrawingQuads();
                tess.setNormal(0.0F, -1.0F, 0.0F);
                rb.renderFaceYNeg(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 0));
                tess.draw();
                tess.startDrawingQuads();
                tess.setNormal(0.0F, 1.0F, 0.0F);
                rb.renderFaceYPos(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 1));
                tess.draw();
                tess.startDrawingQuads();
                tess.setNormal(0.0F, 0.0F, -1.0F);
                tess.addTranslation(0.0F, 0.0F, d);
                rb.renderFaceZNeg(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 2));
                tess.addTranslation(0.0F, 0.0F, -d);
                tess.draw();
                tess.startDrawingQuads();
                tess.setNormal(0.0F, 0.0F, 1.0F);
                tess.addTranslation(0.0F, 0.0F, -d);
                rb.renderFaceZPos(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 3));
                tess.addTranslation(0.0F, 0.0F, d);
                tess.draw();
                tess.startDrawingQuads();
                tess.setNormal(-1.0F, 0.0F, 0.0F);
                tess.addTranslation(d, 0.0F, 0.0F);
                rb.renderFaceXNeg(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 4));
                tess.addTranslation(-d, 0.0F, 0.0F);
                tess.draw();
                tess.startDrawingQuads();
                tess.setNormal(1.0F, 0.0F, 0.0F);
                tess.addTranslation(-d, 0.0F, 0.0F);
                rb.renderFaceXPos(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSide(block, 5));
                tess.addTranslation(d, 0.0F, 0.0F);
                tess.draw();
                Draw.translate(0.5F, 0.5F, 0.5F);
            } else if (type == 22) {
                Draw.rotate(90.0F, 0.0F, 1.0F, 0.0F);
                Draw.translate(-0.5F, -0.5F, -0.5F);
                ChestItemRenderHelper.instance.renderChest(block, meta, brightness);
                Draw.rescaleNormal(true);
            } else if (type == 6) {
                tess.startDrawingQuads();
                tess.setNormal(0.0F, -1.0F, 0.0F);
                rb.renderBlockCropsImpl(block, meta, -0.5, -0.5, -0.5);
                tess.draw();
            } else if (type == 2) {
                tess.startDrawingQuads();
                tess.setNormal(0.0F, -1.0F, 0.0F);
                rb.renderTorchAtAngle(block, -0.5, -0.5, -0.5, 0.0, 0.0, 0);
                tess.draw();
            } else if (type == 10) {
                for (int i = 0; i < 2; i++) {
                    if (i == 0) {
                        rb.setRenderBounds(0.0, 0.0, 0.0, 1.0, 1.0, 0.5);
                    }
                    if (i == 1) {
                        rb.setRenderBounds(0.0, 0.0, 0.5, 1.0, 0.5, 1.0);
                    }
                    Draw.translate(-0.5F, -0.5F, -0.5F);
                    BlockItemModels.drawSixFaces(rb, tess, block, meta, false);
                    Draw.translate(0.5F, 0.5F, 0.5F);
                }
            } else if (type == 27) {
                int yOff = 0;
                Draw.translate(-0.5F, -0.5F, -0.5F);
                tess.startDrawingQuads();

                for (int i = 0; i < 8; i++) {
                    byte half = 0;
                    byte height = 1;
                    if (i == 0) {
                        half = 2;
                    }
                    if (i == 1) {
                        half = 3;
                    }
                    if (i == 2) {
                        half = 4;
                    }
                    if (i == 3) {
                        half = 5;
                        height = 2;
                    }
                    if (i == 4) {
                        half = 6;
                        height = 3;
                    }
                    if (i == 5) {
                        half = 7;
                        height = 5;
                    }
                    if (i == 6) {
                        half = 6;
                        height = 2;
                    }
                    if (i == 7) {
                        half = 3;
                    }

                    float w = half / 16.0F;
                    float top = 1.0F - yOff / 16.0F;
                    float bottom = 1.0F - (yOff + height) / 16.0F;
                    yOff += height;
                    rb.setRenderBounds(0.5F - w, bottom, 0.5F - w, 0.5F + w, top, 0.5F + w);
                    BlockItemModels.addSixFaces(rb, tess, block);
                }

                tess.draw();
                Draw.translate(0.5F, 0.5F, 0.5F);
                rb.setRenderBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
            } else if (type == 11) {
                for (int i = 0; i < 4; i++) {
                    float s = 0.125F;
                    if (i == 0) {
                        rb.setRenderBounds(0.5F - s, 0.0, 0.0, 0.5F + s, 1.0, s * 2.0F);
                    }
                    if (i == 1) {
                        rb.setRenderBounds(0.5F - s, 0.0, 1.0F - s * 2.0F, 0.5F + s, 1.0, 1.0);
                    }

                    s = 0.0625F;
                    if (i == 2) {
                        rb.setRenderBounds(0.5F - s, 1.0F - s * 3.0F, -s * 2.0F, 0.5F + s, 1.0F - s, 1.0F + s * 2.0F);
                    }
                    if (i == 3) {
                        rb.setRenderBounds(0.5F - s, 0.5F - s * 3.0F, -s * 2.0F, 0.5F + s, 0.5F - s, 1.0F + s * 2.0F);
                    }

                    Draw.translate(-0.5F, -0.5F, -0.5F);
                    BlockItemModels.drawSixFaces(rb, tess, block, meta, false);
                    Draw.translate(0.5F, 0.5F, 0.5F);
                }

                rb.setRenderBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
            } else if (type == 21) {
                for (int i = 0; i < 3; i++) {
                    float s = 0.0625F;
                    if (i == 0) {
                        rb.setRenderBounds(0.5F - s, 0.3F, 0.0, 0.5F + s, 1.0, s * 2.0F);
                    }
                    if (i == 1) {
                        rb.setRenderBounds(0.5F - s, 0.3F, 1.0F - s * 2.0F, 0.5F + s, 1.0, 1.0);
                    }

                    s = 0.0625F;
                    if (i == 2) {
                        rb.setRenderBounds(0.5F - s, 0.5, 0.0, 0.5F + s, 1.0F - s, 1.0);
                    }

                    Draw.translate(-0.5F, -0.5F, -0.5F);
                    BlockItemModels.drawSixFaces(rb, tess, block, meta, false);
                    Draw.translate(0.5F, 0.5F, 0.5F);
                }
            } else if (type == 32) {
                for (int i = 0; i < 2; i++) {
                    if (i == 0) {
                        rb.setRenderBounds(0.0, 0.0, 0.3125, 1.0, 0.8125, 0.6875);
                    }
                    if (i == 1) {
                        rb.setRenderBounds(0.25, 0.0, 0.25, 0.75, 1.0, 0.75);
                    }

                    Draw.translate(-0.5F, -0.5F, -0.5F);
                    BlockItemModels.drawSixFaces(rb, tess, block, meta, true);
                    Draw.translate(0.5F, 0.5F, 0.5F);
                }

                rb.setRenderBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
            } else if (type == 35) {
                Draw.translate(-0.5F, -0.5F, -0.5F);
                this.renderBlockAnvilOrient((BlockAnvil) block, 0, 0, 0, meta << 2, true);
                Draw.translate(0.5F, 0.5F, 0.5F);
            } else if (type == 34) {
                for (int i = 0; i < 3; i++) {
                    if (i == 0) {
                        rb.setRenderBounds(0.125, 0.0, 0.125, 0.875, 0.1875, 0.875);
                        rb.setOverrideBlockTexture(rb.getBlockIcon(Block.obsidian));
                    } else if (i == 1) {
                        rb.setRenderBounds(0.1875, 0.1875, 0.1875, 0.8125, 0.875, 0.8125);
                        rb.setOverrideBlockTexture(rb.getBlockIcon(Block.beacon));
                    } else if (i == 2) {
                        rb.setRenderBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
                        rb.setOverrideBlockTexture(rb.getBlockIcon(Block.glass));
                    }

                    Draw.translate(-0.5F, -0.5F, -0.5F);
                    BlockItemModels.drawSixFaces(rb, tess, block, meta, true);
                    Draw.translate(0.5F, 0.5F, 0.5F);
                }

                rb.setRenderBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
                rb.clearOverrideBlockTexture();
            } else if (type == 38) {
                Draw.translate(-0.5F, -0.5F, -0.5F);
                this.renderBlockHopperMetadata((BlockHopper) block, 0, 0, 0, 0, true);
                Draw.translate(0.5F, 0.5F, 0.5F);
            }
        } else {
            if (type == 16) {
                meta = 1;
            }

            block.setBlockBoundsForItemRender(meta);
            if (block.isAlwaysStandardFormCube()) {
                rb.setRenderBoundsForStandardFormBlock();
            } else {
                rb.setRenderBoundsForNonStandardFormBlock(block);
            }

            Draw.rotate(90.0F, 0.0F, 1.0F, 0.0F);
            Draw.translate(-0.5F, -0.5F, -0.5F);
            tess.startDrawingQuads();
            tess.setNormal(0.0F, -1.0F, 0.0F);
            rb.renderFaceYNeg(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSideAndMetadata(block, 0, meta));
            tess.draw();
            if (isGrass && rb.useInventoryTint) {
                int color = block.getRenderColor(meta);
                float r = (color >> 16 & 0xFF) / 255.0F;
                float g = (color >> 8 & 0xFF) / 255.0F;
                float b = (color & 0xFF) / 255.0F;
                Draw.color(r * brightness, g * brightness, b * brightness, 1.0F);
            }

            tess.startDrawingQuads();
            tess.setNormal(0.0F, 1.0F, 0.0F);
            rb.renderFaceYPos(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSideAndMetadata(block, 1, meta));
            tess.draw();
            if (isGrass && rb.useInventoryTint) {
                Draw.color(brightness, brightness, brightness, 1.0F);
            }

            tess.startDrawingQuads();
            tess.setNormal(0.0F, 0.0F, -1.0F);
            rb.renderFaceZNeg(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSideAndMetadata(block, 2, meta));
            tess.draw();
            tess.startDrawingQuads();
            tess.setNormal(0.0F, 0.0F, 1.0F);
            rb.renderFaceZPos(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSideAndMetadata(block, 3, meta));
            tess.draw();
            tess.startDrawingQuads();
            tess.setNormal(-1.0F, 0.0F, 0.0F);
            rb.renderFaceXNeg(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSideAndMetadata(block, 4, meta));
            tess.draw();
            tess.startDrawingQuads();
            tess.setNormal(1.0F, 0.0F, 0.0F);
            rb.renderFaceXPos(block, 0.0, 0.0, 0.0, rb.getBlockIconFromSideAndMetadata(block, 5, meta));
            tess.draw();
            Draw.translate(0.5F, 0.5F, 0.5F);
        }
    }
}

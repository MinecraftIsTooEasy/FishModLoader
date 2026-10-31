package net.xiaoyu233.fml.vulkan.render.sky;

import java.util.Iterator;
import java.util.Map;
import net.minecraft.Block;
import net.minecraft.DestroyBlockProgress;
import net.minecraft.EntityPlayer;
import net.minecraft.Icon;
import net.minecraft.RenderBlocks;
import net.minecraft.Tessellator;
import net.minecraft.TextureManager;
import net.minecraft.TextureMap;
import net.minecraft.WorldClient;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

/** Block damage overlay (3.12 / 2.8), NR-11. Re-meshes damaged blocks every frame through the Tessellator (DRAW target). */
public final class BlockDamageRenderer {
    private BlockDamageRenderer() {
    }

    /** Body of {@code RenderGlobal.drawBlockDamageTexture}; {@code damagedBlocks} is RenderGlobal's live map (entries beyond 32 blocks are removed). */
    public static void draw(Map damagedBlocks, WorldClient world, TextureManager engine, RenderBlocks renderBlocks, Icon[] destroyIcons,
                            Tessellator tess, EntityPlayer player, float partialTicks) {
        double px = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double py = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
        double pz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        if (!damagedBlocks.isEmpty()) {
            Draw.blendFunc(BlendFactor.DST_COLOR, BlendFactor.SRC_COLOR);
            engine.bindTexture(TextureMap.locationBlocksTexture);
            Draw.color(1.0F, 1.0F, 1.0F, 0.5F);
            Draw.pushPose();
            Draw.alphaTest(false);
            Draw.polygonOffset(-3.0F, -3.0F);
            Draw.polygonOffsetFill(true);
            Draw.alphaTest(true);
            tess.startDrawingQuads();
            tess.setTranslation(-px, -py, -pz);
            Draw.scale(1.0F, 0.9999F, 1.0F);
            tess.disableColor();
            Iterator it = damagedBlocks.values().iterator();
            while (it.hasNext()) {
                DestroyBlockProgress p = (DestroyBlockProgress) it.next();
                double dx = p.getPartialBlockX() - px;
                double dy = p.getPartialBlockY() - py;
                double dz = p.getPartialBlockZ() - pz;
                if (dx * dx + dy * dy + dz * dz > 1024.0) {
                    it.remove();
                } else {
                    int id = world.getBlockId(p.getPartialBlockX(), p.getPartialBlockY(), p.getPartialBlockZ());
                    Block block = id > 0 ? Block.blocksList[id] : null;
                    if (block == null) {
                        block = Block.stone;
                    }
                    renderBlocks.renderBlockUsingTexture(block, p.getPartialBlockX(), p.getPartialBlockY(), p.getPartialBlockZ(),
                            destroyIcons[p.getPartialBlockDamage()]);
                }
            }
            tess.draw();
            tess.setTranslation(0.0, 0.0, 0.0);
            Draw.alphaTest(false);
            Draw.polygonOffset(0.0F, 0.0F);
            Draw.polygonOffsetFill(false);
            Draw.alphaTest(true);
            Draw.depthMask(true);
            Draw.popPose();
        }
    }
}

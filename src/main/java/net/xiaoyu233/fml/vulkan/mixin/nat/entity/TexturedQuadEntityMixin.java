package net.xiaoyu233.fml.vulkan.mixin.nat.entity;

import net.minecraft.PositionTextureVertex;
import net.minecraft.TexturedQuad;
import net.minecraft.Tessellator;
import net.minecraft.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Native area "entity": TexturedQuad.draw kept arithmetically identical (goes through the NativeTess-backed tessellator). */
@Mixin(TexturedQuad.class)
public abstract class TexturedQuadEntityMixin {
    @Shadow public PositionTextureVertex[] vertexPositions;
    @Shadow private boolean invertNormal;

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void draw(Tessellator par1Tessellator, float par2) {
        Vec3 var3 = this.vertexPositions[1].vector3D.subtract(this.vertexPositions[0].vector3D);
        Vec3 var4 = this.vertexPositions[1].vector3D.subtract(this.vertexPositions[2].vector3D);
        Vec3 var5 = var4.crossProduct(var3).normalize();
        par1Tessellator.startDrawingQuads();
        if (this.invertNormal) {
            par1Tessellator.setNormal(-((float) var5.xCoord), -((float) var5.yCoord), -((float) var5.zCoord));
        } else {
            par1Tessellator.setNormal((float) var5.xCoord, (float) var5.yCoord, (float) var5.zCoord);
        }
        for (int var6 = 0; var6 < 4; var6++) {
            PositionTextureVertex var7 = this.vertexPositions[var6];
            par1Tessellator.addVertexWithUV(
                    (float) var7.vector3D.xCoord * par2,
                    (float) var7.vector3D.yCoord * par2,
                    (float) var7.vector3D.zCoord * par2,
                    var7.texturePositionX,
                    var7.texturePositionY);
        }
        par1Tessellator.draw();
    }
}

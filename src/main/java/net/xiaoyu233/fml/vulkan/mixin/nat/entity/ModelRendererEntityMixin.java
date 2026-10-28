package net.xiaoyu233.fml.vulkan.mixin.nat.entity;

import java.util.List;
import net.minecraft.ModelRenderer;
import net.xiaoyu233.fml.vulkan.render.model.BakedPart;
import net.xiaoyu233.fml.vulkan.render.model.ModelRendererAccess;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native area "entity": ModelRenderer emits a cached BakedPart through the native pose stack (no display lists). */
@Mixin(ModelRenderer.class)
public abstract class ModelRendererEntityMixin implements ModelRendererAccess {
    private static final float DEG = 180.0F / (float) Math.PI;

    @Shadow public float rotationPointX;
    @Shadow public float rotationPointY;
    @Shadow public float rotationPointZ;
    @Shadow public float rotateAngleX;
    @Shadow public float rotateAngleY;
    @Shadow public float rotateAngleZ;
    @Shadow public float offsetX;
    @Shadow public float offsetY;
    @Shadow public float offsetZ;
    @Shadow public boolean showModel;
    @Shadow public boolean isHidden;
    @Shadow public List childModels;
    @Shadow private boolean compiled;

    @Unique
    private BakedPart fml$baked;

    @Override
    public BakedPart fml$baked() {
        return fml$baked;
    }

    @Override
    public void fml$setBaked(BakedPart part) {
        this.fml$baked = part;
    }

    @Inject(method = "addBox(Ljava/lang/String;FFFIII)Lnet/minecraft/ModelRenderer;", at = @At("TAIL"))
    private void fml$addBox1(String n, float x, float y, float z, int a, int b, int c, CallbackInfoReturnable<ModelRenderer> cir) {
        this.compiled = false;
    }

    @Inject(method = "addBox(FFFIII)Lnet/minecraft/ModelRenderer;", at = @At("TAIL"))
    private void fml$addBox2(float x, float y, float z, int a, int b, int c, CallbackInfoReturnable<ModelRenderer> cir) {
        this.compiled = false;
    }

    @Inject(method = "addBox(FFFIIIF)V", at = @At("TAIL"))
    private void fml$addBox3(float x, float y, float z, int a, int b, int c, float s, CallbackInfo ci) {
        this.compiled = false;
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void render(float var1) {
        if (!this.isHidden && this.showModel) {
            if (!this.compiled || this.fml$stale()) {
                this.compileDisplayList(var1);
            }
            Draw.translate(this.offsetX, this.offsetY, this.offsetZ);
            if (this.rotateAngleX != 0.0F || this.rotateAngleY != 0.0F || this.rotateAngleZ != 0.0F) {
                Draw.pushPose();
                Draw.translate(this.rotationPointX * var1, this.rotationPointY * var1, this.rotationPointZ * var1);
                if (this.rotateAngleZ != 0.0F) {
                    Draw.rotate(this.rotateAngleZ * DEG, 0.0F, 0.0F, 1.0F);
                }
                if (this.rotateAngleY != 0.0F) {
                    Draw.rotate(this.rotateAngleY * DEG, 0.0F, 1.0F, 0.0F);
                }
                if (this.rotateAngleX != 0.0F) {
                    Draw.rotate(this.rotateAngleX * DEG, 1.0F, 0.0F, 0.0F);
                }
                this.fml$baked.emit();
                this.fml$renderChildren(var1);
                Draw.popPose();
            } else if (this.rotationPointX == 0.0F && this.rotationPointY == 0.0F && this.rotationPointZ == 0.0F) {
                this.fml$baked.emit();
                this.fml$renderChildren(var1);
            } else {
                Draw.translate(this.rotationPointX * var1, this.rotationPointY * var1, this.rotationPointZ * var1);
                this.fml$baked.emit();
                this.fml$renderChildren(var1);
                Draw.translate(-this.rotationPointX * var1, -this.rotationPointY * var1, -this.rotationPointZ * var1);
            }
            Draw.translate(-this.offsetX, -this.offsetY, -this.offsetZ);
        }
    }

    @Unique
    private void fml$renderChildren(float scale) {
        if (this.childModels != null) {
            for (int i = 0; i < this.childModels.size(); i++) {
                ((ModelRenderer) this.childModels.get(i)).render(scale);
            }
        }
    }

    /**
     * MITE compiles the display list once and reuses it for any later scale; only a change of the cube list (addBox
     * clears {@code compiled}) requires a re-bake. The scale is therefore deliberately not part of the check.
     */
    @Unique
    private boolean fml$stale() {
        return this.fml$baked == null || this.fml$baked.cubeCount != ((ModelRenderer) (Object) this).cubeList.size();
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void renderWithRotation(float var1) {
        if (!this.isHidden && this.showModel) {
            if (!this.compiled || this.fml$stale()) {
                this.compileDisplayList(var1);
            }
            Draw.pushPose();
            Draw.translate(this.rotationPointX * var1, this.rotationPointY * var1, this.rotationPointZ * var1);
            if (this.rotateAngleY != 0.0F) {
                Draw.rotate(this.rotateAngleY * DEG, 0.0F, 1.0F, 0.0F);
            }
            if (this.rotateAngleX != 0.0F) {
                Draw.rotate(this.rotateAngleX * DEG, 1.0F, 0.0F, 0.0F);
            }
            if (this.rotateAngleZ != 0.0F) {
                Draw.rotate(this.rotateAngleZ * DEG, 0.0F, 0.0F, 1.0F);
            }
            this.fml$baked.emit();
            Draw.popPose();
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    public void postRender(float var1) {
        if (!this.isHidden && this.showModel) {
            if (!this.compiled || this.fml$stale()) {
                this.compileDisplayList(var1);
            }
            if (this.rotateAngleX != 0.0F || this.rotateAngleY != 0.0F || this.rotateAngleZ != 0.0F) {
                Draw.translate(this.rotationPointX * var1, this.rotationPointY * var1, this.rotationPointZ * var1);
                if (this.rotateAngleZ != 0.0F) {
                    Draw.rotate(this.rotateAngleZ * DEG, 0.0F, 0.0F, 1.0F);
                }
                if (this.rotateAngleY != 0.0F) {
                    Draw.rotate(this.rotateAngleY * DEG, 0.0F, 1.0F, 0.0F);
                }
                if (this.rotateAngleX != 0.0F) {
                    Draw.rotate(this.rotateAngleX * DEG, 1.0F, 0.0F, 0.0F);
                }
            } else if (this.rotationPointX != 0.0F || this.rotationPointY != 0.0F || this.rotationPointZ != 0.0F) {
                Draw.translate(this.rotationPointX * var1, this.rotationPointY * var1, this.rotationPointZ * var1);
            }
        }
    }

    /**
     * @author fml-native
     * @reason native renderer
     */
    @Overwrite
    private void compileDisplayList(float var1) {
        this.fml$baked = BakedPart.bake((ModelRenderer) (Object) this, var1);
        this.compiled = true;
    }
}

package net.xiaoyu233.fml.vulkan.mixin.nat.terrain;

import net.minecraft.GLAllocation;
import net.minecraft.OpenGlCapsChecker;
import net.minecraft.RenderGlobal;
import net.xiaoyu233.fml.vulkan.render.buffer.StaticMeshes;
import net.xiaoyu233.fml.vulkan.render.sky.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "terrain": RenderGlobal constructor without GL display lists.
 *
 * <p>Final redirect ordinals (all counted inside {@code <init>(Minecraft)} only):
 * <pre>
 *  GLAllocation.generateDisplayLists  ordinal 0 -> 0 (section list base, no ids reserved)
 *  GLAllocation.generateDisplayLists  ordinal 1 -> StaticMeshes.reserveSkyIds() (star/sky/sky2 base)
 *  OpenGlCapsChecker.checkARBOcclusion ordinal 0 -> false (occlusion branch dead, glGenQueriesARB unreachable)
 *  GL11.glPushMatrix                  ordinal 0 -> no-op
 *  GL11.glPopMatrix                   ordinal 0 -> no-op
 *  GL11.glNewList(II)V                ordinal 0 -> SkyRenderer.beginCapture(0) (stars; renderStars() runs inside)
 *  GL11.glNewList(II)V                ordinal 1 -> SkyRenderer.beginCapture(1) (sky)
 *  GL11.glNewList(II)V                ordinal 2 -> SkyRenderer.beginCapture(2) (sky2)
 *  GL11.glEndList()V                  ordinal 0 -> SkyRenderer.endCapture(0)
 *  GL11.glEndList()V                  ordinal 1 -> SkyRenderer.endCapture(1)
 *  GL11.glEndList()V                  ordinal 2 -> SkyRenderer.endCapture(2)
 * </pre>
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalInitTerrainMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/GLAllocation;generateDisplayLists(I)I", ordinal = 0))
    private int fml$sectionListBase(int count) {
        return 0;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/GLAllocation;generateDisplayLists(I)I", ordinal = 1))
    private int fml$skyListBase(int count) {
        return StaticMeshes.reserveSkyIds();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/OpenGlCapsChecker;checkARBOcclusion()Z", ordinal = 0))
    private boolean fml$noOcclusion() {
        return false;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V", ordinal = 0))
    private void fml$push0() {
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V", ordinal = 0))
    private void fml$pop0() {
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glNewList(II)V", ordinal = 0))
    private void fml$new0(int list, int mode) {
        SkyRenderer.beginCapture(0);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glNewList(II)V", ordinal = 1))
    private void fml$new1(int list, int mode) {
        SkyRenderer.beginCapture(1);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glNewList(II)V", ordinal = 2))
    private void fml$new2(int list, int mode) {
        SkyRenderer.beginCapture(2);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEndList()V", ordinal = 0))
    private void fml$end0() {
        SkyRenderer.endCapture(0);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEndList()V", ordinal = 1))
    private void fml$end1() {
        SkyRenderer.endCapture(1);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEndList()V", ordinal = 2))
    private void fml$end2() {
        SkyRenderer.endCapture(2);
    }
}

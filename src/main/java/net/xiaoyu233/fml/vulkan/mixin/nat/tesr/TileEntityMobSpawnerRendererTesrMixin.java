package net.xiaoyu233.fml.vulkan.mixin.nat.tesr;

import net.minecraft.TileEntityMobSpawnerRenderer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native area "tesr": redirect-only mixin (template A). The original bodies are kept,
 * including the spinning mini-entity render in func_98144_a (RenderManager.renderEntityWithPosYaw),
 * which is untouched; each matrix call is sent to Draw.
 *
 * renderTileEntityMobSpawner is an instance method and func_98144_a is static, so the
 * handlers for the two methods are separate (instance and static) to match Mixin staticness.
 */
@Mixin(TileEntityMobSpawnerRenderer.class)
public abstract class TileEntityMobSpawnerRendererTesrMixin {

    // ---- renderTileEntityMobSpawner(TileEntityMobSpawner, DDDF)V (instance) ----

    @Redirect(method = "renderTileEntityMobSpawner(Lnet/minecraft/TileEntityMobSpawner;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$pushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "renderTileEntityMobSpawner(Lnet/minecraft/TileEntityMobSpawner;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$translate(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = "renderTileEntityMobSpawner(Lnet/minecraft/TileEntityMobSpawner;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$popMatrix() {
        Draw.popPose();
    }

    // ---- func_98144_a(MobSpawnerBaseLogic, DDDF)V (static) ----

    @Redirect(method = "func_98144_a(Lnet/minecraft/MobSpawnerBaseLogic;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private static void fml$translateStatic(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = "func_98144_a(Lnet/minecraft/MobSpawnerBaseLogic;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private static void fml$rotateStatic(float angle, float x, float y, float z) {
        Draw.rotate(angle, x, y, z);
    }

    @Redirect(method = "func_98144_a(Lnet/minecraft/MobSpawnerBaseLogic;DDDF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private static void fml$scaleStatic(float x, float y, float z) {
        Draw.scale(x, y, z);
    }
}

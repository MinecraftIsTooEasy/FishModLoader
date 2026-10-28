package net.xiaoyu233.fml.vulkan.mixin.nat.item;

import net.minecraft.ItemRenderer;
import net.xiaoyu233.fml.vulkan.render.item.HandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NR-14 (3.9, template E): every GL11 statement of the held-item, first-person hand and overlay methods is redirected in place to
 * the native state ({@link HandRenderer}). MITE arithmetic is untouched, so the MapItemRenderer.renderMap INVOKE, the Item.map
 * GETSTATIC and the method signatures that Twilight Forest hooks stay present. Glint passes use the uv matrix through
 * GlShim.matrixMode(5890), equivalent to ItemEffects.beginGlintPass.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererItemMixin {
    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private static void fml$nr14_glPushMatrix() { HandRenderer.push(); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private static void fml$nr14_glPopMatrix() { HandRenderer.pop(); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private static void fml$nr14_glTranslatef(float x, float y, float z) { HandRenderer.translate(x, y, z); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private static void fml$nr14_glRotatef(float a, float x, float y, float z) { HandRenderer.rotate(a, x, y, z); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private static void fml$nr14_glScalef(float x, float y, float z) { HandRenderer.scale(x, y, z); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private static void fml$nr14_glColor4f(float r, float g, float b, float a) { HandRenderer.color(r, g, b, a); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glNormal3f(FFF)V"))
    private static void fml$nr14_glNormal3f(float x, float y, float z) { HandRenderer.normal(x, y, z); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private static void fml$nr14_glEnable(int c) { HandRenderer.enable(c); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private static void fml$nr14_glDisable(int c) { HandRenderer.disable(c); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBlendFunc(II)V"))
    private static void fml$nr14_glBlendFunc(int s, int d) { HandRenderer.blendFunc(s, d); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthFunc(I)V"))
    private static void fml$nr14_glDepthFunc(int f) { HandRenderer.depthFunc(f); }

    @Redirect(method = {"renderItem","renderItemInFirstPerson","renderOverlays","renderInsideOfBlock","renderWarpedTextureOverlay","renderFireInFirstPerson"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glMatrixMode(I)V"))
    private static void fml$nr14_glMatrixMode(int m) { HandRenderer.matrixMode(m); }
}

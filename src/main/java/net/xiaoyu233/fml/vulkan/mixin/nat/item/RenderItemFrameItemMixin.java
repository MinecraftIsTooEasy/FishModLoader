package net.xiaoyu233.fml.vulkan.mixin.nat.item;

import net.minecraft.RenderItemFrame;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NR-14 (3.9, template E): every GL11 statement of the item-frame methods ({@code func_82404_a}, {@code renderFrameItemAsBlock},
 * {@code func_82402_b}) is redirected in place to {@link GlShim}. The MITE body is kept unchanged, so the ItemRenderer / RenderItem /
 * MapItemRenderer calls (owned by other subtasks) and the gameplay logic stay as they are. The six distinct GL11 descriptors used
 * by the class (glPushMatrix, glPopMatrix, glTranslatef, glRotatef, glScalef, glNormal3f) each have one redirect covering every
 * occurrence in the listed methods.
 */
@Mixin(RenderItemFrame.class)
public abstract class RenderItemFrameItemMixin {
    @Redirect(method = {"func_82404_a", "renderFrameItemAsBlock", "func_82402_b"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void fml$nr14_glPushMatrix() { GlShim.pushMatrix(); }

    @Redirect(method = {"func_82404_a", "renderFrameItemAsBlock", "func_82402_b"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void fml$nr14_glPopMatrix() { GlShim.popMatrix(); }

    @Redirect(method = {"func_82404_a", "renderFrameItemAsBlock", "func_82402_b"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void fml$nr14_glTranslatef(float x, float y, float z) { GlShim.translatef(x, y, z); }

    @Redirect(method = {"func_82404_a", "renderFrameItemAsBlock", "func_82402_b"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void fml$nr14_glRotatef(float a, float x, float y, float z) { GlShim.rotatef(a, x, y, z); }

    @Redirect(method = {"func_82404_a", "renderFrameItemAsBlock", "func_82402_b"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void fml$nr14_glScalef(float x, float y, float z) { GlShim.scalef(x, y, z); }

    @Redirect(method = {"func_82404_a", "renderFrameItemAsBlock", "func_82402_b"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glNormal3f(FFF)V"))
    private void fml$nr14_glNormal3f(float x, float y, float z) { GlShim.normal3f(x, y, z); }
}

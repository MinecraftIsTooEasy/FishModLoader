package net.xiaoyu233.fml.vulkan.mixin.nat.screen;

import net.minecraft.GuiMainMenu;
import net.minecraft.GuiScreen;
import net.minecraft.Minecraft;
import net.minecraft.ResourceLocation;
import net.xiaoyu233.fml.vulkan.render.screen.Panorama;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Native GuiMainMenu (design 3.11): the panorama is rendered by {@link Panorama}; the GL calls left in
 * drawScreen / drawBulletHole are redirected in place. The Minecraft.getVersionDescriptor call that FML core
 * MainMenuMixin injects before is never targeted.
 */
@Mixin(GuiMainMenu.class)
public abstract class GuiMainMenuScreenMixin extends GuiScreen {

    @Shadow private int panoramaTimer;
    @Shadow @Final private static ResourceLocation[] titlePanoramaPaths;
    @Shadow private ResourceLocation field_110351_G;

    /** @author NR-18 @reason native panorama */
    @Overwrite
    private void renderSkybox(int mouseX, int mouseY, float partial) {
        Minecraft mc = this.mc;
        Panorama.render((GuiMainMenu) (Object) this, partial, this.panoramaTimer, titlePanoramaPaths,
                mc.getTextureManager().getTexture(this.field_110351_G).getGlTextureId(), this.width, this.height, this.zLevel);
    }

    /** @author NR-18 @reason replaced by Panorama (dead stub) */
    @Overwrite
    private void drawPanorama(int mouseX, int mouseY, float partial) {
    }

    /** @author NR-18 @reason replaced by Panorama (dead stub) */
    @Overwrite
    private void rotateAndBlurSkybox(float partial) {
    }

    @Redirect(method = {"drawScreen(IIF)V", "drawBulletHole(IIFF)V"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private void nat$color(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = {"drawScreen(IIF)V", "drawBulletHole(IIFF)V"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private void nat$push() {
        Draw.pushPose();
    }

    @Redirect(method = {"drawScreen(IIF)V", "drawBulletHole(IIFF)V"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private void nat$pop() {
        Draw.popPose();
    }

    @Redirect(method = {"drawScreen(IIF)V", "drawBulletHole(IIFF)V"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private void nat$translate(float x, float y, float z) {
        Draw.translate(x, y, z);
    }

    @Redirect(method = {"drawScreen(IIF)V", "drawBulletHole(IIFF)V"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private void nat$rotate(float a, float x, float y, float z) {
        Draw.rotate(a, x, y, z);
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private void nat$scale(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "drawScreen(IIF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private void nat$enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "drawBulletHole(IIFF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexParameteri(III)V"))
    private void nat$texParameteri(int target, int pname, int param) {
        GlShim.texParameteri(target, pname, param);
    }

    @Redirect(method = "drawBulletHole(IIFF)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glAlphaFunc(IF)V"))
    private void nat$alphaFunc(int func, float ref) {
        GlShim.alphaFunc(func, ref);
    }
}

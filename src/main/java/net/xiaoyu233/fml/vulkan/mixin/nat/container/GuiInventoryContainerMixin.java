package net.xiaoyu233.fml.vulkan.mixin.nat.container;

import net.minecraft.GuiInventory;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native area "container": GL11 calls redirected in place (templates D/E); bodies and other mixin hooks stay. */
@Mixin(GuiInventory.class)
public abstract class GuiInventoryContainerMixin {
    @Redirect(method = "drawGuiContainerBackgroundLayer(FII)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glColor4f(FFFF)V"))
    private static void fml$a_Color4f(float r, float g, float b, float a) {
        Draw.color(r, g, b, a);
    }

    @Redirect(method = "func_110423_a(IIIFFLnet/minecraft/EntityLivingBase;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"))
    private static void fml$Model_Enable(int cap) {
        GlShim.enable(cap);
    }

    @Redirect(method = "func_110423_a(IIIFFLnet/minecraft/EntityLivingBase;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"))
    private static void fml$Model_Disable(int cap) {
        GlShim.disable(cap);
    }

    @Redirect(method = "func_110423_a(IIIFFLnet/minecraft/EntityLivingBase;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPushMatrix()V"))
    private static void fml$Model_PushMatrix() {
        Draw.pushPose();
    }

    @Redirect(method = "func_110423_a(IIIFFLnet/minecraft/EntityLivingBase;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPopMatrix()V"))
    private static void fml$Model_PopMatrix() {
        Draw.popPose();
    }

    @Redirect(method = "func_110423_a(IIIFFLnet/minecraft/EntityLivingBase;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V"))
    private static void fml$Model_Rotatef(float a, float x, float y, float z) {
        Draw.rotate(a, x, y, z);
    }

    @Redirect(method = "func_110423_a(IIIFFLnet/minecraft/EntityLivingBase;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glScalef(FFF)V"))
    private static void fml$Model_Scalef(float x, float y, float z) {
        Draw.scale(x, y, z);
    }

    @Redirect(method = "func_110423_a(IIIFFLnet/minecraft/EntityLivingBase;)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTranslatef(FFF)V"))
    private static void fml$Model_Translatef(float x, float y, float z) {
        Draw.translate(x, y, z);
    }
}

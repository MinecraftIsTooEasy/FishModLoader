package net.xiaoyu233.fml.vulkan.render.screen;

import net.minecraft.Gui;
import net.minecraft.LoadingScreenRenderer;
import net.minecraft.Minecraft;
import net.minecraft.ScaledResolution;
import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.render.state.Phase;
import net.xiaoyu233.fml.vulkan.window.NativeWindow;

/**
 * Standalone loading frames (3.11). Contract: {@link #drawProgress} is the body of MITE {@code setLoadingProgress} after the 100 ms
 * throttle check: {@code Frame.clearColorDepth}, ortho 100..300 + translate z -200, dirt background quad (optionsBackground, colour 0x404040, 32 px tiles),
 * progress bar (texturing off), the two strings via FontRenderer, then {@code NativeWindow.update()}; phase GUI.
 * {@link #beginFrame} is the body of {@code resetProgressAndWorkingMessage}'s sibling {@code func_73722_d}/displayProgressMessage ortho setup.
 */
public final class LoadingFrames {
    private LoadingFrames() {
    }

    private static final java.lang.invoke.VarHandle RUNNING = handle();

    private static java.lang.invoke.VarHandle handle() {
        try {
            return java.lang.invoke.MethodHandles.privateLookupIn(Minecraft.class, java.lang.invoke.MethodHandles.lookup())
                .findVarHandle(Minecraft.class, "running", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Package-private field {@code Minecraft.running}. */
    public static boolean running(Minecraft mc) {
        return (boolean) RUNNING.getVolatile(mc);
    }

    /** Ortho setup of {@code displayProgressMessage}: clear depth, projection ortho(0,w,h,0,100,300), pose = translate(0,0,-200). */
    public static void beginFrame(double scaledW, double scaledH) {
        Frame.clearDepth();
        Draw.projection().loadIdentity();
        Draw.projection().ortho(0.0, scaledW, scaledH, 0.0, 100.0, 300.0);
        Draw.pose().loadIdentity();
        Draw.pose().translate(0.0F, 0.0F, -200.0F);
    }

    /** One loading frame; {@code percent < 0} hides the bar. */
    public static void drawProgress(LoadingScreenRenderer r, Minecraft mc, String title, String working, int percent) {
        ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        int w = sr.getScaledWidth();
        int h = sr.getScaledHeight();
        Frame.beginPhase(Phase.GUI);
        Frame.clearDepth();
        Draw.projection().loadIdentity();
        Draw.projection().ortho(0.0, sr.getScaledWidth_double(), sr.getScaledHeight_double(), 0.0, 100.0, 300.0);
        Draw.pose().loadIdentity();
        Draw.pose().translate(0.0F, 0.0F, -200.0F);
        Frame.clearColorDepth();
        Tessellator t = Tessellator.instance;
        mc.getTextureManager().bindTexture(Gui.optionsBackground);
        float tile = 32.0F;
        t.startDrawingQuads();
        t.setColorOpaque_I(4210752);
        t.addVertexWithUV(0.0, h, 0.0, 0.0, h / tile);
        t.addVertexWithUV(w, h, 0.0, w / tile, h / tile);
        t.addVertexWithUV(w, 0.0, 0.0, w / tile, 0.0);
        t.addVertexWithUV(0.0, 0.0, 0.0, 0.0, 0.0);
        t.draw();
        if (percent >= 0) {
            int bw = 100;
            int bh = 2;
            int x = w / 2 - bw / 2;
            int y = h / 2 + 16;
            Draw.texturing(false);
            t.startDrawingQuads();
            t.setColorOpaque_I(8421504);
            t.addVertex(x, y, 0.0);
            t.addVertex(x, y + bh, 0.0);
            t.addVertex(x + bw, y + bh, 0.0);
            t.addVertex(x + bw, y, 0.0);
            t.setColorOpaque_I(8454016);
            t.addVertex(x, y, 0.0);
            t.addVertex(x, y + bh, 0.0);
            t.addVertex(x + percent, y + bh, 0.0);
            t.addVertex(x + percent, y, 0.0);
            t.draw();
            Draw.texturing(true);
        }
        mc.fontRenderer.drawStringWithShadow(title, (w - mc.fontRenderer.getStringWidth(title)) / 2, h / 2 - 4 - 16, 16777215);
        mc.fontRenderer.drawStringWithShadow(working, (w - mc.fontRenderer.getStringWidth(working)) / 2, h / 2 - 4 + 8, 16777215);
        NativeWindow.update();
        try {
            Thread.yield();
        } catch (Exception ignored) {
        }
    }
}

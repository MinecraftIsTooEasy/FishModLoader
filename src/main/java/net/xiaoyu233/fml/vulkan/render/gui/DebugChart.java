package net.xiaoyu233.fml.vulkan.render.gui;

import java.text.DecimalFormat;
import java.util.List;
import net.minecraft.Minecraft;
import net.minecraft.FontRenderer;
import net.minecraft.MathHelper;
import net.minecraft.ProfilerResult;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Frame;
import net.xiaoyu233.fml.vulkan.render.tess.NativeTess;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;

/** Profiler pie chart of Minecraft.displayDebugInfo (3.11): fans become triangles, outlines become LINES. NR-17 owns the body. */
public final class DebugChart {
    private static final int GL_TRIANGLE_STRIP = 5, GL_TRIANGLE_FAN = 6;
    /** Render-thread only: DecimalFormat is not thread-safe, so one shared instance is kept. */
    private static final DecimalFormat PERCENT = new DecimalFormat("##0.00");

    private DebugChart() {
    }

    /** Implemented by MinecraftGuiMixin to expose the private {@code debugProfilerName}. */
    public interface ProfilerNameAccess {
        String fml$debugProfilerName();
    }

    /**
     * Native replacement body of {@code Minecraft.displayDebugInfo(long)}; called from MinecraftGuiMixin. The tessellator turns the
     * fan (mode 6) into triangles (0, k+1, k+2) and the strip (mode 5) into triangles in the same order and colours as GL.
     */
    public static void render(Minecraft mc, long frameNanos) {
        if (!mc.mcProfiler.profilingEnabled) {
            return;
        }
        String name = ((ProfilerNameAccess) (Object) mc).fml$debugProfilerName();
        List<ProfilerResult> var3 = mc.mcProfiler.getProfilingData(name);
        ProfilerResult var4 = var3.remove(0);
        Frame.clearDepth();
        Draw.projection().loadIdentity();
        Draw.projection().ortho(0.0, mc.displayWidth, mc.displayHeight, 0.0, 1000.0, 3000.0);
        Draw.pose().loadIdentity();
        Draw.pose().translate(0.0F, 0.0F, -2000.0F);
        Draw.colorMaterialEnabled(true);
        Draw.lineWidth(1.0F);
        Draw.texturing(false);
        NativeTess t = TessBridge.current();
        short var6 = 160;
        int var7 = mc.displayWidth - var6 - 10;
        int var8 = mc.displayHeight - var6 * 2;
        Draw.blend(true);
        t.startDrawingQuads();
        t.setColorRGBA_I(0, 200);
        t.addVertex(var7 - var6 * 1.1F, var8 - var6 * 0.6F - 16.0F, 0.0);
        t.addVertex(var7 - var6 * 1.1F, var8 + var6 * 2, 0.0);
        t.addVertex(var7 + var6 * 1.1F, var8 + var6 * 2, 0.0);
        t.addVertex(var7 + var6 * 1.1F, var8 - var6 * 0.6F - 16.0F, 0.0);
        t.draw();
        Draw.blend(false);
        double var9 = 0.0;

        for (int var11 = 0; var11 < var3.size(); var11++) {
            ProfilerResult var12 = var3.get(var11);
            int var13 = MathHelper.floor_double(var12.field_76332_a / 4.0) + 1;
            t.startDrawing(GL_TRIANGLE_FAN);
            t.setColorOpaque_I(var12.func_76329_a());
            t.addVertex(var7, var8, 0.0);
            for (int var14 = var13; var14 >= 0; var14--) {
                float var15 = (float) ((var9 + var12.field_76332_a * var14 / var13) * Math.PI * 2.0 / 100.0);
                float var16 = MathHelper.sin(var15) * var6;
                float var17 = MathHelper.cos(var15) * var6 * 0.5F;
                t.addVertex(var7 + var16, var8 - var17, 0.0);
            }
            t.draw();
            t.startDrawing(GL_TRIANGLE_STRIP);
            t.setColorOpaque_I((var12.func_76329_a() & 16711422) >> 1);
            for (int var23 = var13; var23 >= 0; var23--) {
                float var15 = (float) ((var9 + var12.field_76332_a * var23 / var13) * Math.PI * 2.0 / 100.0);
                float var16 = MathHelper.sin(var15) * var6;
                float var17 = MathHelper.cos(var15) * var6 * 0.5F;
                t.addVertex(var7 + var16, var8 - var17, 0.0);
                t.addVertex(var7 + var16, var8 - var17 + 10.0F, 0.0);
            }
            t.draw();
            var9 += var12.field_76332_a;
        }

        Draw.texturing(true);
        FontRenderer font = mc.fontRenderer;
        String var18 = "";
        if (!var4.field_76331_c.equals("unspecified")) {
            var18 = var18 + "[0] ";
        }
        if (var4.field_76331_c.length() == 0) {
            var18 = var18 + "ROOT ";
        } else {
            var18 = var18 + var4.field_76331_c + " ";
        }
        int white = 16777215;
        font.drawStringWithShadow(var18, var7 - var6, var8 - var6 / 2 - 16, white);
        font.drawStringWithShadow(var18 = PERCENT.format(var4.field_76330_b) + "%", var7 + var6 - font.getStringWidth(var18), var8 - var6 / 2 - 16, white);

        for (int var21 = 0; var21 < var3.size(); var21++) {
            ProfilerResult var20 = var3.get(var21);
            String var22 = "";
            if (var20.field_76331_c.equals("unspecified")) {
                var22 = var22 + "[?] ";
            } else {
                var22 = var22 + "[" + (var21 + 1) + "] ";
            }
            var22 = var22 + var20.field_76331_c;
            int y = var8 + var6 / 2 + var21 * 8 + 20;
            font.drawStringWithShadow(var22, var7 - var6, y, var20.func_76329_a());
            font.drawStringWithShadow(var22 = PERCENT.format(var20.field_76332_a) + "%", var7 + var6 - 50 - font.getStringWidth(var22), y, var20.func_76329_a());
            font.drawStringWithShadow(var22 = PERCENT.format(var20.field_76330_b) + "%", var7 + var6 - font.getStringWidth(var22), y, var20.func_76329_a());
        }
    }
}

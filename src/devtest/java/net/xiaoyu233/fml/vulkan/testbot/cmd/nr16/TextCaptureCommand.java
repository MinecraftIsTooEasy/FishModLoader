package net.xiaoyu233.fml.vulkan.testbot.cmd.nr16;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import net.minecraft.FontRenderer;
import net.minecraft.Minecraft;
import net.xiaoyu233.fml.vulkan.render.state.AttribMask;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.testing.DrawCapture;
import net.xiaoyu233.fml.vulkan.render.testing.RecordedDraw;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code textCapture}: drives the real {@code FontRenderer} on the render thread (TestBot hand-over) with {@code Batches.testCapture}
 * (through {@link DrawCapture}) and checks: consecutive glyphs of one (page, state) are merged (no two adjacent draws with an identical
 * RenderType and kind), the shadow pass uses colour {@code (c & 0xFCFCFC) >> 2} offset +1, a bold glyph is drawn twice with +1 offset,
 * and obfuscation ({@code §k}) is reproducible for a seeded {@code fontRandom}.
 */
final class TextCaptureCommand implements DevCommand {
    private static final String STYLED = "Hello §lBold §nUnder §mStrike §oItalic";
    private static final String UNICODE = "你好世界 Ünïçødé Жд あい";

    @Override
    public String name() {
        return "textCapture";
    }

    @Override
    public String description() {
        return "FontRenderer under draw capture: merging per (page,state), shadow colour/offset, bold +1, seeded obfuscation";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        onRenderThread(() -> {
            check(s);
            return null;
        });
    }

    private static <T> T onRenderThread(Callable<T> c) throws Exception {
        TestBot bot = TestBot.get();
        return bot != null ? bot.afterFrame(c) : c.call();
    }

    private static void check(Report.Section s) {
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc == null ? null : mc.fontRenderer;
        if (font == null) {
            s.warn("textCapture", "no FontRenderer available");
            return;
        }
        boolean unicode = font.getUnicodeFlag();
        Draw.pushAttrib(AttribMask.decode(0xFFFFF));
        try {
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            font.setUnicodeFlag(false);
            styled(s, font);
            shadow(s, font);
            bold(s, font);
            obfuscation(s, font);
            unicode(s, font);
        } catch (Throwable t) {
            s.exception("textCapture", t);
        } finally {
            font.setUnicodeFlag(unicode);
            Draw.popAttrib();
        }
    }

    private static List<RecordedDraw> capture(Runnable r) {
        try (DrawCapture c = DrawCapture.open()) {
            r.run();
            return new ArrayList<>(c.draws());
        }
    }

    /** Number of adjacent draw pairs that could have been one draw (same render type, both textured-or-not). */
    private static int unmergedNeighbours(List<RecordedDraw> draws) {
        int n = 0;
        for (int i = 1; i < draws.size(); i++) {
            if (draws.get(i).type().equals(draws.get(i - 1).type())) {
                n++;
            }
        }
        return n;
    }

    private static int distinctTextures(List<RecordedDraw> draws) {
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        for (RecordedDraw d : draws) {
            set.add(d.type().texture());
        }
        return set.size();
    }

    private static void styled(Report.Section s, FontRenderer font) {
        List<RecordedDraw> draws = capture(() -> font.drawString(STYLED, 10, 10, 0xFFFFFFFF));
        s.check(!draws.isEmpty(), "textCapture styled string records draws", "no draws captured");
        // draw count == number of (page,state) changes: runs of equal render type; adjacent equal types mean a missed merge
        int merges = unmergedNeighbours(draws);
        s.check(merges == 0, "textCapture draw count == (page,state) changes", draws.size() + " draws, " + merges
                + " adjacent draws share a RenderType (glyphs not merged)");
        int quads = 0;
        for (RecordedDraw d : draws) {
            quads += d.quadCount();
        }
        s.info("textCapture styled", draws.size() + " draws, " + quads + " quads, " + distinctTextures(draws) + " distinct textures");
        // underline + strikethrough are untextured quads of the current colour: at least two draws whose type texture differs from glyphs
        s.check(distinctTextures(draws) >= 2, "textCapture underline/strikethrough use an untextured type",
                "only " + distinctTextures(draws) + " distinct texture(s)");
    }

    private static int shadowColor(int c) {
        return (c & 0xFCFCFC) >> 2 | c & 0xFF000000;
    }

    private static int argbToAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >>> 16) & 0xFF);
    }

    private static void shadow(Report.Section s, FontRenderer font) {
        int color = 0xFF80C0E0;
        List<RecordedDraw> draws = capture(() -> font.drawStringWithShadow("H", 10, 10, color));
        int shadowAbgr = argbToAbgr(shadowColor(color));
        int mainAbgr = argbToAbgr(color);
        RecordedDraw shadow = null, main = null;
        for (RecordedDraw d : draws) {
            if (d.vertexCount() == 0) {
                continue;
            }
            if (shadow == null && d.abgr(0) == shadowAbgr) {
                shadow = d;
            } else if (main == null && d.abgr(0) == mainAbgr) {
                main = d;
            }
        }
        s.check(shadow != null, "textCapture shadow pass colour (c&0xFCFCFC)>>2", "expected abgr " + Integer.toHexString(shadowAbgr)
                + " not found in " + draws.size() + " draws");
        s.check(main != null, "textCapture main pass keeps the colour", "expected abgr " + Integer.toHexString(mainAbgr) + " not found");
        if (shadow != null && main != null) {
            float dx = minX(shadow) - minX(main), dy = minY(shadow) - minY(main);
            s.check(Math.abs(dx - 1f) < 1e-4f && Math.abs(dy - 1f) < 1e-4f, "textCapture shadow offset +1/+1", "dx=" + dx + " dy=" + dy);
        }
    }

    private static void bold(Report.Section s, FontRenderer font) {
        List<RecordedDraw> draws = capture(() -> font.drawString("§lH", 10, 10, 0xFFFFFFFF));
        List<float[]> quadMinX = new ArrayList<>();
        for (RecordedDraw d : draws) {
            for (int q = 0; q < d.quadCount(); q++) {
                float mn = Float.MAX_VALUE, my = Float.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    mn = Math.min(mn, d.x(q * 4 + k));
                    my = Math.min(my, d.y(q * 4 + k));
                }
                quadMinX.add(new float[] {mn, my});
            }
        }
        s.check(quadMinX.size() == 2, "textCapture bold glyph is two quads", quadMinX.size() + " quads");
        if (quadMinX.size() >= 2) {
            float dx = quadMinX.get(1)[0] - quadMinX.get(0)[0];
            s.check(Math.abs(dx - 1f) < 1e-4f && quadMinX.get(1)[1] == quadMinX.get(0)[1], "textCapture bold second glyph offset +1",
                    "dx=" + dx);
        }
    }

    private static void obfuscation(Report.Section s, FontRenderer font) {
        String text = "§kabcdefghijkl";
        font.fontRandom.setSeed(1234L);
        List<RecordedDraw> a = capture(() -> font.drawString(text, 10, 10, 0xFFFFFFFF));
        font.fontRandom.setSeed(1234L);
        List<RecordedDraw> b = capture(() -> font.drawString(text, 10, 10, 0xFFFFFFFF));
        s.check(!a.isEmpty() && sameGeometry(a, b), "textCapture obfuscation reproducible with a seeded fontRandom",
                "two runs with seed 1234 differ (" + a.size() + " vs " + b.size() + " draws)");
        font.fontRandom.setSeed(99L);
        List<RecordedDraw> c = capture(() -> font.drawString(text, 10, 10, 0xFFFFFFFF));
        if (sameGeometry(a, c)) {
            s.warn("textCapture obfuscation seed", "a different seed produced identical glyphs (all replacement chars equal?)");
        } else {
            s.ok("textCapture obfuscation depends on fontRandom");
        }
    }

    private static void unicode(Report.Section s, FontRenderer font) {
        font.setUnicodeFlag(true);
        List<RecordedDraw> draws = capture(() -> font.drawString(UNICODE, 10, 10, 0xFFFFFFFF));
        font.setUnicodeFlag(false);
        s.check(!draws.isEmpty(), "textCapture unicode string records draws", "no draws");
        int merges = unmergedNeighbours(draws);
        s.check(merges == 0, "textCapture unicode draw count == page changes", draws.size() + " draws, " + merges + " unmerged neighbours");
        s.info("textCapture unicode", draws.size() + " draws over " + distinctTextures(draws) + " texture(s)");
        s.check(distinctTextures(draws) >= 2, "textCapture unicode string spans several pages", "textures " + distinctTextures(draws));
    }

    private static boolean sameGeometry(List<RecordedDraw> a, List<RecordedDraw> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            RecordedDraw x = a.get(i), y = b.get(i);
            if (x.vertexCount() != y.vertexCount() || !x.type().equals(y.type())) {
                return false;
            }
            for (int v = 0; v < x.vertexCount(); v++) {
                if (x.x(v) != y.x(v) || x.y(v) != y.y(v) || x.u(v) != y.u(v) || x.v(v) != y.v(v)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static float minX(RecordedDraw d) {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < d.vertexCount(); i++) {
            m = Math.min(m, d.x(i));
        }
        return m;
    }

    private static float minY(RecordedDraw d) {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < d.vertexCount(); i++) {
            m = Math.min(m, d.y(i));
        }
        return m;
    }
}

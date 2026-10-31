package net.xiaoyu233.fml.vulkan.testbot.cmd.nr27;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import net.xiaoyu233.fml.vulkan.testbot.CaptureCompare;
import net.xiaoyu233.fml.vulkan.testbot.CaptureRunner;
import net.xiaoyu233.fml.vulkan.testbot.ImageStats;
import net.xiaoyu233.fml.vulkan.testbot.ImageStats.Comparison;
import net.xiaoyu233.fml.vulkan.testbot.ImageStats.Tolerance;
import net.xiaoyu233.fml.vulkan.testbot.NativeChecks;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommandProvider;

/** Devtest commands of work package NR-27: tooling self tests (comparison maths, scene lists) and the counters report. */
public final class Commands implements DevCommandProvider {
    @Override
    public String packageId() {
        return "NR-27";
    }

    @Override
    public List<DevCommand> commands() {
        return List.of(new Cmd("compareSelfTest", "SSIM / histogram / tolerance classes on synthetic images (known answers)", Commands::compareSelfTest),
            new Cmd("sceneList", "parses every native-captures/NR-*.txt and lists the scenes", Commands::sceneList),
            new Cmd("nativeCounters", "prints the validation / GLAudit / GlShim / mixin audit counters", Commands::nativeCounters),
            new Cmd("compareCaptures", "args: <dirA> <dirB> [outDir] [T0|T1|T2]: compares two capture directories scene by scene", Commands::compareCaptures));
    }

    private record Cmd(String name, String description, Body body) implements DevCommand {
        @Override
        public void run(Report.Section section, String[] args) throws Exception {
            body.run(section, args);
        }
    }

    private interface Body {
        void run(Report.Section s, String[] args) throws Exception;
    }

    private static BufferedImage gradient(int w, int h, int bias) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255 / (w - 1) + bias) & 255;
                int g = (y * 255 / (h - 1)) & 255;
                int b = ((x ^ y) * 3) & 255;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    static void compareSelfTest(Report.Section s, String[] args) {
        BufferedImage a = gradient(128, 96, 0);
        BufferedImage same = gradient(128, 96, 0);
        Comparison c0 = ImageStats.compare(a, same, Tolerance.T0, null);
        s.check(c0.pass() && c0.maxDiff() == 0 && c0.ssim() == 1.0, "identical images: T0 pass, SSIM 1", c0.summary());

        BufferedImage one = gradient(128, 96, 0);
        one.setRGB(10, 10, one.getRGB(10, 10) ^ 0x000001);
        Comparison c1 = ImageStats.compare(a, one, Tolerance.T0, null);
        s.check(!c1.pass() && c1.maxDiff() == 1, "one pixel off by 1: T0 fails with max diff 1", c1.summary());
        Comparison c1b = ImageStats.compare(a, one, Tolerance.T1, null);
        s.check(c1b.pass(), "one pixel off by 1: T1 passes", c1b.summary());

        BufferedImage shifted = gradient(128, 96, 0);
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 128; x++) {
                int p = shifted.getRGB(x, y);
                int r = Math.min(255, ((p >> 16) & 255) + 3);
                shifted.setRGB(x, y, (p & 0xFF00FFFF) | (r << 16));
            }
        }
        Comparison c3 = ImageStats.compare(a, shifted, Tolerance.T1, null);
        s.check(c3.maxDiff() <= 3 && c3.fractionWithin3() >= 0.999 && c3.pass(), "red +3 everywhere: within 3 on 100 % and SSIM >= 0.999, T1 passes", c3.summary());

        BufferedImage far = gradient(128, 96, 0);
        for (int y = 40; y < 56; y++) {
            for (int x = 40; x < 72; x++) {
                far.setRGB(x, y, 0xFFFFFF);
            }
        }
        Comparison cf = ImageStats.compare(a, far, Tolerance.T1, null);
        s.check(!cf.pass() && cf.maxDiff() > 3, "white patch: T1 fails", cf.summary());
        BufferedImage mask = new BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 36; y < 60; y++) {
            for (int x = 36; x < 76; x++) {
                mask.setRGB(x, y, 0xFFFFFF);
            }
        }
        Comparison cm = ImageStats.compare(a, far, Tolerance.T2, mask);
        s.check(cm.pass() && cm.maskedPixels() == 24L * 40L, "white patch inside the T2 mask: passes, masked pixels counted (" + cm.maskedPixels() + ")", cm.summary());

        long[] hist = ImageStats.maxChannelDiffHistogram(a, far, null);
        long total = 0;
        for (long h : hist) {
            total += h;
        }
        s.check(total == 128L * 96L, "histogram covers every pixel", "sum " + total);

        BufferedImage small = gradient(64, 48, 0);
        Comparison cs = ImageStats.compare(a, small, Tolerance.T1, null);
        s.check(!cs.sameSize() && !cs.pass(), "size mismatch fails", cs.summary());

        BufferedImage diff = ImageStats.diffImage(a, far, mask);
        s.check(diff.getWidth() == 128 && (diff.getRGB(50, 45) & 0xFFFFFF) == 0x000040 && (diff.getRGB(2, 2) & 0xFFFFFF) == 0, "diff image: masked dark blue, equal black", "unexpected pixels");
    }

    static void sceneList(Report.Section s, String[] args) {
        List<CaptureRunner.Scene> scenes = CaptureRunner.scenes();
        s.check(!scenes.isEmpty(), "scene lists parsed (" + scenes.size() + " scenes)", "no native-captures/NR-*.txt scene found");
        StringBuilder sb = new StringBuilder("| package | scene | class | kind | options |\n|---|---|---|---|---|\n");
        for (CaptureRunner.Scene sc : scenes) {
            sb.append("| ").append(sc.packageId()).append(" | ").append(sc.id()).append(" | ").append(sc.tolerance()).append(" | ").append(sc.kind()).append(" | ").append(sc.options()).append(" |\n");
        }
        s.markdown(sb.toString());
    }

    static void nativeCounters(Report.Section s, String[] args) {
        s.info("counters", NativeChecks.snapshot().text());
        List<String> missing = NativeChecks.missingMixins();
        s.info("mixin audit", missing == null ? "plugin not available" : missing.isEmpty() ? "complete" : missing.size() + " not applied: " + missing);
        s.info("gates apply", Boolean.toString(NativeChecks.gatesApply()));
    }

    static void compareCaptures(Report.Section s, String[] args) throws Exception {
        if (args.length < 2) {
            s.fail("compareCaptures", "usage: compareCaptures <dirA> <dirB> [outDir] [T0|T1|T2]");
            return;
        }
        Path out = new File(args.length > 2 ? args[2] : args[1] + "-compare").toPath();
        List<CaptureCompare.SceneResult> results = CaptureCompare.compareDirs(new File(args[0]).toPath(), new File(args[1]).toPath(), out, args.length > 3 ? Tolerance.parse(args[3]) : null);
        for (CaptureCompare.SceneResult r : results) {
            s.check(r.pass(), "scene " + r.id() + " (" + r.tolerance() + ")", r.comparison() != null ? r.comparison().summary() : r.note());
        }
        s.info("report", out.resolve("compare.md").toString());
    }
}

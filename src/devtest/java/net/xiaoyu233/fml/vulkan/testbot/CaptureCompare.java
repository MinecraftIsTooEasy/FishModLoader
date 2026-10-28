package net.xiaoyu233.fml.vulkan.testbot;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.xiaoyu233.fml.vulkan.testbot.ImageStats.Comparison;
import net.xiaoyu233.fml.vulkan.testbot.ImageStats.Tolerance;

/**
 * Compares two capture directories scene by scene under the tolerance class of each scene (7.2): {@code scenes.tsv} of directory A names the scenes and their class, a
 * scene present only on one side is a failure, a size mismatch is a failure. Writes a markdown report and, for every scene that is not identical, a diff image next to it.
 * <p>
 * Command line (also {@code sh ./gradlew compareCaptures -PcapA=<dir or tag> -PcapB=<dir or tag> [-PcapOut=<tag>]}): {@code CaptureCompare <dirA> <dirB> <outDir> [forceClass]}
 * where {@code forceClass} (T0, T1 or T2) overrides every scene's class (the determinism proof of two consecutive runs uses T0). Exit status 0 = every scene passed.
 */
public final class CaptureCompare {
    /** Per scene result. */
    public record SceneResult(String id, String packageId, Tolerance tolerance, Comparison comparison, String note) {
        public boolean pass() {
            return comparison != null && comparison.pass();
        }
    }

    private CaptureCompare() {
    }

    /** The optional T2 mask image of a scene: {@code <captureDir>/masks/<id>.png}, written by the scene's command; any non-black pixel is masked. */
    public static Path maskFile(Path captureDir, String sceneId) {
        return captureDir.resolve("masks").resolve(sceneId + ".png");
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length < 3) {
            System.err.println("usage: CaptureCompare <dirA> <dirB> <outDir> [T0|T1|T2]");
            System.exit(2);
        }
        Tolerance force = args.length > 3 ? Tolerance.parse(args[3]) : null;
        List<SceneResult> results = compareDirs(new File(args[0]).toPath(), new File(args[1]).toPath(), new File(args[2]).toPath(), force);
        long failed = results.stream().filter(r -> !r.pass()).count();
        System.out.println("CaptureCompare: " + results.size() + " scene(s), " + failed + " failed; report " + new File(args[2], "compare.md"));
        System.exit(failed == 0 && !results.isEmpty() ? 0 : 1);
    }

    /** Compares and writes {@code <out>/compare.md} (+ {@code <out>/diff/<id>.png}). */
    public static List<SceneResult> compareDirs(Path a, Path b, Path out, Tolerance force) throws IOException {
        Files.createDirectories(out);
        // union of both manifests (A wins for a scene listed on both sides): a scene captured on one side only is a failure, not ignored
        List<String[]> scenes = new ArrayList<>(readManifest(a));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] sc : scenes) {
            seen.add(sc[0]);
        }
        for (String[] sc : readManifest(b)) {
            if (seen.add(sc[0])) {
                scenes.add(sc);
            }
        }
        List<SceneResult> results = new ArrayList<>();
        for (String[] sc : scenes) {
            String id = sc[0];
            Tolerance tol = force != null ? force : Tolerance.parse(sc[1]);
            String pkg = sc[2];
            Path fa = a.resolve(sc[3]);
            Path fb = b.resolve(sc[3]);
            if (!Files.isRegularFile(fa) || !Files.isRegularFile(fb)) {
                results.add(new SceneResult(id, pkg, tol, null, "missing capture on " + (Files.isRegularFile(fa) ? "B" : Files.isRegularFile(fb) ? "A" : "both sides")));
                continue;
            }
            BufferedImage ia = ImageIO.read(fa.toFile());
            BufferedImage ib = ImageIO.read(fb.toFile());
            if (ia == null || ib == null) {
                results.add(new SceneResult(id, pkg, tol, null, "unreadable image"));
                continue;
            }
            BufferedImage mask = null;
            Path mf = maskFile(a, id);
            if (tol == Tolerance.T2 && Files.isRegularFile(mf)) {
                mask = ImageIO.read(mf.toFile());
            }
            Comparison c = ImageStats.compare(ia, ib, tol, mask);
            String note = "";
            if (c.sameSize() && !(tol == Tolerance.T0 && c.pass())) {
                Path diff = out.resolve("diff").resolve(id + ".png");
                Files.createDirectories(diff.getParent());
                ImageIO.write(ImageStats.diffImage(ia, ib, mask), "png", diff.toFile());
                note = "diff/" + id + ".png";
            }
            results.add(new SceneResult(id, pkg, tol, c, note));
        }
        Files.writeString(out.resolve("compare.md"), render(a, b, results), StandardCharsets.UTF_8);
        return results;
    }

    private static List<String[]> readManifest(Path dir) throws IOException {
        List<String[]> out = new ArrayList<>();
        Path f = dir.resolve("scenes.tsv");
        if (!Files.isRegularFile(f)) {
            return out;
        }
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length >= 4) {
                out.add(p);
            }
        }
        return out;
    }

    private static String render(Path a, Path b, List<SceneResult> results) {
        StringBuilder sb = new StringBuilder("# Capture comparison\n\n- A: `" + a + "`\n- B: `" + b + "`\n");
        long failed = results.stream().filter(r -> !r.pass()).count();
        sb.append("- Scenes: ").append(results.size()).append(", failed: ").append(failed).append("\n\n");
        sb.append("| scene | package | class | result | max diff | within 3 | SSIM | masked px | note |\n|---|---|---|---|---|---|---|---|---|\n");
        for (SceneResult r : results) {
            Comparison c = r.comparison();
            if (c == null || !c.sameSize()) {
                sb.append("| ").append(r.id()).append(" | ").append(r.packageId()).append(" | ").append(r.tolerance()).append(" | **FAIL** | - | - | - | - | ")
                    .append(c == null ? r.note() : c.verdict()).append(" |\n");
                continue;
            }
            sb.append(String.format(java.util.Locale.ROOT, "| %s | %s | %s | **%s** | %d | %.4f%% | %.5f | %d | %s |%n", r.id(), r.packageId(), r.tolerance(), r.pass() ? "PASS" : "FAIL",
                c.maxDiff(), c.fractionWithin3() * 100.0, c.ssim(), c.maskedPixels(), r.note()));
        }
        sb.append("\nHistograms of the per-pixel max channel difference (bucket:pixels, non-empty buckets only):\n\n");
        for (SceneResult r : results) {
            if (r.comparison() != null && r.comparison().sameSize()) {
                sb.append("- ").append(r.id()).append(": ").append(r.comparison().histogramText()).append("\n");
            }
        }
        return sb.toString();
    }
}

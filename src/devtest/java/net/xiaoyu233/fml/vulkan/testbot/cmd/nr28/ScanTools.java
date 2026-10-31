package net.xiaoyu233.fml.vulkan.testbot.cmd.nr28;

import net.xiaoyu233.fml.vulkan.testbot.Report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the glSiteScan JSON (JDK only).
 * Zero-site gate (default): fails when GL/GLU sites remain after transformation.
 * Self-test (args[1] given, native.off=mobs or selftest config): fails unless the report lists exactly the expected classes with the expected counts.
 */
final class ScanTools {
    private static final Pattern CLASS = Pattern.compile("\\{\\s*\"class\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"count\"\\s*:\\s*(\\d+)");
    private static final Pattern TOTAL = Pattern.compile("\"totalSites\"\\s*:\\s*(\\d+)");
    private static final Pattern MODE = Pattern.compile("\"mode\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CLASSES_SCANNED = Pattern.compile("\"classesScanned\"\\s*:\\s*(\\d+)");
    private static final String EXPECTED_MODE = "transformed";

    private ScanTools() {
    }

    /**
     * args[0] = json (default ../docs/native/gl-sites-transformed.json);
     * args[1] = optional expected-class list file for the self-test. One line per class: internal name (dots or slashes),
     * optionally followed by the exact expected site count (for example "net.minecraft.client.model.ModelRenderSkeleton 2");
     * without a count the class must simply have at least one site.
     */
    static void gate(Report.Section s, String[] args) throws Exception {
        Path p = Paths.get(args.length > 0 && !args[0].isEmpty() ? args[0] : "../docs/native/gl-sites-transformed.json");
        if (!Files.isRegularFile(p)) {
            s.fail("scanGate", "file not found: " + p);
            return;
        }
        String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        Matcher totalM = TOTAL.matcher(json);
        if (!totalM.find()) {
            s.fail("scanGate", "not a GlSiteScan report (no totalSites): " + p);
            return;
        }
        Map<String, Integer> perClass = new TreeMap<>();
        Matcher m = CLASS.matcher(json);
        long sum = 0;
        while (m.find()) {
            int c = Integer.parseInt(m.group(2));
            perClass.merge(m.group(1), c, Integer::sum);
            sum += c;
        }
        long total = Math.max(Long.parseLong(totalM.group(1)), sum);
        Matcher modeM = MODE.matcher(json);
        String mode = modeM.find() ? modeM.group(1) : "";
        Matcher scannedM = CLASSES_SCANNED.matcher(json);
        long classesScanned = scannedM.find() ? Long.parseLong(scannedM.group(1)) : 0;

        StringBuilder sb = new StringBuilder("mode ").append(mode).append(", classes scanned ").append(classesScanned)
                .append(", total GL/GLU sites ").append(total).append(" in ").append(perClass.size()).append(" classes");
        perClass.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(15)
                .forEach(e -> sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue()));
        s.info("scanGate", sb.toString());

        // A report that scanned nothing (empty or stale class directory) or was not produced in transformed mode proves nothing.
        s.check(EXPECTED_MODE.equals(mode), "scanGate", "report mode is '" + mode + "', expected '" + EXPECTED_MODE + "'");
        s.check(classesScanned > 0, "scanGate", "no net/minecraft classes were scanned (empty or stale class directory)");

        if (args.length > 1 && !args[1].isEmpty()) {
            selfTest(s, perClass, total, Paths.get(args[1]));
        } else {
            s.check(total == 0, "scanGate", "GL/GLU sites remaining: " + total);
        }
    }

    /** Self-test: the report must contain exactly the expected classes, each with the expected (or at least one) site. */
    private static void selfTest(Report.Section s, Map<String, Integer> perClass, long total, Path expectedFile) throws Exception {
        Map<String, Integer> expected = new TreeMap<>();
        for (String l : Files.readAllLines(expectedFile, StandardCharsets.UTF_8)) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\\s+");
            String name = parts[0].replace('.', '/');
            int want = -1;
            if (parts.length > 1) {
                try {
                    want = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    s.fail("scanSelfTest", "bad expected count in " + expectedFile + ": " + t);
                    return;
                }
                if (want < 1) {
                    s.fail("scanSelfTest", "expected count must be at least 1: " + t);
                    return;
                }
            }
            if (expected.put(name, want) != null) {
                s.fail("scanSelfTest", "duplicate expected class in " + expectedFile + ": " + name);
                return;
            }
        }
        if (expected.isEmpty()) {
            s.fail("scanSelfTest", "expected class list is empty: " + expectedFile);
            return;
        }
        if (total == 0) {
            s.fail("scanSelfTest", "self-test report has 0 sites (the scan is broken or the mixin was not applied)");
            return;
        }
        List<String> unexpected = new ArrayList<>();
        for (String c : perClass.keySet()) {
            if (!expected.containsKey(c)) {
                unexpected.add(c);
            }
        }
        List<String> missing = new ArrayList<>();
        List<String> wrongCount = new ArrayList<>();
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            Integer have = perClass.get(e.getKey());
            if (have == null || have < 1) {
                missing.add(e.getKey());
            } else if (e.getValue() > 0 && have != e.getValue()) {
                wrongCount.add(e.getKey() + " has " + have + ", expected " + e.getValue());
            }
        }
        s.check(unexpected.isEmpty() && missing.isEmpty() && wrongCount.isEmpty(), "scanSelfTest",
                "unexpected classes " + unexpected + ", expected but absent " + missing + ", wrong counts " + wrongCount);
    }
}

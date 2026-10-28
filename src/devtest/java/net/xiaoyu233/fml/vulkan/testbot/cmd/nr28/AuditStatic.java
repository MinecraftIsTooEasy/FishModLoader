package net.xiaoyu233.fml.vulkan.testbot.cmd.nr28;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.xiaoyu233.fml.vulkan.testbot.Report;

/**
 * Static (no game launch) ownership audit of NR-28: (a) native mixin configs vs the mixin source tree, (b) Appendix A of ARCHITECTURE-NATIVE.md vs
 * inventory-native.json vs the mixin targets. Run from the repository root (or give the root and the docs directory as arguments).
 */
final class AuditStatic {
    private static final Pattern CONFIG_LIST = Pattern.compile("\"(mixins|client|server)\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern PACKAGE = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MIXIN_ANN = Pattern.compile("@Mixin\\s*\\((.*?)\\)\\s*(?:public|abstract|final|class|@)", Pattern.DOTALL);
    private static final Pattern CLASS_REF = Pattern.compile("([A-Za-z0-9_$.]+)\\.class");
    private static final Pattern STRING_TARGET = Pattern.compile("\"([A-Za-z0-9_$./]+)\"");
    private static final Pattern INV_NAME = Pattern.compile("^\\s{4}\"name\"\\s*:\\s*\"([^\"]+)\"", Pattern.MULTILINE);

    private AuditStatic() {
    }

    static void staticAudit(Report.Section s, String[] args) throws Exception {
        Path root = findRoot(args.length > 0 ? args[0] : null);
        if (root == null) {
            s.fail("staticAudit", "repository root (src/main/resources) not found from " + Paths.get("").toAbsolutePath());
            return;
        }
        Path docs = findDocs(root, args.length > 1 ? args[1] : null);
        s.info("root", root.toString());
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        configAudit(s, root, targets);
        if (docs == null) {
            s.warn("inventory cross-check", "docs directory (ARCHITECTURE-NATIVE.md, inventory-native.json) not found; pass it as the second argument");
            return;
        }
        inventoryAudit(s, docs, targets);
    }

    private static Path findRoot(String arg) {
        List<Path> cands = new ArrayList<>();
        if (arg != null) {
            cands.add(Paths.get(arg));
        }
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            cands.add(p);
        }
        for (Path c : cands) {
            if (Files.isDirectory(c.resolve("src/main/resources")) && Files.isDirectory(c.resolve("src/main/java"))) {
                return c;
            }
        }
        return null;
    }

    private static Path findDocs(Path root, String arg) {
        List<Path> cands = new ArrayList<>();
        if (arg != null) {
            cands.add(Paths.get(arg));
        }
        Path p = root.toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            cands.add(p.resolve("docs"));
        }
        for (Path c : cands) {
            if (Files.isRegularFile(c.resolve("ARCHITECTURE-NATIVE.md")) && Files.isRegularFile(c.resolve("inventory-native.json"))) {
                return c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- (a) configs vs source tree

    private static void configAudit(Report.Section s, Path root, Map<String, Set<String>> targets) throws IOException {
        Path resDir = root.resolve("src/main/resources");
        Path srcBase = root.resolve("src/main/java/net/xiaoyu233/fml/vulkan/mixin/nat");
        List<Path> configs = new ArrayList<>();
        try (Stream<Path> st = Files.list(resDir)) {
            st.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith("fishmodloader.native.") && n.endsWith(".mixins.json");
            }).sorted().forEach(configs::add);
        }
        s.check(!configs.isEmpty(), configs.size() + " native mixin configs found", "no fishmodloader.native.*.mixins.json in " + resDir);
        Map<String, List<String>> listedIn = new HashMap<>();
        int listed = 0;
        for (Path cfg : configs) {
            String text = Files.readString(cfg, StandardCharsets.UTF_8);
            Matcher pm = PACKAGE.matcher(text);
            String pkg = pm.find() ? pm.group(1) : "";
            String area = cfg.getFileName().toString().replace("fishmodloader.native.", "").replace(".mixins.json", "");
            Path dir = srcBase.resolve(area);
            String expectPkg = "net.xiaoyu233.fml.vulkan.mixin.nat." + area;
            if (!pkg.equals(expectPkg)) {
                s.fail("config " + area, "package '" + pkg + "' is not '" + expectPkg + "'");
            }
            Set<String> inConfig = new LinkedHashSet<>();
            Matcher lm = CONFIG_LIST.matcher(text);
            while (lm.find()) {
                Matcher q = QUOTED.matcher(lm.group(2));
                while (q.find()) {
                    if (!inConfig.add(q.group(1))) {
                        s.fail("config " + area, "mixin '" + q.group(1) + "' listed twice");
                    }
                }
            }
            int missing = 0;
            for (String m : inConfig) {
                listed++;
                listedIn.computeIfAbsent(area + "/" + m, k -> new ArrayList<>()).add(cfg.getFileName().toString());
                Path src = dir.resolve(m.replace('.', '/') + ".java");
                if (!Files.isRegularFile(src)) {
                    missing++;
                    s.fail("config " + area, "listed mixin '" + m + "' has no source file " + root.relativize(src));
                } else {
                    collectTargets(src, targets, area + "/" + m);
                }
            }
            Set<String> onDisk = new TreeSet<>();
            if (Files.isDirectory(dir)) {
                try (Stream<Path> st = Files.walk(dir)) {
                    st.filter(f -> f.toString().endsWith(".java")).forEach(f -> {
                        String rel = dir.relativize(f).toString().replace('/', '.');
                        onDisk.add(rel.substring(0, rel.length() - 5));
                    });
                }
            }
            int unlisted = 0;
            for (String f : onDisk) {
                if (!inConfig.contains(f)) {
                    unlisted++;
                    s.fail("config " + area, "source file '" + f + "' is not listed in " + cfg.getFileName());
                }
            }
            if (missing == 0 && unlisted == 0) {
                s.ok("config " + area + ": " + inConfig.size() + " mixins, tree matches");
            }
        }
        // every file under the tree belongs to a config of its own area dir
        if (Files.isDirectory(srcBase)) {
            try (Stream<Path> st = Files.list(srcBase)) {
                for (Path d : (Iterable<Path>) st.filter(Files::isDirectory).sorted()::iterator) {
                    String area = d.getFileName().toString();
                    if (!Files.isRegularFile(resDir.resolve("fishmodloader.native." + area + ".mixins.json"))) {
                        s.fail("area " + area, "directory has no fishmodloader.native." + area + ".mixins.json");
                    }
                }
            }
        }
        for (Map.Entry<String, List<String>> e : listedIn.entrySet()) {
            if (e.getValue().size() != 1) {
                s.fail("mixin " + e.getKey(), "listed in " + e.getValue().size() + " configs");
            }
        }
        s.info("mixins listed", Integer.toString(listed));
    }

    private static void collectTargets(Path src, Map<String, Set<String>> targets, String id) throws IOException {
        String text = Files.readString(src, StandardCharsets.UTF_8);
        Matcher m = MIXIN_ANN.matcher(text);
        Set<String> names = new LinkedHashSet<>();
        while (m.find()) {
            String body = m.group(1);
            Matcher c = CLASS_REF.matcher(body);
            while (c.find()) {
                names.add(simple(c.group(1)));
            }
            Matcher t = STRING_TARGET.matcher(body);
            while (t.find()) {
                names.add(simple(t.group(1)));
            }
        }
        targets.put(id, names);
    }

    private static String simple(String n) {
        int i = Math.max(n.lastIndexOf('.'), n.lastIndexOf('/'));
        return i >= 0 ? n.substring(i + 1) : n;
    }

    // ---------------------------------------------------------------- (b) Appendix A vs inventory vs mixins

    private static void inventoryAudit(Report.Section s, Path docs, Map<String, Set<String>> targets) throws IOException {
        List<String> md = Files.readAllLines(docs.resolve("ARCHITECTURE-NATIVE.md"), StandardCharsets.UTF_8);
        Map<String, String[]> appendix = new LinkedHashMap<>(); // class -> {owner, form}
        boolean in = false;
        for (String line : md) {
            if (line.startsWith("## Appendix A")) {
                in = true;
                continue;
            }
            if (in && line.startsWith("## ")) {
                break;
            }
            if (!in || !line.startsWith("|")) {
                continue;
            }
            String[] c = line.split("\\|", -1);
            if (c.length < 9 || !c[1].trim().matches("\\d+")) {
                continue;
            }
            String cls = c[2].trim().replace("`", "");
            if (appendix.put(cls, new String[] {c[7].trim(), c[8].trim()}) != null) {
                s.fail("Appendix A", "class '" + cls + "' listed twice");
            }
        }
        String json = Files.readString(docs.resolve("inventory-native.json"), StandardCharsets.UTF_8);
        Set<String> inventory = new LinkedHashSet<>();
        Matcher im = INV_NAME.matcher(json);
        while (im.find()) {
            inventory.add(simple(im.group(1)));
        }
        s.info("Appendix A rows", Integer.toString(appendix.size()));
        s.info("inventory-native.json classes", Integer.toString(inventory.size()));
        s.check(appendix.size() == 173, "Appendix A has 173 entries", "found " + appendix.size());
        s.check(inventory.size() == 173, "inventory-native.json has 173 entries", "found " + inventory.size());
        for (String c : inventory) {
            if (!appendix.containsKey(c)) {
                s.fail("inventory vs Appendix A", "'" + c + "' is in inventory-native.json but not in Appendix A");
            }
        }
        for (String c : appendix.keySet()) {
            if (!inventory.contains(c)) {
                s.fail("inventory vs Appendix A", "'" + c + "' is in Appendix A but not in inventory-native.json");
            }
        }
        Map<String, List<String>> mixinOf = new HashMap<>();
        targets.forEach((id, names) -> names.forEach(n -> mixinOf.computeIfAbsent(n, k -> new ArrayList<>()).add(id)));
        int mixed = 0;
        int verifyOnly = 0;
        List<String> unowned = new ArrayList<>();
        for (Map.Entry<String, String[]> e : appendix.entrySet()) {
            String cls = e.getKey();
            boolean verify = e.getValue()[1].toLowerCase(java.util.Locale.ROOT).contains("verify");
            if (mixinOf.containsKey(cls)) {
                mixed++;
            } else if (verify) {
                verifyOnly++;
            } else {
                unowned.add(cls + " (owner " + e.getValue()[0] + ", form: " + e.getValue()[1] + ")");
            }
        }
        s.info("inventory classes with a mixin", Integer.toString(mixed));
        s.info("verify-only (documented, no mixin)", Integer.toString(verifyOnly));
        for (String u : unowned) {
            s.warn("unowned class", u);
        }
        s.check(unowned.isEmpty(), "every inventory class has a mixin or is documented verify-only", unowned.size() + " class(es) have neither (see warnings)");
        List<String> extra = new ArrayList<>();
        for (String n : mixinOf.keySet()) {
            if (!appendix.containsKey(n)) {
                extra.add(n);
            }
        }
        java.util.Collections.sort(extra);
        s.info("mixin targets outside the inventory (allowed by 5.x rules)", extra.isEmpty() ? "none" : String.join(", ", extra));
    }
}

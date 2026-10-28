import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.Opcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GL site scanner (ARCHITECTURE-NATIVE 1.16.6). Single-file Java 25 program.
 *
 * <pre>
 * java tools/GlSiteScan.java --mode=annotations [--game-jar=libs/1.6.4-MITE.jar]
 *      [--mixin-classes=build/classes/java/main] [--out-dir=&lt;dir&gt;] [expectations]
 * java tools/GlSiteScan.java --mode=transformed [--class-dir=run/.mixin.out/class] [--game-jar=libs/1.6.4-MITE.jar]
 *      [--export-after=&lt;ISO-8601 instant&gt;] [--out-dir=&lt;dir&gt;] [expectations]
 *
 * expectations (all optional, combinable; every one is checked and any mismatch exits 1):
 *   --expect-owner=&lt;internal name prefix&gt;   restrict all counts below to sites whose owner starts with the prefix
 *   --expect-sites=&lt;cls=n,cls=n,...&gt;        EXACT per-class map: the scan must report exactly these classes and counts
 *                                            (empty value = no sites at all); every listed class must have a class file
 *   --expect-class=&lt;cls&gt; [--expect-count=&lt;n&gt;]  one class (count optional; without it the class must have &gt;0 sites)
 *   --expect-classes=&lt;n&gt;                    number of classes with sites
 *   --expect-total=&lt;n&gt;                      number of sites
 * </pre>
 *
 * Transformed mode: the exported transformed classes (mixin.debug.export) are used; with --game-jar, net/minecraft classes
 * without an exported file are read from the jar (Mixin exports only the classes it transformed, so those are unchanged).
 * The reports list how many classes came from each source, and the export mtime range. --export-after fails the scan if any
 * exported file predates the given instant (stale files from an earlier run).
 *
 * Coverage in annotations mode is an upper bound on migrated sites: a {@code @Redirect}/{@code @WrapOperation} with
 * {@code ordinal} covers only the ordinal-th matching site of its method; with a {@code slice} every matching site is
 * counted as covered (reported as "approximate").
 *
 * Game jar lookup: the deobfuscated/remapped MITE jar is {@code libs/1.6.4-MITE.jar} (build.gradle:
 * {@code implementation files('libs/1.6.4-MITE.jar')}); the decompiled work/mite-src is reference only.
 * Default out dir: the nearest ancestor of the working dir that has {@code docs/native}, else {@code ../docs/native}.
 * Exit codes: 0 ok, 1 expectation mismatch or scan failure, 2 bad arguments / IO error.
 */
public class GlSiteScan {
    record Site(String cls, String method, int line, String kind, String owner, String member) {
    }

    /** A class reference of a Mixin target string: owner internal name and member ("name(desc)ret", "name:desc", "" or null). */
    record Ref(String owner, String member) {
        /** Accepts "Lowner;member" (Mixin form), "owner.name(desc)ret" (dotted) and "owner" (class only, member null). */
        static Ref parse(String t) {
            String x = t.startsWith("L") && t.indexOf(';') > 0 ? t.substring(1) : t;
            int semi = x.indexOf(';');
            int paren = x.indexOf('(');
            if (semi >= 0 && (paren < 0 || semi < paren)) {
                return new Ref(x.substring(0, semi).replace('.', '/'), x.substring(semi + 1));
            }
            if (paren >= 0) {
                String pre = x.substring(0, paren);
                int dot = pre.lastIndexOf('.');
                if (dot < 0) return new Ref(pre.replace('.', '/'), x.substring(paren));
                return new Ref(pre.substring(0, dot).replace('.', '/'), pre.substring(dot + 1) + x.substring(paren));
            }
            if (x.indexOf('/') < 0 && x.indexOf('.') > 0) {
                int dot = x.lastIndexOf('.');
                return new Ref(x.substring(0, dot).replace('.', '/'), x.substring(dot + 1));
            }
            return new Ref(x.replace('.', '/'), null);
        }
    }

    static boolean isGl(String owner) {
        return owner.startsWith("org/lwjgl/opengl/") || owner.startsWith("org/lwjgl/util/glu/");
    }

    static final class Bad extends RuntimeException {
        Bad(String m) { super(m); }
    }

    /** Outcome of one scan. */
    static final class Result {
        /** Reported sites (annotations: unmigrated; transformed: all). */
        final List<Site> sites = new ArrayList<>();
        /** Internal names of every net/minecraft class that was scanned (for expectations on class presence). */
        final Set<String> seen = new HashSet<>();
        final List<String> info = new ArrayList<>();
        /** Hard failures of the scan itself (not expectations). */
        final List<String> problems = new ArrayList<>();
        int classCount;
    }

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Bad b) {
            System.err.println("GlSiteScan: " + b.getMessage());
            System.err.println("usage: --mode=annotations --game-jar=<jar> --mixin-classes=<dir> | --mode=transformed --class-dir=<dir> [--game-jar=<jar>] [--export-after=<instant>]"
                    + " [--out-dir=<dir>] [--expect-owner=<prefix>] [--expect-sites=<cls=n,...>] [--expect-class=<cls> [--expect-count=<n>]]"
                    + " [--expect-classes=<n>] [--expect-total=<n>]");
            System.exit(2);
        }
    }

    static void run(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (String a : args) {
            if (!a.startsWith("--") || !a.contains("=")) throw new Bad("bad argument: " + a);
            int i = a.indexOf('=');
            o.put(a.substring(2, i), a.substring(i + 1));
        }
        Set<String> known = Set.of("mode", "game-jar", "mixin-classes", "class-dir", "out-dir", "export-after",
                "expect-class", "expect-count", "expect-sites", "expect-classes", "expect-total", "expect-owner");
        for (String k : o.keySet()) if (!known.contains(k)) throw new Bad("unknown option --" + k);
        String mode = o.get("mode");
        if (mode == null || !(mode.equals("annotations") || mode.equals("transformed"))) throw new Bad("--mode must be annotations or transformed");

        Integer expectCount = intOpt(o, "expect-count");
        Integer expectClasses = intOpt(o, "expect-classes");
        Integer expectTotal = intOpt(o, "expect-total");
        String expectClass = o.get("expect-class");
        if (expectClass != null) expectClass = expectClass.replace('.', '/');
        if (expectCount != null && expectClass == null) throw new Bad("--expect-count needs --expect-class (use --expect-total for all sites)");
        Map<String, Integer> expectSites = o.containsKey("expect-sites") ? parseSites(o.get("expect-sites")) : null;
        String owner = o.get("expect-owner");
        Instant exportAfter = null;
        if (o.containsKey("export-after")) {
            try {
                exportAfter = Instant.parse(o.get("export-after"));
            } catch (DateTimeParseException e) {
                throw new Bad("bad --export-after (ISO-8601 instant expected): " + o.get("export-after"));
            }
            if (!mode.equals("transformed")) throw new Bad("--export-after applies to --mode=transformed only");
        }
        Path outDir = o.containsKey("out-dir") ? Path.of(o.get("out-dir")) : defaultOut();

        Result r = mode.equals("annotations") ? scanAnnotations(o) : scanTransformed(o, exportAfter);
        if (r.classCount == 0) r.problems.add("no net/minecraft classes were scanned");

        Files.createDirectories(outDir);
        String base = mode.equals("annotations") ? "gl-sites" : "gl-sites-transformed";
        write(outDir, base, mode, r.sites, r.classCount, r.info);
        System.out.println("GlSiteScan[" + mode + "]: " + r.sites.size() + " sites in " + countByClass(r.sites).size() + " classes (" + r.classCount
                + " net/minecraft classes scanned) -> " + outDir.toAbsolutePath().resolve(base + ".md"));
        System.out.println("by owner: " + ownerCounts(r.sites));

        boolean fail = false;
        for (String p : r.problems) {
            System.err.println("SCAN FAIL: " + p);
            fail = true;
        }

        // Expectations: every one is checked against the (optionally owner-filtered) site set.
        boolean any = expectClass != null || expectSites != null || expectClasses != null || expectTotal != null;
        List<Site> view = owner == null ? r.sites : filterOwner(r.sites, owner);
        Map<String, Integer> counts = countByClass(view);
        int total = view.size();
        String scope = owner == null ? "" : " [owner " + owner + "*]";
        if (owner != null) System.out.println("owner " + owner + "*: " + total + " sites in " + counts.size() + " classes");
        List<String> failures = new ArrayList<>();
        List<String> passed = new ArrayList<>();
        if (expectClass != null) {
            if (!r.seen.contains(expectClass)) {
                failures.add("no class file for " + expectClass + " among the scanned classes (never loaded or exported?)");
            } else {
                int actual = counts.getOrDefault(expectClass, 0);
                if (expectCount == null) {
                    if (actual == 0) failures.add("no sites in " + expectClass);
                    else passed.add(expectClass + " has " + actual + " sites");
                } else if (actual != expectCount) {
                    failures.add(expectClass + " has " + actual + " sites, expected " + expectCount);
                } else {
                    passed.add(expectClass + " = " + actual);
                }
            }
        }
        if (expectSites != null) {
            for (String c : expectSites.keySet()) {
                if (!r.seen.contains(c)) failures.add("no class file for " + c + " among the scanned classes");
            }
            Set<String> names = new TreeSet<>(counts.keySet());
            names.addAll(expectSites.keySet());
            int before = failures.size();
            for (String c : names) {
                int e = expectSites.getOrDefault(c, 0);
                int a = counts.getOrDefault(c, 0);
                if (a != e) {
                    failures.add(c + " has " + a + " sites, expected " + e + (expectSites.containsKey(c) ? "" : " (class not in the expectation)"));
                }
            }
            if (failures.size() == before) passed.add("exact map of " + expectSites.size() + " classes, " + total + " sites");
        }
        if (expectClasses != null) {
            if (counts.size() != expectClasses) failures.add("classes with sites: " + counts.size() + ", expected " + expectClasses);
            else passed.add(counts.size() + " classes with sites");
        }
        if (expectTotal != null) {
            if (total != expectTotal) failures.add("total sites: " + total + ", expected " + expectTotal);
            else passed.add("total " + total);
        }
        for (String f : failures) {
            System.err.println("EXPECT FAIL" + scope + ": " + f);
            fail = true;
        }
        if (any && failures.isEmpty()) System.out.println("expect ok" + scope + ": " + String.join("; ", passed));
        if (fail) System.exit(1);
    }

    static Integer intOpt(Map<String, String> o, String key) {
        if (!o.containsKey(key)) return null;
        try {
            return Integer.parseInt(o.get(key).trim());
        } catch (NumberFormatException e) {
            throw new Bad("bad --" + key + ": " + o.get(key));
        }
    }

    /** Parses {@code cls=n,cls=n}; an empty value is the empty map (no sites expected). */
    static Map<String, Integer> parseSites(String v) {
        Map<String, Integer> m = new TreeMap<>();
        if (v.isBlank()) return m;
        for (String part : v.split(",")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) throw new Bad("bad --expect-sites entry '" + part + "' (want <internal/name>=<count>)");
            String cls = part.substring(0, eq).trim().replace('.', '/');
            int n;
            try {
                n = Integer.parseInt(part.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                throw new Bad("bad count in --expect-sites entry '" + part + "'");
            }
            if (n < 0) throw new Bad("negative count in --expect-sites entry '" + part + "'");
            if (m.put(cls, n) != null) throw new Bad("duplicate class in --expect-sites: " + cls);
        }
        return m;
    }

    static List<Site> filterOwner(List<Site> sites, String prefix) {
        List<Site> r = new ArrayList<>();
        for (Site s : sites) if (s.owner().startsWith(prefix)) r.add(s);
        return r;
    }

    static Map<String, Integer> countByClass(List<Site> sites) {
        Map<String, Integer> m = new TreeMap<>();
        for (Site s : sites) m.merge(s.cls(), 1, Integer::sum);
        return m;
    }

    static Map<String, Integer> ownerCounts(List<Site> sites) {
        Map<String, Integer> m = new TreeMap<>();
        for (Site s : sites) m.merge(s.owner(), 1, Integer::sum);
        return m;
    }

    static Result scanAnnotations(Map<String, String> o) throws Exception {
        Result r = new Result();
        Path jar = Path.of(o.getOrDefault("game-jar", "libs/1.6.4-MITE.jar"));
        Path mixDir = Path.of(o.getOrDefault("mixin-classes", "build/classes/java/main"));
        if (!Files.isRegularFile(jar)) throw new Bad("game jar not found: " + jar);
        if (!Files.isDirectory(mixDir)) throw new Bad("mixin class dir not found: " + mixDir);
        Cover cover = new Cover();
        int mixinCount = 0;
        try (Stream<Path> st = Files.walk(mixDir)) {
            for (Path p : (Iterable<Path>) st.filter(x -> x.toString().endsWith(".class"))::iterator) {
                if (cover.addMixin(Files.readAllBytes(p))) mixinCount++;
            }
        }
        List<Site> all = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().startsWith("net/minecraft/") || !e.getName().endsWith(".class")) continue;
                r.classCount++;
                r.seen.add(e.getName().substring(0, e.getName().length() - 6));
                try (InputStream in = z.getInputStream(e)) {
                    scan(in.readAllBytes(), all);
                }
            }
        }
        boolean[] cov = cover.coverage(all);
        int covered = 0;
        for (int i = 0; i < all.size(); i++) {
            if (cov[i]) covered++; else r.sites.add(all.get(i));
        }
        r.info.add("game jar: " + jar.toAbsolutePath());
        r.info.add("mixin classes: " + mixDir.toAbsolutePath() + " (" + mixinCount + " mixins)");
        r.info.add("sites total " + all.size() + ", covered by @Overwrite/@Redirect/@WrapOperation " + covered + ", unmigrated " + r.sites.size());
        if (cover.approximate > 0) {
            r.info.add("coverage is an upper bound: " + cover.approximate + " redirect(s) with ordinal and slice count every matching site as covered");
        }
        return r;
    }

    static Result scanTransformed(Map<String, String> o, Instant exportAfter) throws Exception {
        Result r = new Result();
        Path dir = Path.of(o.getOrDefault("class-dir", "run/.mixin.out/class"));
        if (!Files.isDirectory(dir)) throw new Bad("class dir not found: " + dir);
        Set<String> exported = new HashSet<>();
        int fromExport = 0;
        Instant oldest = null;
        Instant newest = null;
        List<String> stale = new ArrayList<>();
        try (Stream<Path> st = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) st.filter(x -> x.toString().endsWith(".class"))::iterator) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith("net/minecraft/")) continue;
                String internal = rel.substring(0, rel.length() - 6);
                Instant mt = Files.getLastModifiedTime(p).toInstant();
                if (oldest == null || mt.isBefore(oldest)) oldest = mt;
                if (newest == null || mt.isAfter(newest)) newest = mt;
                if (exportAfter != null && mt.isBefore(exportAfter)) stale.add(internal);
                exported.add(internal);
                r.seen.add(internal);
                r.classCount++;
                fromExport++;
                scan(Files.readAllBytes(p), r.sites);
            }
        }
        r.info.add("class dir: " + dir.toAbsolutePath() + " (" + fromExport + " exported net/minecraft classes"
                + (oldest == null ? "" : ", mtime " + oldest + " .. " + newest) + ")");
        if (exportAfter != null) {
            r.info.add("export-after " + exportAfter + ": " + stale.size() + " exported class(es) older");
            if (!stale.isEmpty()) {
                r.problems.add("stale export: " + stale.size() + " class file(s) older than " + exportAfter + ", e.g. " + stale.subList(0, Math.min(3, stale.size()))
                        + " (re-run the testbot with the export dir cleared)");
            }
        }
        int fromJar = 0;
        if (o.containsKey("game-jar")) {
            Path jar = Path.of(o.get("game-jar"));
            if (!Files.isRegularFile(jar)) throw new Bad("game jar not found: " + jar);
            try (ZipFile z = new ZipFile(jar.toFile())) {
                var en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!e.getName().startsWith("net/minecraft/") || !e.getName().endsWith(".class")) continue;
                    String internal = e.getName().substring(0, e.getName().length() - 6);
                    if (exported.contains(internal)) continue;
                    r.classCount++;
                    fromJar++;
                    r.seen.add(internal);
                    try (InputStream in = z.getInputStream(e)) {
                        scan(in.readAllBytes(), r.sites);
                    }
                }
            }
            r.info.add("classes without an exported file, read from game jar " + jar.toAbsolutePath() + ": " + fromJar
                    + " (untransformed: no applicable mixin)");
        }
        r.info.add("classes from export: " + fromExport + ", from game jar: " + fromJar);
        return r;
    }

    static Path defaultOut() {
        Path d = Path.of("").toAbsolutePath();
        for (Path p = d; p != null; p = p.getParent()) {
            Path c = p.resolve("docs/native");
            if (Files.isDirectory(c)) return c;
        }
        return Path.of("../docs/native");
    }

    static void scan(byte[] bytes, List<Site> out) {
        ClassModel cm = ClassFile.of().parse(bytes);
        String cls = cm.thisClass().asInternalName();
        if (!cls.startsWith("net/minecraft/")) return;
        for (MethodModel mm : cm.methods()) {
            var code = mm.code();
            if (code.isEmpty()) continue;
            String m = mm.methodName().stringValue() + mm.methodType().stringValue();
            int line = -1;
            for (CodeElement ce : code.get()) {
                if (ce instanceof LineNumber ln) {
                    line = ln.line();
                } else if (ce instanceof InvokeInstruction ii && ii.opcode() == Opcode.INVOKESTATIC) {
                    String ow = ii.owner().asInternalName();
                    if (isGl(ow)) out.add(new Site(cls, m, line, "INVOKESTATIC", ow, ii.name().stringValue() + ii.type().stringValue()));
                } else if (ce instanceof FieldInstruction fi && fi.opcode() == Opcode.GETSTATIC) {
                    String ow = fi.owner().asInternalName();
                    if (isGl(ow)) out.add(new Site(cls, m, line, "GETSTATIC", ow, fi.name().stringValue() + ":" + fi.type().stringValue()));
                } else if (ce instanceof NewObjectInstruction ni) {
                    String ow = ni.className().asInternalName();
                    if (isGl(ow)) out.add(new Site(cls, m, line, "NEW", ow, ""));
                }
            }
        }
    }

    /** Coverage computed from the mixin classes. */
    static final class Cover {
        /** target class -> overwritten name+desc. */
        final Map<String, Set<String>> overwrites = new HashMap<>();
        /** target class -> redirect-like injections on it. */
        final Map<String, List<Redirect>> redirects = new HashMap<>();
        /** Redirects that count every match although they carry an ordinal and a slice (upper bound). */
        int approximate;

        /** One @Redirect/@WrapOperation/@WrapWithCondition. ordinal -1 = every match; sliced = a slice element is present. */
        record Redirect(List<String> methods, List<String> targets, int ordinal, boolean sliced) {}

        /** Mutable collector for the at= annotation(s) of one injection. */
        static final class AtInfo {
            final List<String> targets = new ArrayList<>();
            int ordinal = -1;
            boolean sliced;
        }

        boolean addMixin(byte[] bytes) {
            ClassModel cm = ClassFile.of().parse(bytes);
            List<Annotation> anns = annotations(cm.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(RuntimeInvisibleAnnotationsAttribute::annotations).orElse(List.of()),
                    cm.findAttribute(Attributes.runtimeVisibleAnnotations()).map(RuntimeVisibleAnnotationsAttribute::annotations).orElse(List.of()));
            Annotation mixin = null;
            for (Annotation a : anns) if (a.className().stringValue().equals("Lorg/spongepowered/asm/mixin/Mixin;")) mixin = a;
            if (mixin == null) return false;
            List<String> targets = new ArrayList<>();
            for (AnnotationElement e : mixin.elements()) {
                String n = e.name().stringValue();
                if (n.equals("value") && e.value() instanceof AnnotationValue.OfArray arr) {
                    for (AnnotationValue v : arr.values()) if (v instanceof AnnotationValue.OfClass c) targets.add(internal(c.classSymbol().descriptorString()));
                } else if (n.equals("targets") && e.value() instanceof AnnotationValue.OfArray arr) {
                    for (AnnotationValue v : arr.values()) if (v instanceof AnnotationValue.OfString s) targets.add(s.stringValue().replace('.', '/'));
                }
            }
            if (targets.isEmpty()) return true;
            for (MethodModel mm : cm.methods()) {
                List<Annotation> ma = annotations(mm.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(RuntimeInvisibleAnnotationsAttribute::annotations).orElse(List.of()),
                        mm.findAttribute(Attributes.runtimeVisibleAnnotations()).map(RuntimeVisibleAnnotationsAttribute::annotations).orElse(List.of()));
                for (Annotation a : ma) {
                    String an = a.className().stringValue();
                    if (an.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                        String nd = mm.methodName().stringValue() + mm.methodType().stringValue();
                        for (String t : targets) overwrites.computeIfAbsent(t, k -> new HashSet<>()).add(nd);
                    } else if (an.endsWith("/Redirect;") || an.endsWith("/WrapOperation;") || an.endsWith("/WrapWithCondition;")) {
                        List<String> methods = new ArrayList<>();
                        AtInfo at = new AtInfo();
                        boolean sliced = false;
                        for (AnnotationElement e : a.elements()) {
                            String n = e.name().stringValue();
                            if (n.equals("method")) strings(e.value(), methods);
                            else if (n.equals("at")) collectAt(e.value(), at);
                            else if (n.equals("slice")) sliced = e.value() instanceof AnnotationValue.OfString s && !s.stringValue().isEmpty();
                        }
                        Redirect rd = new Redirect(methods, at.targets, at.ordinal, sliced || at.sliced);
                        for (String t : targets) redirects.computeIfAbsent(t, k -> new ArrayList<>()).add(rd);
                    }
                }
            }
            return true;
        }

        static List<Annotation> annotations(List<Annotation> a, List<Annotation> b) {
            List<Annotation> r = new ArrayList<>(a);
            r.addAll(b);
            return r;
        }

        static String internal(String desc) {
            return desc.startsWith("L") && desc.endsWith(";") ? desc.substring(1, desc.length() - 1) : desc;
        }

        static void strings(AnnotationValue v, List<String> out) {
            if (v instanceof AnnotationValue.OfString s) out.add(s.stringValue());
            else if (v instanceof AnnotationValue.OfArray arr) for (AnnotationValue x : arr.values()) strings(x, out);
        }

        /** Reads target, ordinal and slice of an @At (or an array of them). */
        static void collectAt(AnnotationValue v, AtInfo out) {
            if (v instanceof AnnotationValue.OfArray arr) {
                for (AnnotationValue x : arr.values()) collectAt(x, out);
            } else if (v instanceof AnnotationValue.OfAnnotation oa) {
                for (AnnotationElement e : oa.annotation().elements()) {
                    switch (e.name().stringValue()) {
                        case "target" -> strings(e.value(), out.targets);
                        case "ordinal" -> {
                            if (e.value() instanceof AnnotationValue.OfConstant c && c.resolvedValue() instanceof Integer i) out.ordinal = i;
                        }
                        default -> { }
                    }
                }
            }
        }

        /**
         * Marks the sites (indices into {@code all}, bytecode order per method) that a native mixin covers.
         * A redirect without ordinal covers every matching site of its method; with ordinal k it covers only the k-th
         * (0-based) matching site; with a slice every match is covered (upper bound, counted in {@link #approximate}).
         */
        boolean[] coverage(List<Site> all) {
            boolean[] cov = new boolean[all.size()];
            Map<String, Map<String, List<Integer>>> idx = new HashMap<>();
            for (int i = 0; i < all.size(); i++) {
                Site s = all.get(i);
                if (overwrites.getOrDefault(s.cls(), Set.of()).contains(s.method())) cov[i] = true;
                idx.computeIfAbsent(s.cls(), k -> new HashMap<>()).computeIfAbsent(s.method(), k -> new ArrayList<>()).add(i);
            }
            for (var ce : idx.entrySet()) {
                List<Redirect> rs = redirects.get(ce.getKey());
                if (rs == null) continue;
                for (var me : ce.getValue().entrySet()) {
                    for (Redirect rd : rs) {
                        if (!methodMatches(rd.methods(), me.getKey())) continue;
                        List<Integer> hits = new ArrayList<>();
                        for (int si : me.getValue()) {
                            Site s = all.get(si);
                            for (String t : rd.targets()) {
                                if (targetMatches(t, s)) {
                                    hits.add(si);
                                    break;
                                }
                            }
                        }
                        if (hits.isEmpty()) continue;
                        if (rd.ordinal() >= 0 && !rd.sliced()) {
                            if (rd.ordinal() < hits.size()) cov[hits.get(rd.ordinal())] = true;
                        } else {
                            for (int h : hits) cov[h] = true;
                            if (rd.ordinal() >= 0) approximate++;
                        }
                    }
                }
            }
            return cov;
        }

        /**
         * Selector forms: "name" (any overload), "name*" (prefix), "name(desc)" or "name(desc)ret" (exact descriptor),
         * optionally prefixed with "Lowner;".
         */
        static boolean methodMatches(List<String> sel, String nameDesc) {
            String name = nameDesc.substring(0, nameDesc.indexOf('('));
            for (String x : sel) {
                String y = x;
                int semi = y.indexOf(';');
                if (y.startsWith("L") && semi > 0 && (y.indexOf('(') < 0 || semi < y.indexOf('('))) y = y.substring(semi + 1);
                if (y.endsWith("*")) {
                    if (name.startsWith(y.substring(0, y.length() - 1))) return true;
                } else if (y.indexOf('(') < 0) {
                    if (y.equals(name)) return true;
                } else if (nameDesc.equals(y) || (y.endsWith(")") && nameDesc.startsWith(y))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Matches a Mixin at-target against a site. Owner must match exactly. A target without a member matches only NEW sites;
         * a target name without descriptor matches every overload/field type; a target with a descriptor must match it.
         */
        static boolean targetMatches(String t, Site s) {
            Ref r = Ref.parse(t);
            if (!r.owner().equals(s.owner())) return false;
            boolean noMember = r.member() == null || r.member().isEmpty();
            if (s.kind().equals("NEW")) return noMember;
            if (noMember) return false;
            String rm = r.member();
            String sm = s.member();
            String refName = nameOf(rm);
            String siteName = nameOf(sm);
            String refRest = rm.substring(refName.length());
            if (!refName.equals(siteName)) return false;
            return refRest.isEmpty() || sm.equals(rm) || (rm.endsWith(")") && sm.startsWith(rm));
        }

        /** Member name without descriptor: up to the first '(' (method) or ':' (field). */
        static String nameOf(String member) {
            int p = member.length();
            int a = member.indexOf('(');
            int b = member.indexOf(':');
            if (a >= 0) p = Math.min(p, a);
            if (b >= 0) p = Math.min(p, b);
            return member.substring(0, p);
        }
    }

    // ---------------------------------------------------------------- output

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    static void write(Path dir, String base, String mode, List<Site> sites, int classCount, List<String> info) throws Exception {
        Map<String, Map<String, List<Site>>> by = new TreeMap<>();
        for (Site s : sites) by.computeIfAbsent(s.cls(), k -> new TreeMap<>()).computeIfAbsent(s.method(), k -> new ArrayList<>()).add(s);
        int methodCount = 0;
        for (var m : by.values()) methodCount += m.size();
        Map<String, Integer> owners = ownerCounts(sites);

        StringBuilder md = new StringBuilder();
        md.append("# GL sites (").append(mode).append(")\n\n");
        for (String i : info) md.append("- ").append(i).append('\n');
        md.append("- net/minecraft classes scanned: ").append(classCount).append('\n');
        md.append("- **total sites: ").append(sites.size()).append("**, classes with sites: ").append(by.size()).append(", methods: ").append(methodCount).append('\n');
        md.append("- by owner: ");
        boolean fo = true;
        for (var e : owners.entrySet()) {
            if (!fo) md.append(", ");
            fo = false;
            md.append('`').append(e.getKey()).append("` ").append(e.getValue());
        }
        md.append("\n\n| class | methods | sites |\n|---|---:|---:|\n");
        for (var e : by.entrySet()) {
            int n = 0;
            for (var l : e.getValue().values()) n += l.size();
            md.append("| ").append(e.getKey()).append(" | ").append(e.getValue().size()).append(" | ").append(n).append(" |\n");
        }
        md.append("\n## Sites per method\n\n");
        for (var e : by.entrySet()) {
            md.append("### ").append(e.getKey()).append("\n\n");
            for (var m : e.getValue().entrySet()) {
                md.append("- `").append(m.getKey()).append("`: ").append(m.getValue().size()).append('\n');
                for (Site s : m.getValue()) {
                    md.append("  - L").append(s.line()).append(' ').append(s.kind()).append(' ').append(s.owner()).append('.').append(s.member()).append('\n');
                }
            }
            md.append('\n');
        }
        Files.writeString(dir.resolve(base + ".md"), md.toString(), StandardCharsets.UTF_8);

        StringBuilder js = new StringBuilder();
        js.append("{\n  \"mode\": \"").append(mode).append("\",\n  \"totalSites\": ").append(sites.size())
                .append(",\n  \"classesScanned\": ").append(classCount).append(",\n  \"classesWithSites\": ").append(by.size())
                .append(",\n  \"methodsWithSites\": ").append(methodCount).append(",\n  \"ownerCounts\": {");
        boolean fk = true;
        for (var e : owners.entrySet()) {
            if (!fk) js.append(", ");
            fk = false;
            js.append('"').append(esc(e.getKey())).append("\": ").append(e.getValue());
        }
        js.append("},\n  \"classes\": [\n");
        boolean fc = true;
        for (var e : by.entrySet()) {
            if (!fc) js.append(",\n");
            fc = false;
            int n = 0;
            for (var l : e.getValue().values()) n += l.size();
            js.append("    {\"class\": \"").append(esc(e.getKey())).append("\", \"count\": ").append(n).append(", \"methods\": [\n");
            boolean fm = true;
            for (var m : e.getValue().entrySet()) {
                if (!fm) js.append(",\n");
                fm = false;
                js.append("      {\"method\": \"").append(esc(m.getKey())).append("\", \"count\": ").append(m.getValue().size()).append(", \"sites\": [");
                boolean fs = true;
                for (Site s : m.getValue()) {
                    if (!fs) js.append(", ");
                    fs = false;
                    js.append("{\"line\": ").append(s.line()).append(", \"kind\": \"").append(s.kind()).append("\", \"owner\": \"")
                            .append(esc(s.owner())).append("\", \"member\": \"").append(esc(s.member())).append("\"}");
                }
                js.append("]}");
            }
            js.append("\n    ]}");
        }
        js.append("\n  ]\n}\n");
        Files.writeString(dir.resolve(base + ".json"), js.toString(), StandardCharsets.UTF_8);
    }
}

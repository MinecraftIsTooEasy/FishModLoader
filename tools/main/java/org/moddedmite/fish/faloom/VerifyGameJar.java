package org.moddedmite.fish.faloom;

import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerClassVisitor;
import net.fabricmc.accesswidener.AccessWidenerReader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Runs the JVM bytecode verifier over every class of a remapped game jar,
 * with the runtime AccessWidener applied, and reports verification failures.
 *
 * <p>Why this exists: remapping MITE from its {@code official} namespace (where
 * almost everything sits in the default package) to {@code intermediary} splits
 * classes across packages. That turns previously-legal accesses to
 * {@code protected} members into ones the verifier rejects, e.g.
 *
 * <pre>
 * java.lang.VerifyError: Bad access to protected data in invokevirtual
 *   Type 'net/minecraft/entity/monster/EntitySkeleton' is not assignable to
 *        'net/minecraft/entity/EntityBoneLord'
 * </pre>
 *
 * <p>TinyRemapper's {@code fixPackageAccess} does <em>not</em> catch these.
 * Its {@code PackageAccessChecker} implements JVMS 5.4.4 (resolution-time
 * access control) but not JVMS 4.10.1.8, the verifier's extra rule that a
 * protected member of a superclass in another runtime package may only be
 * accessed through a receiver assignable to the <em>current</em> class. When
 * the receiver is a local variable rather than {@code this}, the check fires.
 * Such cases have to be widened via {@code fishmodloader.accesswidener}.
 *
 * <p>Verification is what fails at runtime, so reproducing it here is the only
 * reliable way to find these before they crash the game.
 *
 * <p>Defining a class is <em>not</em> enough. HotSpot verifies during linking,
 * and linking is lazy, so {@code defineClass} alone reports nothing. The
 * original crash surfaced through {@code Class.getConstructor()} ->
 * {@code getDeclaredConstructors0}, which forces linkage; this tool therefore
 * reflects over the declared members of every class to trigger the same step.
 *
 * <p>Usage: {@code VerifyGameJar <remappedGameJar> [intermediaryAw] [libJar...]}
 */
public final class VerifyGameJar {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: VerifyGameJar <remappedGameJar> [intermediaryAw] [libJar...]");
            System.exit(1);
        }

        Path gameJar = Path.of(args[0]);
        if (!Files.exists(gameJar)) {
            System.err.println("Not found: " + gameJar);
            System.exit(1);
        }

        AccessWidener widener = null;
        if (args.length >= 2 && !args[1].isEmpty()) {
            Path awPath = Path.of(args[1]);
            if (Files.exists(awPath)) {
                widener = new AccessWidener();
                try (BufferedReader r = Files.newBufferedReader(awPath, StandardCharsets.UTF_8)) {
                    new AccessWidenerReader(widener).read(r);
                }
                System.out.println("Loaded AccessWidener: " + widener.getTargets().size()
                        + " target class(es) from " + awPath);
            } else {
                System.out.println("WARNING: AccessWidener not found, verifying raw jar: " + awPath);
            }
        }

        List<Path> extraJars = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            Path lib = Path.of(args[i]);
            if (Files.exists(lib)) extraJars.add(lib);
        }

        Map<String, byte[]> classes = new HashMap<>();
        readClasses(gameJar, classes);
        int gameClassCount = classes.size();
        for (Path lib : extraJars) {
            readClasses(lib, classes);
        }
        System.out.println("Read " + gameClassCount + " game class(es)"
                + (extraJars.isEmpty() ? "" : " + " + (classes.size() - gameClassCount) + " library class(es)"));

        List<String> gameClassNames = new ArrayList<>();
        int skipped = 0;
        try (JarFile jf = new JarFile(gameJar.toFile())) {
            java.util.Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")) continue;
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                if (isLoaderOwned(className)) {
                    skipped++;
                    continue;
                }
                gameClassNames.add(className);
            }
        }
        gameClassNames.sort(Comparator.naturalOrder());
        if (skipped > 0) {
            System.out.println("Skipped " + skipped
                    + " class(es) the loader serves from its own classpath (see isLoaderOwned)");
        }

        VerifyingLoader loader = new VerifyingLoader(classes, widener);

        // Real defects: the class is present and well-formed enough to load,
        // but the JVM rejects it. VerifyError and IllegalAccessError are both
        // in scope -- the AccessWidener exists to prevent exactly these.
        Map<String, String> defects = new TreeMap<>();
        // Counted separately: absent third-party libraries (LWJGL et al) make
        // client classes unloadable here without meaning the jar is broken.
        Map<String, String> missing = new TreeMap<>();
        Map<String, String> other = new TreeMap<>();
        // -Dverify.trace=<substring> prints the full throwable for matching
        // classes. Without it a class that fails for an unexpected reason just
        // lands in a bucket and stays invisible.
        String trace = System.getProperty("verify.trace");
        int ok = 0;

        for (String className : gameClassNames) {
            if (trace != null && className.contains(trace)) {
                try {
                    Class<?> c = loader.loadClass(className);
                    c.getDeclaredConstructors();
                    c.getDeclaredMethods();
                    System.out.println("[trace] " + className + ": linked OK");
                } catch (Throwable t) {
                    System.out.println("[trace] " + className + ": " + t.getClass().getName());
                    t.printStackTrace(System.out);
                }
                continue;
            }
            try {
                Class<?> loaded = loader.loadClass(className);
                // Force linkage -- this is what actually runs the verifier.
                // Reflecting over declared members links without running the
                // static initialiser, so no game state is touched.
                loaded.getDeclaredConstructors();
                loaded.getDeclaredMethods();
                loaded.getDeclaredFields();
                ok++;
            } catch (Throwable t) {
                // Classify by the first interesting cause: a defect can arrive
                // wrapped when it surfaces while linking a referenced supertype.
                Throwable defect = findAny(t, VerifyError.class, IllegalAccessError.class);
                if (defect != null) {
                    defects.put(className, defect.getClass().getSimpleName() + System.lineSeparator()
                            + firstLines(defect.getMessage(), 4));
                    continue;
                }
                Throwable absent = findAny(t, NoClassDefFoundError.class, UnsatisfiedLinkError.class);
                if (absent != null) {
                    missing.put(className, String.valueOf(absent.getMessage()));
                } else {
                    other.put(className, t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
            }
        }

        System.out.println();
        System.out.println("Linked OK        : " + ok);
        System.out.println("Missing deps     : " + missing.size()
                + " (not a jar defect if libs were omitted)");
        System.out.println("Other            : " + other.size());
        System.out.println("DEFECTS          : " + defects.size()
                + " (VerifyError / IllegalAccessError)");

        if (!other.isEmpty()) {
            // Not classified as a defect, but not obviously benign either --
            // print a digest so a real problem cannot hide in a bulk count.
            Map<String, Integer> byMessage = new TreeMap<>();
            for (String value : other.values()) {
                byMessage.merge(value, 1, Integer::sum);
            }
            System.out.println();
            System.out.println("Other link errors grouped by message:");
            byMessage.forEach((message, count) ->
                    System.out.println("  " + count + "x " + message));
        }

        if (!defects.isEmpty()) {
            System.out.println();
            for (Map.Entry<String, String> e : defects.entrySet()) {
                System.out.println("--- " + e.getKey());
                System.out.println(e.getValue());
            }
            System.exit(2);
        }
    }

    /** First cause matching any of {@code types}, searching the whole chain. */
    @SafeVarargs
    private static Throwable findAny(Throwable t, Class<? extends Throwable>... types) {
        for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(cursor)) return cursor;
            }
        }
        return null;
    }

    /**
     * Classes the game jar ships but that never load from it at runtime.
     *
     * <p>{@code LaunchClassBlocker} blocks these prefixes so the loader's own
     * (modern) copies win; MITE's bundled antiques are dead weight, and the
     * build strips {@code com/google/**} from the compile jar for the same
     * reason. Verifying them here only produces split-loader noise: this
     * harness would define the bundled copy while its parent resolves the
     * tool's copy, yielding IllegalAccessError for a pairing that never occurs
     * in the running game.
     */
    private static boolean isLoaderOwned(String className) {
        return className.startsWith("com.google.")
                || className.startsWith("org.apache.logging.")
                || className.startsWith("org.objectweb.asm.")
                || className.startsWith("org.spongepowered.asm.")
                || className.startsWith("net.fabricmc.loader.")
                || className.startsWith("net.fabricmc.api.")
                || className.startsWith("com.chocohead.mm.");
    }

    private static String firstLines(String message, int limit) {
        if (message == null) return "(no message)";
        String[] lines = message.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, lines.length); i++) {
            sb.append("    ").append(lines[i].trim()).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static void readClasses(Path jar, Map<String, byte[]> into) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            java.util.Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")) continue;
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                // Game jar is read first; libraries must not shadow it.
                if (into.containsKey(className)) continue;
                try (InputStream in = jf.getInputStream(entry)) {
                    into.put(className, in.readAllBytes());
                }
            }
        }
    }

    /**
     * Defines classes from an in-memory map, applying the AccessWidener exactly
     * as {@code FMLClassTransformer} does at runtime, so verification sees the
     * same access flags the game would.
     */
    private static final class VerifyingLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        private final AccessWidener widener;

        VerifyingLoader(Map<String, byte[]> classes, AccessWidener widener) {
            super(VerifyingLoader.class.getClassLoader());
            this.classes = classes;
            this.widener = widener;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);

            if (widener != null && widener.getTargets().contains(name)) {
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(0);
                reader.accept(
                        AccessWidenerClassVisitor.createClassVisitor(Opcodes.ASM9, writer, widener),
                        0);
                bytes = writer.toByteArray();
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

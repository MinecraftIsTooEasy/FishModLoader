package net.xiaoyu233.fml.vulkan.audio;

import net.xiaoyu233.fml.vulkan.VulkanConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds and loads the OpenAL Soft library that FML bundles (LWJGL 3 natives: {@code libopenal.dylib}, {@code OpenAL.dll}).
 *
 * <p>Search order, first valid library wins:
 * <ol>
 *   <li>{@code -Dfml.openal.library} / {@code -Dorg.lwjgl.openal.libname}: a file, a directory or a library name (override),</li>
 *   <li>{@code fml.natives.dir} (one directory, set by the NativeExtractor) and {@code org.lwjgl.librarypath},</li>
 *   <li>the copy bundled in the FML jar ({@code <os>/<arch>/org/lwjgl/openal/<lib>}), written once to
 *       {@code <gameDir>/.fml/natives/openal-<os>-<arch>-<sha1>/} and reused by every later start,</li>
 *   <li>{@code java.library.path} (launcher natives, usually an old LWJGL 2 OpenAL Soft),</li>
 *   <li>the system libraries (Apple's OpenAL.framework, Homebrew, ...).</li>
 * </ol>
 * The last two are substitutes: they can lack features of the bundled OpenAL Soft (EFX), so loading one logs a warning that
 * says why the bundled library was skipped. A failure is not cached: a later call searches again. Every candidate that was
 * tried is listed in the failure message.
 */
final class OpenALLibrary {
    static final String PROP_LIBRARY = "fml.openal.library";
    static final String PROP_LWJGL_LIBNAME = "org.lwjgl.openal.libname";
    static final String PROP_NATIVES_DIR = "fml.natives.dir";
    static final String PROP_LWJGL_LIBRARYPATH = "org.lwjgl.librarypath";

    private record Loaded(SymbolLookup lookup, String origin) {
    }

    /**
     * The outcome of the search. {@code substitute} is true for a library that is neither the one FML bundles nor an explicit
     * choice of the user; {@code skipped} then lists why the better candidates were not used.
     */
    private record Located(Loaded library, boolean substitute, List<String> skipped) {
        static Located intended(Loaded library) {
            return new Located(library, false, List.of());
        }

        static Located ofSubstitute(Loaded library, List<String> skipped) {
            return new Located(library, true, List.copyOf(skipped));
        }
    }

    private static SymbolLookup lookup;
    private static String origin;

    private OpenALLibrary() {
    }

    /** @return the symbol lookup of the OpenAL library, loading it on first use. */
    static synchronized SymbolLookup lookup() {
        if (lookup == null) {
            Located located = locate();
            lookup = located.library().lookup();
            origin = located.library().origin();
            AudioLog.info("Loaded OpenAL library: " + origin);
            if (located.substitute()) {
                AudioLog.warn(substituteNotice(origin, located.skipped()), null);
            }
        }
        return lookup;
    }

    /** @return where the library was loaded from, null before it was loaded. */
    static synchronized String origin() {
        return origin;
    }

    private static String substituteNotice(String origin, List<String> skipped) {
        StringBuilder notice = new StringBuilder("The OpenAL Soft bundled with FML could not be used, loaded a different OpenAL library instead: ")
                .append(origin)
                .append(".\n  It can lack features of the bundled library, for example the EFX audio effects (mods that use them fail with"
                        + " UnsupportedOperationException), and it can behave differently.");
        if (!skipped.isEmpty()) {
            notice.append("\n  Candidates that did not work:");
            for (String line : skipped) {
                notice.append("\n    ").append(line);
            }
        }
        return notice.append("\n  Use -D").append(PROP_LIBRARY).append("=<path of an OpenAL Soft library> to choose the library yourself.").toString();
    }

    // ---------------------------------------------------------------- platform

    static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // macOS first: "darwin" contains "win", so a substring test for Windows would claim it. Windows names start with it.
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.startsWith("win")) return "windows";
        return "linux";
    }

    static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64", "x64" -> "x64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> arch;
        };
    }

    /** File names of the library inside a natives directory, the first one is the name used inside the FML jar. */
    private static String[] bundledNames(String os) {
        return switch (os) {
            case "macos" -> new String[]{"libopenal.dylib", "libopenal.1.dylib", "openal.dylib"};
            case "windows" -> new String[]{"OpenAL.dll", "OpenAL32.dll", "soft_oal.dll"};
            default -> new String[]{"libopenal.so", "libopenal.so.1"};
        };
    }

    /** Names LWJGL 2 launchers put on java.library.path. */
    private static String[] launcherNames(String os) {
        return switch (os) {
            case "macos" -> new String[]{"openal.dylib", "libopenal.dylib"};
            case "windows" -> new String[]{"OpenAL64.dll", "OpenAL32.dll", "OpenAL.dll"};
            default -> new String[]{"libopenal64.so", "libopenal.so", "libopenal.so.1"};
        };
    }

    private static String[] systemNames(String os) {
        return switch (os) {
            case "macos" -> new String[]{"libopenal.dylib", "/opt/homebrew/opt/openal-soft/lib/libopenal.dylib",
                    "/usr/local/opt/openal-soft/lib/libopenal.dylib", "/System/Library/Frameworks/OpenAL.framework/OpenAL"};
            case "windows" -> new String[]{"OpenAL32.dll", "OpenAL32", "soft_oal.dll"};
            default -> new String[]{"libopenal.so.1", "libopenal.so", "libopenal.so.0"};
        };
    }

    // ---------------------------------------------------------------- search

    private static Located locate() {
        String os = osName();
        String arch = archName();
        List<String> failures = new ArrayList<>();
        Set<String> tried = new LinkedHashSet<>();

        for (String prop : new String[]{PROP_LIBRARY, PROP_LWJGL_LIBNAME}) {
            String value = System.getProperty(prop);
            if (value != null && !value.isBlank()) {
                int mark = failures.size();
                Loaded loaded = loadSpec(value.trim(), "-D" + prop, os, tried, failures);
                if (loaded != null) return Located.intended(loaded);
                if (failures.size() > mark) {
                    // an explicit choice that does not apply must not go unnoticed: the search goes on with the bundled library
                    AudioLog.warn("Ignoring -D" + prop + "=" + value.trim() + ", it is not a usable OpenAL library:\n  "
                            + String.join("\n  ", failures.subList(mark, failures.size())), null);
                }
            }
        }

        for (Path dir : singleDirectory(System.getProperty(PROP_NATIVES_DIR))) {
            Loaded loaded = loadFromDirectory(dir, bundledNames(os), PROP_NATIVES_DIR, true, tried, failures);
            if (loaded != null) return Located.intended(loaded);
        }
        for (Path dir : libraryPath(System.getProperty(PROP_LWJGL_LIBRARYPATH))) {
            Loaded loaded = loadFromDirectory(dir, bundledNames(os), PROP_LWJGL_LIBRARYPATH, true, tried, failures);
            if (loaded != null) return Located.intended(loaded);
        }

        Loaded extracted = extractBundled(os, arch, tried, failures);
        if (extracted != null) return Located.intended(extracted);

        for (Path dir : pathList(System.getProperty("java.library.path"))) {
            Loaded loaded = loadFromDirectory(dir, launcherNames(os), "java.library.path", false, tried, failures);
            if (loaded != null) return Located.ofSubstitute(loaded, failures);
        }

        for (String name : systemNames(os)) {
            Loaded loaded = loadByName(name, "system", tried, failures);
            if (loaded != null) return Located.ofSubstitute(loaded, failures);
        }

        throw new UnsatisfiedLinkError("Could not load the OpenAL library for " + os + "/" + arch + ". Tried:\n  "
                + String.join("\n  ", failures));
    }

    /** A property that holds exactly one directory (fml.natives.dir): the whole value, whatever characters it contains. */
    private static List<Path> singleDirectory(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return List.of(Path.of(value.trim()));
        } catch (InvalidPathException e) {
            return List.of();
        }
    }

    /**
     * org.lwjgl.librarypath. The NativeExtractor sets it to one directory, and on macOS and Linux a directory name may contain
     * {@link File#pathSeparator} (':'), so the whole value is tried first. LWJGL 3 itself reads the property as a list, so a
     * value that is not a directory is split like java.library.path.
     */
    private static List<Path> libraryPath(String value) {
        List<Path> whole = singleDirectory(value);
        if (!whole.isEmpty() && Files.isDirectory(whole.get(0))) return whole;
        List<Path> entries = pathList(value);
        return entries.isEmpty() ? whole : entries;
    }

    /** A real path list (java.library.path): entries separated by {@link File#pathSeparator}. */
    private static List<Path> pathList(String value) {
        List<Path> result = new ArrayList<>();
        if (value == null || value.isBlank()) return result;
        for (String entry : value.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            try {
                result.add(Path.of(entry.trim()));
            } catch (InvalidPathException ignored) {
                // not a path of this platform: skip
            }
        }
        return result;
    }

    /** An explicit override: a library file, a directory containing the library, or a library name. */
    private static Loaded loadSpec(String spec, String source, String os, Set<String> tried, List<String> failures) {
        Path path = null;
        try {
            path = Path.of(spec);
        } catch (InvalidPathException ignored) {
            // treated as a library name
        }
        if (path != null && Files.isDirectory(path)) {
            return loadFromDirectory(path, bundledNames(os), source, true, tried, failures);
        }
        if (path != null && (path.isAbsolute() || spec.indexOf('/') >= 0 || spec.indexOf('\\') >= 0)) {
            return loadFile(path, source, tried, failures);
        }
        return loadByName(spec, source, tried, failures);
    }

    /** Tries the library names in a directory; absent files are reported as one line. */
    private static Loaded loadFromDirectory(Path dir, String[] names, String source, boolean reportMissingDirectory,
                                            Set<String> tried, List<String> failures) {
        if (!Files.isDirectory(dir)) {
            if (reportMissingDirectory) failures.add(source + ": " + dir + " (not a directory)");
            return null;
        }
        List<String> absent = new ArrayList<>();
        for (String name : names) {
            Path path = dir.resolve(name).toAbsolutePath().normalize();
            if (!tried.add(path.toString())) continue;
            if (!Files.isRegularFile(path)) {
                absent.add(name);
                continue;
            }
            Loaded loaded = loadExisting(path, source, failures);
            if (loaded != null) return loaded;
        }
        if (!absent.isEmpty()) {
            failures.add(source + ": " + dir.toAbsolutePath().normalize() + " (no " + String.join(", ", absent) + ")");
        }
        return null;
    }

    private static Loaded loadFile(Path file, String source, Set<String> tried, List<String> failures) {
        Path path = file.toAbsolutePath().normalize();
        if (!tried.add(path.toString())) return null;
        if (!Files.isRegularFile(path)) {
            failures.add(source + ": " + path + " (not found)");
            return null;
        }
        return loadExisting(path, source, failures);
    }

    private static Loaded loadExisting(Path path, String source, List<String> failures) {
        try {
            SymbolLookup candidate = SymbolLookup.libraryLookup(path, Arena.global());
            String problem = validate(candidate);
            if (problem != null) {
                failures.add(source + ": " + path + " (" + problem + ")");
                return null;
            }
            return new Loaded(candidate, path.toString());
        } catch (RuntimeException | LinkageError e) {
            failures.add(source + ": " + path + " (" + e + ")");
            return null;
        }
    }

    private static Loaded loadByName(String name, String source, Set<String> tried, List<String> failures) {
        if (!tried.add("name:" + name)) return null;
        try {
            SymbolLookup candidate = SymbolLookup.libraryLookup(name, Arena.global());
            String problem = validate(candidate);
            if (problem != null) {
                failures.add(source + ": " + name + " (" + problem + ")");
                return null;
            }
            return new Loaded(candidate, name + " (" + source + ")");
        } catch (RuntimeException | LinkageError e) {
            failures.add(source + ": " + name + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /** @return null if the library exports the OpenAL core entry points, otherwise the reason it is unusable. */
    private static String validate(SymbolLookup candidate) {
        for (String symbol : new String[]{"alcOpenDevice", "alcCreateContext", "alGetError", "alGenSources"}) {
            if (candidate.find(symbol).isEmpty()) {
                return "not an OpenAL library, " + symbol + " is missing";
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- bundled copy

    /**
     * Loads the library bundled in the FML jar from a stable cache directory (see {@link #materialize}); it is written on the
     * first start only. The same file is reached through every class loader that sees the resource, so a start never leaves
     * more than one copy behind.
     */
    private static Loaded extractBundled(String os, String arch, Set<String> tried, List<String> failures) {
        String name = bundledNames(os)[0];
        String resource = os + "/" + arch + "/org/lwjgl/openal/" + name;
        String source = "class path resource /" + resource;
        IdentityHashMap<ClassLoader, Boolean> seen = new IdentityHashMap<>();
        ClassLoader[] loaders = {OpenALLibrary.class.getClassLoader(), Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()};
        Set<String> attempted = new HashSet<>();
        boolean found = false;
        for (ClassLoader loader : loaders) {
            if (loader == null || seen.put(loader, Boolean.TRUE) != null) continue;
            try {
                byte[] content;
                try (InputStream in = loader.getResourceAsStream(resource)) {
                    if (in == null) continue;
                    found = true;
                    content = in.readAllBytes();
                }
                String digest = sha1(content);
                if (!attempted.add(digest)) continue; // the same library seen through another class loader: already tried
                Loaded loaded = loadFile(materialize(content, digest, name, os, arch), source, tried, failures);
                if (loaded != null) {
                    return new Loaded(loaded.lookup(), loaded.origin() + " (extracted from /" + resource + ")");
                }
            } catch (IOException | RuntimeException e) {
                failures.add(source + ": " + e);
            }
        }
        if (!found) {
            failures.add(source + " (not found)");
        }
        return null;
    }

    /**
     * @return a file that holds {@code content}: {@code <gameDir>/.fml/natives/openal-<os>-<arch>-<sha1>/<name>}, reused as long
     * as its content is identical (a new build of the library has another hash and gets its own directory, so a running game
     * that still has the old one loaded is never disturbed). Only when the game directory cannot be written is the library
     * written to a new temporary directory, which Windows cannot delete again while the DLL is loaded.
     */
    private static Path materialize(byte[] content, String digest, String name, String os, String arch) throws IOException {
        String directory = "openal-" + os + "-" + arch + "-" + digest.substring(0, 16);
        try {
            return cached(VulkanConfig.gameDir().resolve(".fml").resolve("natives").resolve(directory), name, content);
        } catch (IOException | RuntimeException e) {
            AudioLog.warn("Cannot keep the bundled OpenAL library in the game directory, extracting it to a temporary directory instead ("
                    + e + ")", null);
        }
        Path dir = Files.createTempDirectory("fml-openal-");
        Path file = dir.resolve(name);
        Files.write(file, content);
        // deleteOnExit runs in reverse order of registration: the file first, then its directory
        dir.toFile().deleteOnExit();
        file.toFile().deleteOnExit();
        return file;
    }

    /** Makes {@code dir/name} hold {@code content}: nothing is written when it already does, otherwise it is replaced atomically. */
    private static Path cached(Path dir, String name, byte[] content) throws IOException {
        Path file = dir.resolve(name);
        if (hasContent(file, content)) return file;
        Files.createDirectories(dir);
        Path part = Files.createTempFile(dir, name + ".", ".part");
        try {
            Files.write(part, content);
            try {
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // a parallel start installed the same file meanwhile, or Windows refuses to replace a library that is loaded
                if (!hasContent(file, content)) throw e;
            }
        } finally {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // the leftover is a few MB in a cache directory
            }
        }
        return file;
    }

    private static boolean hasContent(Path file, byte[] content) {
        try {
            return Files.isRegularFile(file) && Files.size(file) == content.length && Arrays.equals(Files.readAllBytes(file), content);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String sha1(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }
}

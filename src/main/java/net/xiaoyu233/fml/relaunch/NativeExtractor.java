package net.xiaoyu233.fml.relaunch;

import net.xiaoyu233.fml.util.UrlUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts the LWJGL 3 native libraries of the current platform from the FML jar into
 * {@code <gameDir>/.fml/natives/lwjgl-3.4.3-<os>-<arch>/} (one flat directory) and points LWJGL and the FML backends at it.
 * <p>
 * Runs on the AppClassLoader side before Knot loads anything: it must not reference {@code org.lwjgl.*} or
 * {@code net.xiaoyu233.fml.vulkan.*} classes. System properties set:
 * <ul>
 *   <li>{@code org.lwjgl.librarypath} and {@code org.lwjgl.system.SharedLibraryExtractPath}: the natives directory. The natives
 *   are also class-path resources, so LWJGL would extract its own copy into a temp directory; the extract path makes it reuse
 *   (and verify) this directory instead, so there is exactly one copy of every library,</li>
 *   <li>{@code fml.natives.dir}: the same directory (used by the OpenAL compat for {@code libopenal.dylib}/{@code OpenAL.dll}),</li>
 *   <li>{@code org.lwjgl.vulkan.libname}: {@code -Dfml.vulkan.library=<path>} when given, otherwise (macOS) the bundled MoltenVK.
 *   Windows and Linux use the system Vulkan loader ({@code vulkan-1.dll} / {@code libvulkan.so.1}).</li>
 * </ul>
 * {@code -Dfml.natives.dir=<dir>} chooses another extraction directory (for example for a read-only game directory).
 * <p>
 * The extracted files are loaded as native code, so the directory is handled defensively: a symbolic link in place of the directory
 * or of a library is refused (a link could make FML write to, or load, a file outside the directory), concurrent launches of the
 * same game directory serialise on {@code .lock} in the directory, and every failure names the directory and the
 * {@code -Dfml.natives.dir} way out.
 * <p>
 * {@code org.lwjgl.vulkan.explicitInit} is deliberately left alone (LWJGL default: automatic initialisation). LWJGL 3.4's {@code VK}
 * class initialiser creates the Vulkan loader on the first touch of the class (using {@code org.lwjgl.vulkan.libname}), and
 * {@code VK.getFunctionProvider()} never returns null: while the loader is not created it throws
 * {@code IllegalStateException("Vulkan library has not been loaded.")}. The backend ({@code WindowManager.initVulkanLoader},
 * {@code DeviceSetup.createInstance}) relies on that automatic initialisation, so with explicit init every one of its
 * {@code getFunctionProvider()} guards would throw, and an unguarded {@code VK.create()} after the first touch of {@code VK} throws
 * {@code IllegalStateException("Vulkan has already been created.")}. Never call {@code VK.create()} from backend code.
 */
public final class NativeExtractor {
    public static final String LWJGL_VERSION = "3.4.3";

    public static final String PROP_NATIVES_DIR = "fml.natives.dir";
    public static final String PROP_VULKAN_LIBRARY = "fml.vulkan.library";

    private static final String PROP_LWJGL_LIBRARY_PATH = "org.lwjgl.librarypath";
    private static final String PROP_LWJGL_EXTRACT_PATH = "org.lwjgl.system.SharedLibraryExtractPath";
    private static final String PROP_LWJGL_VULKAN_LIBNAME = "org.lwjgl.vulkan.libname";

    /** Platforms whose natives are bundled in the FML jar (the natives classifiers of build.gradle). */
    static final Set<String> SUPPORTED_PLATFORMS = Set.of("windows/x64", "macos/x64", "macos/arm64", "linux/x64", "linux/arm64");

    private NativeExtractor() {
    }

    public static void extractNatives(Path gameDir) throws IOException {
        String os = detectOs(System.getProperty("os.name", ""));
        String arch = detectArch(System.getProperty("os.arch", ""));
        String platform = os + "/" + arch;
        if (!SUPPORTED_PLATFORMS.contains(platform)) {
            throw new IllegalStateException("The FishModLoader Vulkan backend has no natives for " + platform
                    + " (os.name=" + System.getProperty("os.name") + ", os.arch=" + System.getProperty("os.arch")
                    + "); supported platforms: " + String.join(", ", new TreeSet<>(SUPPORTED_PLATFORMS)));
        }

        String override = trimToNull(System.getProperty(PROP_NATIVES_DIR));
        Path nativesDir = override != null
                ? Paths.get(override)
                : gameDir.resolve(".fml").resolve("natives").resolve("lwjgl-" + LWJGL_VERSION + "-" + os + "-" + arch);
        nativesDir = nativesDir.toAbsolutePath().normalize();

        prepareDirectory(nativesDir);

        List<Path> sources = findNativeSources();
        int[] counts = new int[2]; // [0] libraries present and up to date afterwards, [1] of which (re)written now
        // one launch at a time per directory: a second instance waits until the first finished verifying / writing the libraries
        try (FileChannel lockChannel = FileChannel.open(nativesDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = lock(lockChannel, nativesDir)) {
            for (Path source : sources) {
                extractFromJar(source, platform, nativesDir, counts);
            }
        }
        if (counts[0] == 0) {
            throw new IllegalStateException("No LWJGL " + LWJGL_VERSION + " natives for " + platform + " found in "
                    + (sources.isEmpty() ? "any jar (FML does not run from a jar and the class path has none)" : sources)
                    + ". The natives are bundled in the FishModLoader fat jar (gradle shadowJar); when launching from classes"
                    + " directories put the lwjgl-" + LWJGL_VERSION + " natives jars on the class path.");
        }
        if (counts[1] > 0) {
            System.out.println("[FML] Extracted " + counts[1] + " LWJGL " + LWJGL_VERSION + " native librar" + (counts[1] == 1 ? "y" : "ies")
                    + " (" + platform + ") to " + nativesDir);
        }

        String dir = nativesDir.toString();
        System.setProperty(PROP_NATIVES_DIR, dir);
        System.setProperty(PROP_LWJGL_LIBRARY_PATH, dir);
        System.setProperty(PROP_LWJGL_EXTRACT_PATH, dir);

        String library = trimToNull(System.getProperty(PROP_VULKAN_LIBRARY));
        if (library != null) {
            System.setProperty(PROP_LWJGL_VULKAN_LIBNAME, library);
        } else if (os.equals("macos") && System.getProperty(PROP_LWJGL_VULKAN_LIBNAME) == null) {
            Path moltenVk = nativesDir.resolve("libMoltenVK.dylib");
            if (!Files.isRegularFile(moltenVk)) {
                throw new IllegalStateException("The bundled MoltenVK (libMoltenVK.dylib) is missing in " + nativesDir
                        + "; set -D" + PROP_VULKAN_LIBRARY + "=<path of a Vulkan loader or MoltenVK> to use another one");
            }
            System.setProperty(PROP_LWJGL_VULKAN_LIBNAME, moltenVk.toString());
        }
        // Windows / Linux: org.lwjgl.vulkan.libname stays unset, LWJGL loads the system loader (vulkan-1.dll / libvulkan.so.1)
    }

    /** Creates {@code dir} and checks it is a real, writable directory (not a symbolic link, not a file). */
    private static void prepareDirectory(Path dir) throws IOException {
        String hint = " (use -D" + PROP_NATIVES_DIR + "=<writable directory> to choose another one)";
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("The natives directory " + dir + " is a symbolic link; FML loads native code from it and refuses to"
                    + " follow links. Remove the link" + hint);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IOException("Cannot create the natives directory " + dir + hint + ": " + e, e);
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The natives path " + dir + " exists but is not a directory" + hint);
        }
        if (!Files.isWritable(dir)) {
            throw new IOException("The natives directory " + dir + " is not writable" + hint);
        }
    }

    /**
     * Exclusive lock on the natives directory. File systems without locks (some network shares) are tolerated: extraction is still
     * safe there, because every library is written to a temporary file and renamed into place.
     */
    private static FileLock lock(FileChannel channel, Path dir) throws IOException {
        try {
            return channel.lock();
        } catch (OverlappingFileLockException e) {
            return null; // this JVM already holds it (a second extraction in the same process)
        } catch (IOException e) {
            System.err.println("[FML] Cannot lock the natives directory " + dir + ", extracting without the lock: " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // platform
    // ---------------------------------------------------------------------------------------------------------

    static String detectOs(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) {
            return "macos";
        }
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("linux")) {
            return "linux";
        }
        throw new IllegalStateException("Unsupported operating system: " + osName);
    }

    static String detectArch(String osArch) {
        // Exact names only: "aarch64"/"arm64" also contain "64" and must never be mistaken for x64
        switch (osArch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> {
                return "arm64";
            }
            case "amd64", "x86_64", "x86-64", "x64" -> {
                return "x64";
            }
            case "x86", "i386", "i486", "i586", "i686" -> {
                return "x86";
            }
            default -> throw new IllegalStateException("Unsupported architecture: " + osArch);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // extraction
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The jar(s) that carry the natives: the FML fat jar. When FML runs from classes directories (IDE) every class-path jar is
     * searched instead (lwjgl-3.4.3-natives-*.jar contain the same {@code <os>/<arch>/org/lwjgl/**} layout).
     */
    private static List<Path> findNativeSources() {
        List<Path> sources = new ArrayList<>();
        Path fml = null;
        try {
            fml = UrlUtil.asPath(NativeExtractor.class.getProtectionDomain().getCodeSource().getLocation()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            // handled below: fall back to the class path
        }
        if (fml != null && Files.isRegularFile(fml)) {
            sources.add(fml);
            return sources;
        }

        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path path = Paths.get(entry).toAbsolutePath().normalize();
                if (Files.isRegularFile(path) && !sources.contains(path)) {
                    sources.add(path);
                }
            } catch (RuntimeException ignored) {
                // not a valid path on this platform
            }
        }
        return sources;
    }

    /**
     * Extracts the natives of {@code platform} from one jar. Adds the number of libraries that are now present and up to date to
     * {@code counts[0]} and the number of those that had to be (re)written to {@code counts[1]}.
     */
    private static void extractFromJar(Path jar, String platform, Path nativesDir, int[] counts) throws IOException {
        String prefix = platform + "/org/lwjgl/";
        Set<String> done = new HashSet<>();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || !isNativeLibrary(name)) {
                    continue;
                }

                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (!done.add(fileName)) {
                    continue; // the fat jar may contain an entry twice
                }

                if (extractEntry(zip, entry, nativesDir.resolve(fileName))) {
                    counts[1]++;
                }
                counts[0]++;
            }
        } catch (IOException e) {
            throw new IOException("Cannot extract the natives from " + jar + " to " + nativesDir + ": " + e, e);
        }
    }

    private static boolean isNativeLibrary(String entryName) {
        return entryName.endsWith(".dll") || entryName.endsWith(".dylib") || entryName.endsWith(".so");
    }

    /**
     * Makes {@code target} a copy of {@code entry} unless it already is one (same size and SHA-1). The expected SHA-1 comes from
     * the {@code META-INF/<entry>.sha1} file LWJGL ships next to every native, and is computed from the jar entry when absent.
     * The copy is written to a temporary file next to the target and renamed, so a concurrent or aborted launch never leaves a
     * truncated library behind and a library that is currently mapped is never rewritten in place.
     *
     * @return true when the file was (re)written
     */
    private static boolean extractEntry(ZipFile zip, ZipEntry entry, Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            // never load (or write through) a link planted in the natives directory: replace it with the real library
            Files.delete(target);
        }
        if (isIntact(zip, entry, target)) {
            return false;
        }

        Path temp = target.resolveSibling("." + target.getFileName() + "." + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp");
        try {
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            // Windows: the library is mapped by another running instance and cannot be replaced. Keep using it only when it is
            // byte-identical to the bundled one (checked again: it may have been replaced meanwhile), otherwise this launch cannot work
            if (isIntact(zip, entry, target)) {
                System.err.println("[FML] Cannot replace " + target + " (in use by another process?), keeping the existing identical file: " + e);
                return false;
            }
            throw new IOException("Cannot write " + target + " and the existing file differs from the bundled library (in use by"
                    + " another process, or no write permission?). Close other game instances or use -D" + PROP_NATIVES_DIR
                    + "=<writable directory>: " + e, e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** True when {@code target} is a regular file (not a link) with the size and SHA-1 of {@code entry}. */
    private static boolean isIntact(ZipFile zip, ZipEntry entry, Path target) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != entry.getSize()) {
            return false;
        }
        String expected = readSha1File(zip, entry.getName());
        if (expected == null) {
            try (InputStream in = zip.getInputStream(entry)) {
                expected = sha1(in);
            }
        }
        try (InputStream in = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            return expected.equalsIgnoreCase(sha1(in));
        }
    }

    /** The first token of {@code META-INF/<entryName>.sha1} when it is a SHA-1 hex string, otherwise null. */
    private static String readSha1File(ZipFile zip, String entryName) throws IOException {
        ZipEntry sha1Entry = zip.getEntry("META-INF/" + entryName + ".sha1");
        if (sha1Entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(sha1Entry)) {
            String text = new String(in.readNBytes(256), StandardCharsets.US_ASCII).trim();
            int end = 0;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            String hash = text.substring(0, end);
            return hash.length() == 40 && hash.chars().allMatch(HexFormat::isHexDigit) ? hash : null;
        }
    }

    private static String sha1(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JRE provides SHA-1
        }
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}

package net.xiaoyu233.fml.relaunch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Keeps UTF-8 while making LWJGL 2's Windows native search path ASCII-only. */
public final class OpenALBootstrap {
    private static final String LWJGL_LIBRARY_PATH = "org.lwjgl.librarypath";
    private static final String JAVA_LIBRARY_PATH = "java.library.path";
    private static boolean prepared;
    private OpenALBootstrap() {
    }

    public static synchronized void prepare() {
        if (prepared) return;
        if (!isWindows() || !"UTF-8".equalsIgnoreCase(System.getProperty("file.encoding", ""))) {
            return;
        }

        List<Path> libraryDirectories = getLibraryDirectories();
        if (!containsNonAscii(libraryDirectories)) {
            return;
        }

        Set<Path> nativeFiles = findNativeFiles(libraryDirectories);
        if (nativeFiles.isEmpty()) {
            return;
        }

        try {
            Path stagingDirectory = createAsciiStagingDirectory();
            for (Path nativeFile : nativeFiles) {
                Path stagedFile = stagingDirectory.resolve(nativeFile.getFileName().toString());
                if (!nativeFile.toAbsolutePath().normalize().equals(stagedFile.toAbsolutePath().normalize())) {
                    Files.copy(nativeFile, stagedFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // LWJGL checks this directory before java.library.path for both lwjgl and OpenAL.
            System.setProperty(LWJGL_LIBRARY_PATH, stagingDirectory.toString());
            prepared = true;
            System.err.println("[FishModLoader] Staged LWJGL natives in ASCII path: " + stagingDirectory);
        } catch (IOException | SecurityException e) {
            System.err.println("[FishModLoader] Could not stage LWJGL natives: " + e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static List<Path> getLibraryDirectories() {
        List<Path> directories = new ArrayList<>();
        addPathProperty(directories, LWJGL_LIBRARY_PATH);
        addPathProperty(directories, JAVA_LIBRARY_PATH);
        addPathProperty(directories, "user.dir");
        return directories;
    }

    private static void addPathProperty(List<Path> directories, String propertyName) {
        String value = System.getProperty(propertyName);
        if (value == null || value.isEmpty()) return;
        for (String directory : value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isEmpty()) continue;
            try {
                Path path = Paths.get(directory).toAbsolutePath().normalize();
                if (!directories.contains(path)) directories.add(path);
            } catch (InvalidPathException ignored) {
                // Ignore one malformed optional search path.
            }
        }
    }

    private static Set<Path> findNativeFiles(List<Path> directories) {
        String suffix = is64BitJvm() ? "64" : "";
        String lwjglName = "lwjgl" + suffix + ".dll";
        String openAlName = "OpenAL" + (suffix.isEmpty() ? "32" : suffix) + ".dll";
        for (Path directory : directories) {
            Path lwjgl = directory.resolve(lwjglName);
            Path openAl = directory.resolve(openAlName);
            if (Files.isRegularFile(lwjgl) && Files.isRegularFile(openAl)) {
                Set<Path> files = new java.util.LinkedHashSet<>();
                files.add(lwjgl);
                files.add(openAl);
                return files;
            }
        }
        return java.util.Collections.emptySet();
    }

    private static boolean is64BitJvm() {
        String dataModel = System.getProperty("sun.arch.data.model", "");
        if ("64".equals(dataModel)) return true;
        if ("32".equals(dataModel)) return false;

        String architecture = System.getProperty("os.arch", "").toLowerCase();
        return architecture.contains("64") || architecture.equals("amd64") || architecture.equals("aarch64");
    }

    private static boolean containsNonAscii(List<Path> paths) {
        for (Path path : paths) {
            if (containsNonAscii(path.toString())) return true;
        }
        return false;
    }

    private static boolean containsNonAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7f) return true;
        }
        return false;
    }

    private static Path createAsciiStagingDirectory() throws IOException {
        List<Path> roots = new ArrayList<>();
        addAsciiRoot(roots, System.getenv("TEMP"));
        addAsciiRoot(roots, System.getenv("TMP"));
        addAsciiRoot(roots, System.getProperty("java.io.tmpdir"));
        String systemRoot = System.getenv("SystemRoot");
        addAsciiRoot(roots, systemRoot == null ? null : Paths.get(systemRoot, "Temp").toString());
        roots.add(Paths.get("C:\\Temp"));

        IOException failure = null;
        String architectureDirectory = is64BitJvm() ? "openal-64" : "openal-32";
        for (Path root : roots) {
            try {
                Path stagingDirectory = root.resolve("FishModLoader").resolve(architectureDirectory);
                Files.createDirectories(stagingDirectory);
                if (!containsNonAscii(stagingDirectory.toString())) return stagingDirectory;
            } catch (IOException e) {
                failure = e;
            }
        }
        if (failure != null) throw failure;
        throw new IOException("No ASCII staging directory is available");
    }

    private static void addAsciiRoot(List<Path> roots, String value) {
        if (value == null || value.isEmpty() || containsNonAscii(value)) return;
        try {
            Path root = Paths.get(value).toAbsolutePath().normalize();
            if (!roots.contains(root)) roots.add(root);
        } catch (InvalidPathException ignored) {
            // Ignore one malformed optional temporary directory.
        }
    }
}

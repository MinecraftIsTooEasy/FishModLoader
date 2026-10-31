package net.xiaoyu233.fml.relaunch;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.discovery.ModResolutionException;
import net.xiaoyu233.fml.classloading.KnotClassLoaderInterface;
import net.xiaoyu233.fml.classloading.LibClassifier;
import net.xiaoyu233.fml.classloading.McLibrary;
import net.xiaoyu233.fml.util.LoaderUtil;
import net.xiaoyu233.fml.util.UrlUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

public class LaunchDelegate {
    //Here we transpose from the AppClassLoader into our own modifiable KnotClassLoader, so here is a barrier
    public static void delegateLaunch(String mainClass, String[] args, boolean server) throws ClassNotFoundException, NoSuchMethodException, IOException, ModResolutionException {
        checkAsmCanReadFml();
        List<Path> classPath = new ArrayList<>();
        List<String> missing = null;
        List<String> unsupported = null;
        List<Path> excluded = null;
        Path gameJarPath = findMinecraftPath(mainClass);
        Path fmlPath = findFmlPath();
        for (String cpEntry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (cpEntry.equals("*") || cpEntry.endsWith(File.separator + "*")) {
                if (unsupported == null) unsupported = new ArrayList<>();
                unsupported.add(cpEntry);
                continue;
            }

            Path path = Paths.get(cpEntry);

            if (!Files.exists(path)) {
                if (missing == null) missing = new ArrayList<>();
                missing.add(cpEntry);
                continue;
            }

            // LWJGL 2 is replaced by the FML compat classes (org.lwjgl.opengl/input/openal/Sys... on LWJGL 3 + Vulkan): launchers
            // still put the real LWJGL 2 core jar on the class path, it must not reach Knot. The FML jar itself contains
            // org/lwjgl/Sys.class and org/lwjgl/opengl/Display.class (the compat classes) and is never excluded.
            // lwjgl_util 2.x stays (pure Java, links against the compat GL11)
            if (!isSamePath(path, fmlPath) && isLwjgl2CoreJar(path)) {
                if (excluded == null) excluded = new ArrayList<>();
                excluded.add(path);
                continue;
            }

            if (!gameJarPath.equals(path.toAbsolutePath().normalize())) {
                classPath.add(LoaderUtil.normalizeExistingPath(path));
            }
        }

        if (excluded != null) {
            Logger logger = LogManager.getLogger("fishmodloader");
            for (Path path : excluded) {
                logger.info("Excluded LWJGL 2 core jar from the class loader (replaced by the FML compat classes): {}", path);
            }
        }

        KnotClassLoaderInterface modClassLoader = KnotClassLoaderInterface.create();
        //NO!!! You shouldn't do like this, should lock classpath for preinit, and unlock it later
        classPath.forEach(modClassLoader::addCodeSource);
        LaunchClassBlocker.blockClass(modClassLoader);
        modClassLoader.setValidParentClassPath(new LibClassifier<>(McLibrary.class, server ? EnvType.SERVER : EnvType.CLIENT).getSystemLibraries());
        Launch.launch(modClassLoader, mainClass, args, server, gameJarPath);
    }

    /**
     * Fails early, with an actionable message, when an older ASM shadows the ASM that is shaded into the FML jar. FML's classes are
     * Java 25 class files (major version 69), which ASM 9.3 (the version older installers wrote into the version json, ahead of
     * the FML library) cannot read: without this check the launch dies deep inside Mixin with a bare "Unsupported class file major
     * version 69". Never blocks the launch for any other reason.
     */
    private static void checkAsmCanReadFml() {
        try (InputStream in = LaunchDelegate.class.getResourceAsStream("LaunchDelegate.class")) {
            if (in != null) {
                new ClassReader(in);
            }
        } catch (IllegalArgumentException e) {
            String asm = "ASM";
            try {
                asm = "ASM jar " + ClassReader.class.getProtectionDomain().getCodeSource().getLocation();
            } catch (RuntimeException ignored) {
                // no code source information
            }
            throw new IllegalStateException("FishModLoader cannot start: the " + asm + " is too old to read FishModLoader's class files ("
                    + e.getMessage() + "). FishModLoader ships its own ASM, so no org.ow2.asm library may come before it on the class path:"
                    + " older FishModLoader installers wrote org.ow2.asm:*:9.3 into versions/1.6.4-MITE/1.6.4-MITE.json, run the current"
                    + " installer again or delete those library entries from the version json.", e);
        } catch (IOException | RuntimeException e) {
            // cannot tell: the launch goes on and fails later with the original error when ASM really cannot cope
        }
    }

    /**
     * True when the class path entry (a jar, or a classes directory in an IDE) contains org/lwjgl/Sys.class or
     * org/lwjgl/opengl/Display.class. Entries that cannot be inspected are kept (never throws).
     */
    private static boolean isLwjgl2CoreJar(Path path) {
        try {
            if (Files.isDirectory(path)) {
                return Files.isRegularFile(path.resolve("org/lwjgl/Sys.class")) || Files.isRegularFile(path.resolve("org/lwjgl/opengl/Display.class"));
            }
            try (ZipFile zipFile = new ZipFile(path.toFile())) {
                return zipFile.getEntry("org/lwjgl/Sys.class") != null || zipFile.getEntry("org/lwjgl/opengl/Display.class") != null;
            }
        } catch (IOException | RuntimeException e) {
            // not a zip file (ZipException), unreadable, ...: not a LWJGL 2 jar as far as we can tell
            LogManager.getLogger("fishmodloader").debug("Cannot inspect class path entry {} for LWJGL 2 classes: {}", path, e.toString());
            return false;
        }
    }

    /** The code source (jar or classes directory) of FML itself; null when it cannot be determined. */
    private static Path findFmlPath() {
        try {
            return UrlUtil.asPath(LaunchDelegate.class.getProtectionDomain().getCodeSource().getLocation()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isSamePath(Path path, Path other) {
        if (other == null) {
            return false;
        }
        if (path.toAbsolutePath().normalize().equals(other)) {
            return true;
        }
        try {
            return Files.isSameFile(path, other);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Path findMinecraftPath(String mainClass){
        String property = System.getProperty("minecraft.path");
        if (property != null) return Path.of(property);
        try {
            return UrlUtil.asPath(LaunchDelegate.class.getClassLoader().loadClass(mainClass).getProtectionDomain().getCodeSource().getLocation()).toAbsolutePath().normalize();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot find minecraft path due to missing main class file in classpath, nor env var of minecraft set");
        }
    }
}

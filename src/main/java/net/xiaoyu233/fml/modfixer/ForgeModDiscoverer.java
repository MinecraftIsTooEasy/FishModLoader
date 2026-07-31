package net.xiaoyu233.fml.modfixer;

import net.minecraft.launchwrapper.IClassTransformer;
import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.classloading.KnotClassDelegate;
import net.xiaoyu233.fml.classloading.KnotClassLoaderInterface;
import net.xiaoyu233.fml.relaunch.Launch;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Discovers Forge 1.6.4 mods sitting in the {@code mods/} directory.
 *
 * <p>A jar is considered a Forge mod if it contains {@code mcmod.info}, a
 * {@code @cpw.mods.fml.common.Mod} class, or a known Forge AT file. The user's
 * original jar is kept on the classpath; Forge SRG names are adapted when class
 * bytes are loaded.
 */
@Deprecated
public final class ForgeModDiscoverer {

    private static final List<DiscoveredForgeMod> discovered = new ArrayList<>();

    private ForgeModDiscoverer() {}

    public static List<DiscoveredForgeMod> getDiscovered() {
        return Collections.unmodifiableList(discovered);
    }

    public static void discoverIn(Path modsDir) {
        if (!Files.isDirectory(modsDir)) {
            FishModLoader.LOGGER.debug("Forge mod discovery: {} is not a directory, skipping", modsDir);
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : stream) {
                tryDiscover(jar);
            }
        } catch (IOException e) {
            FishModLoader.LOGGER.warn("Forge mod discovery failed walking {}", modsDir, e);
        }
    }

    private static void tryDiscover(Path jarPath) {
        boolean hasFabric;
        boolean hasMcModInfo;
        boolean hasAt;
        String corePluginClass;

        try (JarFile jf = new JarFile(jarPath.toFile())) {
            hasFabric = jf.getEntry("fabric.mod.json") != null
                    || jf.getEntry("fml.mod.json") != null;
            hasMcModInfo = jf.getEntry("mcmod.info") != null;
            hasAt = !ForgeAccessTransformerImporter.findLocations(jf).isEmpty();
            corePluginClass = jf.getManifest() != null
                    ? jf.getManifest().getMainAttributes().getValue("FMLCorePlugin")
                    : null;
        } catch (IOException e) {
            FishModLoader.LOGGER.warn("Could not inspect {}", jarPath, e);
            return;
        }

        if (hasFabric) {
            return;
        }

        List<LegacyModInfo> modAnns = LegacyModParser.scan(jarPath);
        boolean isForgeMod = hasMcModInfo || hasAt || !modAnns.isEmpty() || corePluginClass != null;
        if (!isForgeMod) return;

        List<McModInfoParser.Entry> mcInfo = McModInfoParser.read(jarPath);

        ForgeSrgModRemapper.registerForgeModJar(jarPath);

        DiscoveredForgeMod mod = new DiscoveredForgeMod(jarPath, modAnns, mcInfo);
        discovered.add(mod);

        try {
            Launch.knotLoader.addCodeSource(jarPath);
        } catch (Throwable t) {
            FishModLoader.LOGGER.warn("Could not add {} to class loader", jarPath, t);
        }

        if (hasAt) {
            ForgeAccessTransformerImporter.importFrom(jarPath);
        }

        if (corePluginClass != null) {
            registerCorePlugin(jarPath, corePluginClass);
        }

        FishModLoader.LOGGER.info("Discovered Forge mod: {} ({} @Mod class(es), {} mcmod.info entries{})",
                jarPath.getFileName(), modAnns.size(), mcInfo.size(),
                corePluginClass != null ? ", coremod=" + corePluginClass : "");
    }

    /**
     * Instantiate the FMLCorePlugin and register its {@link IClassTransformer}s with
     * {@link KnotClassDelegate}.  Must be called after the jar has been added to the
     * class loader's code source list.
     */
    private static void registerCorePlugin(Path jarPath, String corePluginClass) {
        KnotClassLoaderInterface knotLoader = Launch.knotLoader;
        ClassLoader classLoader = knotLoader.getClassLoader();
        try {
            Class<?> pluginClazz = Class.forName(corePluginClass, true, classLoader);
            Object plugin = pluginClazz.getDeclaredConstructor().newInstance();

            // IFMLLoadingPlugin.injectData — call if the plugin implements it
            try {
                pluginClazz.getMethod("injectData", java.util.Map.class).invoke(plugin, Launch.blackboard);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                FishModLoader.LOGGER.warn("FMLCorePlugin {} injectData failed: {}", corePluginClass, e.getMessage());
            }

            // Retrieve transformer class names
            String[] transformerClasses;
            try {
                transformerClasses = (String[]) pluginClazz
                        .getMethod("getASMTransformerClass")
                        .invoke(plugin);
            } catch (Exception e) {
                FishModLoader.LOGGER.warn("FMLCorePlugin {} has no getASMTransformerClass: {}",
                        corePluginClass, e.getMessage());
                return;
            }

            if (transformerClasses == null || transformerClasses.length == 0) return;

            // KnotClassLoaderInterface.create() returns a KnotClassDelegate instance
            KnotClassDelegate<?> delegate = (knotLoader instanceof KnotClassDelegate<?>)
                    ? (KnotClassDelegate<?>) knotLoader : null;

            for (String tClass : transformerClasses) {
                try {
                    Class<?> tClazz = Class.forName(tClass, true, classLoader);
                    IClassTransformer transformer = (IClassTransformer) tClazz
                            .getDeclaredConstructor().newInstance();
                    if (delegate != null) {
                        delegate.registerExternalTransformer(transformer);
                        FishModLoader.LOGGER.info("Registered FML transformer: {}", tClass);
                    } else {
                        FishModLoader.LOGGER.warn("Could not get KnotClassDelegate; transformer {} not registered", tClass);
                    }
                } catch (Exception e) {
                    FishModLoader.LOGGER.warn("Failed to instantiate FML transformer {}: {}",
                            tClass, e.getMessage());
                }
            }
        } catch (Exception e) {
            FishModLoader.LOGGER.warn("Failed to load FMLCorePlugin {}: {}", corePluginClass, e);
        }
    }

    /** Aggregate of everything known about a Forge mod jar at discovery time. */
    public static final class DiscoveredForgeMod {
        public final Path jarPath;
        public final List<LegacyModInfo> modAnnotations;
        public final List<McModInfoParser.Entry> mcModInfo;

        public DiscoveredForgeMod(Path jarPath,
                                  List<LegacyModInfo> modAnnotations,
                                  List<McModInfoParser.Entry> mcModInfo) {
            this.jarPath = jarPath;
            this.modAnnotations = Collections.unmodifiableList(modAnnotations);
            this.mcModInfo = Collections.unmodifiableList(mcModInfo);
        }

        public String getModId() {
            for (LegacyModInfo ann : modAnnotations) {
                String id = ann.getModId();
                if (id != null && !id.isEmpty()) return id;
            }
            for (McModInfoParser.Entry e : mcModInfo) {
                if (e.modid != null && !e.modid.isEmpty()) return e.modid;
            }
            return jarPath.getFileName().toString();
        }
    }
}

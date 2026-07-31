package net.xiaoyu233.fml.mapping;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.xiaoyu233.fml.util.Constants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class CachedMappedJar {
    private static final Logger LOGGER = LogManager.getLogger("GameRemapper");
    private final Path jarSource;
    private final Path cacheDir;
    private final TinyRemapper remapper;

    public CachedMappedJar(Path jarSource, IMappingProvider provider, File minecraftDir) throws IOException {
        this.jarSource = jarSource;
        TinyRemapper.Builder builder = TinyRemapper.newRemapper()
                .withMappings(provider)
                .ignoreConflicts(true)
                // The MITE 1.6.4 namespace splits classes that were originally
                // in the same package across multiple subpackages (Block →
                // net/minecraft/block, Material → net/minecraft/block/material,
                // etc.). MITE bytecode contains many cross-package accesses to
                // protected/package-private members that JVMs reject. Those
                // are widened at runtime via fishmodloader.accesswidener, but
                // TinyRemapper's checkPackageAccess pass runs before the AW is
                // applied, so it would refuse to map the jar. Disable the
                // check — incorrect mappings would still surface later as
                // IllegalAccessError at load time, which is exactly what AW
                // already covers.
                .checkPackageAccess(false);
        this.remapper = builder.build();
        this.cacheDir = minecraftDir.toPath().resolve(".fml").resolve("remappedJars");
        Files.createDirectories(cacheDir);
    }

    public Path ensureJarMapped() {
        // Include the source jar and mappings in the cache key. A version-only
        // key keeps stale remaps after either input changes, so runtime
        // transformations can be applied to obsolete bytecode.
        Path mappedJar = this.cacheDir.resolve(jarSource.getFileName() + "-" + Constants.VERSION
                + "-" + mappingFingerprint(jarSource) + ".jar");
        boolean injectionsInvalid = false;

        if (Files.exists(mappedJar) && !injectionsInvalid){
            LOGGER.info("Found mapped jar cache");
            return mappedJar;
        }else {
                LOGGER.info("Mapped jar cache not found, remapping with TinyRemapper on FML version " + Constants.VERSION);
            try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(mappedJar).build()) {
                outputConsumer.addNonClassFiles(this.jarSource, NonClassCopyMode.UNCHANGED, remapper);
                this.remapper.readInputs(this.jarSource);
                remapper.apply(outputConsumer);
                LOGGER.info("Minecraft jar has successfully remapped");
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                remapper.finish();
            }
        }
        return mappedJar;
    }

    private static String mappingFingerprint(Path source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, Files.newInputStream(source));
            java.io.InputStream mappings = CachedMappedJar.class.getResourceAsStream("/intermediary.tiny");
            if (mappings == null) {
                throw new IOException("Missing /intermediary.tiny");
            }
            updateDigest(digest, mappings);
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                result.append(String.format("%02x", hash[i]));
            }
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot fingerprint game jar and mappings", e);
        }
    }

    private static void updateDigest(MessageDigest digest, java.io.InputStream input) throws IOException {
        try (java.io.InputStream in = input) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
        }
    }
}

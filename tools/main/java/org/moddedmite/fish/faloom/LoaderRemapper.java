package org.moddedmite.fish.faloom;

import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class LoaderRemapper {

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Usage: LoaderRemapper <inputJar> <mappingsTiny> <outputJar> [mappingToEmbed]");
            System.exit(1);
        }

        Path inputJar = Path.of(args[0]);
        Path mappingsFile = Path.of(args[1]);
        Path outputJar = Path.of(args[2]);
        Path mappingToEmbed = args.length >= 4 ? Path.of(args[3]) : null;

        if (!Files.exists(inputJar)) {
            System.err.println("Input jar not found: " + inputJar);
            System.exit(1);
        }
        if (!Files.exists(mappingsFile)) {
            System.err.println("Mapping file not found: " + mappingsFile);
            System.exit(1);
        }
        if (mappingToEmbed != null && !Files.exists(mappingToEmbed)) {
            System.err.println("Mapping to embed not found: " + mappingToEmbed);
            System.exit(1);
        }

        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(
                        Files.newBufferedReader(mappingsFile), "named", "intermediary"))
                .ignoreConflicts(true)
                .build();

        try {
            try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputJar).build()) {
                outputConsumer.addNonClassFiles(inputJar, NonClassCopyMode.UNCHANGED, remapper);
                remapper.readInputs(inputJar);
                remapper.apply(outputConsumer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to remap loader jar", e);
        } finally {
            remapper.finish();
        }

        if (mappingToEmbed != null) {
            embedMapping(outputJar, mappingToEmbed, "named2intermediary.tiny");
        }
    }

    private static void embedMapping(Path jarPath, Path mappingFile, String resourceName) throws IOException {
        byte[] mappingBytes = Files.readAllBytes(mappingFile);
        Path tempJar = jarPath.resolveSibling(jarPath.getFileName() + ".tmp");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tempJar));
             java.util.jar.JarInputStream jis = new java.util.jar.JarInputStream(Files.newInputStream(jarPath))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (entry.getName().equals(resourceName)) {
                    continue;
                }
                jos.putNextEntry(new JarEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    jis.transferTo(jos);
                }
                jos.closeEntry();
            }

            JarEntry mappingEntry = new JarEntry(resourceName);
            jos.putNextEntry(mappingEntry);
            jos.write(mappingBytes);
            jos.closeEntry();
        }

        Files.delete(jarPath);
        Files.move(tempJar, jarPath);
    }
}

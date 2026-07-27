package org.moddedmite.fish.faloom;

import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class RemapToIntermediary {
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: RemapToIntermediary <input.jar> <output.jar> <intermediary.tiny>");
            System.exit(1);
        }
        Path inputJar = Paths.get(args[0]);
        Path outputJar = Paths.get(args[1]);
        Path mappingsFile = Paths.get(args[2]);

        System.out.println("Remapping " + inputJar + " -> " + outputJar);
        System.out.println("  Source namespace: official");
        System.out.println("  Target namespace: intermediary");
        System.out.println("  Mappings: " + mappingsFile);

        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(
                        Files.newBufferedReader(mappingsFile), "official", "intermediary"))
                .ignoreConflicts(true)
                .threads(Runtime.getRuntime().availableProcessors())
                .build();

        try {
            try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputJar).build()) {
                outputConsumer.addNonClassFiles(inputJar, NonClassCopyMode.UNCHANGED, remapper);
                remapper.readInputs(inputJar);
                remapper.apply(outputConsumer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to remap jar", e);
        } finally {
            remapper.finish();
        }

        System.out.println("Done. Remapped jar written to " + outputJar);
    }
}

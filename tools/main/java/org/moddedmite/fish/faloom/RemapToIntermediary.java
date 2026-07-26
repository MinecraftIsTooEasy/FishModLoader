package org.moddedmite.fish.faloom;

import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remaps the raw (official/obfuscated namespace) MITE game jar into the
 * {@code intermediary} namespace that FishModLoader's sources are written
 * against.
 *
 * <p>The bundled {@code intermediary.tiny} is a tiny v1 file declaring
 * {@code official} then {@code intermediary}, so the remap direction is
 * official -> intermediary.
 *
 * <p>Usage: {@code RemapToIntermediary <inputJar> <outputJar> <intermediary.tiny>}
 */
public final class RemapToIntermediary {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: RemapToIntermediary <inputJar> <outputJar> <intermediaryTiny>");
            System.exit(1);
        }

        Path inputJar = Path.of(args[0]);
        Path outputJar = Path.of(args[1]);
        Path mappingsFile = Path.of(args[2]);

        if (!Files.exists(inputJar)) {
            System.err.println("Input jar not found: " + inputJar);
            System.exit(1);
        }
        if (!Files.exists(mappingsFile)) {
            System.err.println("Mapping file not found: " + mappingsFile);
            System.exit(1);
        }

        if (outputJar.getParent() != null) {
            Files.createDirectories(outputJar.getParent());
        }
        Files.deleteIfExists(outputJar);

        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(
                        Files.newBufferedReader(mappingsFile), "official", "intermediary"))
                .ignoreConflicts(true)
                .fixPackageAccess(true)
                .rebuildSourceFilenames(true)
                .build();

        try {
            try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputJar).build()) {
                outputConsumer.addNonClassFiles(inputJar, NonClassCopyMode.UNCHANGED, remapper);
                remapper.readInputs(inputJar);
                remapper.apply(outputConsumer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to remap MITE jar to intermediary", e);
        } finally {
            remapper.finish();
        }

        System.out.println("Remapped " + inputJar.getFileName()
                + " (official) -> " + outputJar + " (intermediary)");
    }
}

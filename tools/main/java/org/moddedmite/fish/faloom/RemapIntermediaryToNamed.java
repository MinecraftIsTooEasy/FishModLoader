package org.moddedmite.fish.faloom;

import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Remaps the intermediary-namespace MITE jar into the {@code named} namespace.
 *
 * <p>FishModLoader's own sources (including every forge_compat mixin) and
 * {@code fishmodloader.accesswidener} are written against <em>named</em> names
 * ({@code blockID}, {@code canBlockStay}, ...), so the jar placed on the
 * compile classpath must be in the named namespace too.
 *
 * <p>The bundled {@code named.tiny} is tiny v2 declaring
 * {@code intermediary} then {@code named}, so the remap direction here is
 * intermediary -> named. Combined with {@link RemapToIntermediary} the full
 * chain is: official -> intermediary -> named.
 *
 * <p>Usage: {@code RemapIntermediaryToNamed <inputJar> <outputJar> <named.tiny>}
 */
public final class RemapIntermediaryToNamed {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: RemapIntermediaryToNamed <inputJar> <outputJar> <namedTiny>");
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

        // named.tiny is stale vanilla 1.6.4 metadata; a few entries collide with
        // members MITE already declares under the target name. Those are
        // unfixable conflicts for tiny-remapper and would fail the whole build,
        // so drop them -- this jar is compile-only scaffolding, and the affected
        // members keep their intermediary names.
        Path filtered = sanitizeMappings(mappingsFile, outputJar);

        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(
                        Files.newBufferedReader(filtered), "intermediary", "named"))
                .ignoreConflicts(true)
                // named.tiny stores *official* field descriptors (e.g. [Laqz;)
                // while the input jar is already intermediary
                // ([Lnet/minecraft/block/Block;). Without this, descriptor
                // mismatches silently skip fields such as
                // Block.field_71973_m -> blocksList, and sources referencing
                // the named form fail to compile.
                //
                // Measured on the full compile: 2230 errors without this flag
                // vs 1094 with it. It does mis-map a few same-named fields
                // (descriptors are what disambiguate them), but the net effect
                // is strongly positive, so keep it enabled.
                .ignoreFieldDesc(true)
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
            throw new RuntimeException("Failed to remap MITE jar to named", e);
        } finally {
            remapper.finish();
        }

        System.out.println("Remapped " + inputJar.getFileName()
                + " (intermediary) -> " + outputJar + " (named)");
    }

    /** intermediary-name entries whose named target collides with an existing MITE member. */
    private static final List<String> DROP_INTERMEDIARY_NAMES = List.of(
            "field_71322_p"   // -> playersOnline, but MITE already has playersOnline
    );

    /**
     * Copy the tiny v2 mapping file, omitting member lines whose intermediary
     * name is in {@link #DROP_INTERMEDIARY_NAMES}.
     */
    private static Path sanitizeMappings(Path mappingsFile, Path outputJar) throws IOException {
        Path out = outputJar.resolveSibling("named-sanitized.tiny");
        List<String> kept = new ArrayList<>();
        int dropped = 0;

        try (BufferedReader r = Files.newBufferedReader(mappingsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t");
                // member lines look like: <tab> f|m <tab> desc <tab> srcName <tab> dstName
                boolean drop = false;
                if (parts.length >= 5 && (parts[1].equals("f") || parts[1].equals("m"))) {
                    if (DROP_INTERMEDIARY_NAMES.contains(parts[3])) {
                        drop = true;
                    }
                }
                if (drop) {
                    dropped++;
                } else {
                    kept.add(line);
                }
            }
        }

        Files.write(out, kept, StandardCharsets.UTF_8);
        if (dropped > 0) {
            System.out.println("Dropped " + dropped
                    + " conflicting mapping entry/entries: " + DROP_INTERMEDIARY_NAMES);
        }
        return out;
    }
}

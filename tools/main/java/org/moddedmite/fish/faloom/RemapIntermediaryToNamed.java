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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Usage: {@code RemapIntermediaryToNamed <inputJar> <outputJar> <named.tiny> [intermediary.tiny]}
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
        // Optional: official->intermediary mappings, used to translate the
        // descriptors stored inside named.tiny (see sanitizeMappings).
        Path officialToIntermediary = args.length >= 4 ? Path.of(args[3]) : null;

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
        Path filtered = sanitizeMappings(mappingsFile, outputJar, officialToIntermediary);

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
    /**
     * Copy the tiny v2 mapping file, omitting member lines whose intermediary
     * name is in {@link #DROP_INTERMEDIARY_NAMES}, and (when
     * {@code officialToIntermediary} is supplied) rewriting member descriptors
     * from the official namespace into intermediary.
     *
     * <p>named.tiny stores descriptors in the <em>official</em> namespace
     * (e.g. {@code (Lavi;Ljava/lang/String;III)V}) even though its source
     * namespace is intermediary. tiny-remapper matches members by
     * name + descriptor, so without this translation almost every method
     * mapping silently fails to apply and the output jar keeps SRG names
     * like {@code func_73732_a}.
     */
    private static Path sanitizeMappings(Path mappingsFile, Path outputJar,
                                         Path officialToIntermediary) throws IOException {
        Map<String, String> officialToInter = officialToIntermediary == null
                ? Map.of()
                : readOfficialToIntermediaryClasses(officialToIntermediary);

        Path out = outputJar.resolveSibling("named-sanitized.tiny");
        List<String> kept = new ArrayList<>();
        int dropped = 0;
        int rewritten = 0;

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
                    continue;
                }

                // Translate official descriptors -> intermediary so that
                // tiny-remapper's name+descriptor matching actually succeeds.
                if (!officialToInter.isEmpty()
                        && parts.length >= 5
                        && (parts[1].equals("f") || parts[1].equals("m"))) {
                    String translated = translateDescriptor(parts[2], officialToInter);
                    if (!translated.equals(parts[2])) {
                        parts[2] = translated;
                        line = String.join("\t", parts);
                        rewritten++;
                    }
                }

                kept.add(line);
            }
        }

        Files.write(out, kept, StandardCharsets.UTF_8);
        if (dropped > 0) {
            System.out.println("Dropped " + dropped
                    + " conflicting mapping entry/entries: " + DROP_INTERMEDIARY_NAMES);
        }
        if (rewritten > 0) {
            System.out.println("Translated " + rewritten
                    + " member descriptors from official to intermediary");
        }
        return out;
    }

    /** Read {@code CLASS <official> <intermediary>} lines from a tiny v1 file. */
    private static Map<String, String> readOfficialToIntermediaryClasses(Path tinyV1)
            throws IOException {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(tinyV1, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("CLASS\t")) continue;
                String[] parts = line.split("\t");
                if (parts.length >= 3) map.put(parts[1], parts[2]);
            }
        }
        return map;
    }

    /** Rewrite every {@code L<class>;} in a descriptor using the given class map. */
    private static String translateDescriptor(String desc, Map<String, String> classMap) {
        if (desc == null || desc.indexOf('L') < 0) return desc;

        StringBuilder outDesc = new StringBuilder(desc.length());
        int pos = 0;
        while (pos < desc.length()) {
            char c = desc.charAt(pos);
            if (c != 'L') {
                outDesc.append(c);
                pos++;
                continue;
            }
            int end = desc.indexOf(';', pos);
            if (end < 0) {
                outDesc.append(desc, pos, desc.length());
                break;
            }
            String internal = desc.substring(pos + 1, end);
            outDesc.append('L').append(classMap.getOrDefault(internal, internal)).append(';');
            pos = end + 1;
        }
        return outDesc.toString();
    }
}

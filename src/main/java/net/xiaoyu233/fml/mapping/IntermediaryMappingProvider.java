package net.xiaoyu233.fml.mapping;

import net.fabricmc.tinyremapper.IMappingProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factory for creating an {@link IMappingProvider} that remaps the game jar
 * from the {@code named} (MITE-readable) namespace to the {@code intermediary}
 * (SRG) namespace at runtime.
 *
 * <p>FishModLoader runs the game jar in the {@code intermediary} namespace so
 * that Forge mods (distributed with SRG names like {@code func_71276_C}) find
 * their expected methods without runtime remapping. This class reverses the
 * {@code named.tiny} mapping file ({@code intermediary → named}) to
 * produce a {@code named → intermediary} mapping for {@link CachedMappedJar}.
 */
public final class IntermediaryMappingProvider {

    private static final Pattern CLASS_REF_PATTERN = Pattern.compile("L([^;]+);");

    private IntermediaryMappingProvider() {
    }

    /**
     * Create an {@link IMappingProvider} that remaps the game jar from
     * {@code named} → {@code intermediary} by reversing
     * {@code named.tiny}.
     */
    public static IMappingProvider create() throws IOException {
        // ── Step 1: load all three tiny mapping files ────────────────────────
        InputStream namedResource = IntermediaryMappingProvider.class.getResourceAsStream("/named.tiny");
        if (namedResource == null) {
            throw new IOException("Missing classpath resource: /named.tiny — needed to remap game jar from named → intermediary");
        }

        // mappings.tiny (official → named) resolves notch-style class names
        // (e.g. "yc" → "net/minecraft/item/Item") that appear in named.tiny
        // descriptors.  Without this, descriptors like "[Lyc;" stay as "[Lyc;"
        // and TinyRemapper cannot match them against game jar fields that use
        // the fully-qualified named paths ("[Lnet/minecraft/item/Item;").
        InputStream mappingsResource = IntermediaryMappingProvider.class.getResourceAsStream("/mappings.tiny");
        if (mappingsResource == null) {
            throw new IOException("Missing classpath resource: /mappings.tiny — needed to resolve notch names in descriptors");
        }

        // Build class maps first so they are available for descriptor transformation
        // during member parsing.

        // named.tiny (intermediary → named): class mappings
        Map<String, String> namedToInterClass = new LinkedHashMap<>();  // named → intermediary
        Map<String, String> interToNamedClass = new LinkedHashMap<>();  // intermediary → named

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(namedResource, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("c\t")) {
                    String[] parts = line.split("\t", 3);
                    if (parts.length >= 3) {
                        namedToInterClass.put(parts[2], parts[1]);
                        interToNamedClass.put(parts[1], parts[2]);
                    }
                }
            }
        }

        // mappings.tiny (official → named): class mappings
        Map<String, String> officialToNamed = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(mappingsResource, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("c\t")) {
                    String[] parts = line.split("\t", 3);
                    if (parts.length >= 3) {
                        officialToNamed.put(parts[1], parts[2]);
                    }
                }
            }
        }
        mappingsResource.close();

        // ── Step 2: re-read named.tiny to collect member entries ────────────
        // This time we transform descriptors using both lookup maps so that
        // notch-style class refs in descriptors are resolved to named paths.
        namedResource.close();
        namedResource = IntermediaryMappingProvider.class.getResourceAsStream("/named.tiny");
        if (namedResource == null) throw new IOException("Cannot re-read named.tiny");

        Map<String, List<String[]>> methodsByNamedClass = new LinkedHashMap<>();
        Map<String, List<String[]>> fieldsByNamedClass = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(namedResource, StandardCharsets.UTF_8))) {
            String line;
            String currentNamedClass = null;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("tiny\t")) continue;

                if (line.startsWith("c\t")) {
                    String[] parts = line.split("\t", 3);
                    if (parts.length >= 3) currentNamedClass = parts[2];
                } else if (line.startsWith("\t") && currentNamedClass != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    String[] parts = trimmed.split("\t", 4);
                    if (parts.length < 4) continue;

                    String type = parts[0];
                    String desc = parts[1];
                    String interName = parts[2];
                    String namedName = parts[3];

                    // Transform descriptor using BOTH intermediary→named and official→named
                    String namedDesc = transformDescriptorWithFallback(desc, interToNamedClass, officialToNamed);

                    if ("m".equals(type)) {
                        methodsByNamedClass
                                .computeIfAbsent(currentNamedClass, k -> new ArrayList<>())
                                .add(new String[]{namedName, interName, namedDesc});
                    } else if ("f".equals(type)) {
                        fieldsByNamedClass
                                .computeIfAbsent(currentNamedClass, k -> new ArrayList<>())
                                .add(new String[]{namedName, interName, namedDesc});
                    }
                }
            }
        }

        return (IMappingProvider.MappingAcceptor acceptor) -> {
            // Visit class mappings (named → intermediary)
            for (Map.Entry<String, String> e : namedToInterClass.entrySet()) {
                acceptor.acceptClass(e.getKey(), e.getValue());
            }

            // Visit method mappings
            for (Map.Entry<String, List<String[]>> e : methodsByNamedClass.entrySet()) {
                String namedOwner = e.getKey();
                for (String[] m : e.getValue()) {
                    String namedName = m[0];  // source method name
                    String interName = m[1];  // destination method name
                    String namedDesc = m[2];  // source descriptor (named namespace)
                    acceptor.acceptMethod(
                            new IMappingProvider.Member(namedOwner, namedName, namedDesc),
                            interName);
                }
            }

            // Visit field mappings
            for (Map.Entry<String, List<String[]>> e : fieldsByNamedClass.entrySet()) {
                String namedOwner = e.getKey();
                for (String[] f : e.getValue()) {
                    String namedName = f[0];  // source field name
                    String interName = f[1];  // destination field name
                    String namedDesc = f[2];  // source descriptor (named namespace)
                    acceptor.acceptField(
                            new IMappingProvider.Member(namedOwner, namedName, namedDesc),
                            interName);
                }
            }
        };
    }

    /**
     * Transform a descriptor by looking up class references in TWO maps:
     * <ol>
     *   <li>{@code interToNamed} — intermediary (SRG) → named</li>
     *   <li>{@code officialToNamed} — official (notch) → named</li>
     * </ol>
     *
     * <p>{@code named.tiny} descriptors contain a mix of intermediary names
     * (e.g. {@code Lnet/minecraft/server/MinecraftServer;}) and notch names
     * (e.g. {@code Lye;} for ItemStack).  Trying only the intermediary map
     * would fail for notch references, and trying only the official map would
     * fail for SRG references — so we try both.
     */
    static String transformDescriptorWithFallback(
            String desc,
            Map<String, String> interToNamed,
            Map<String, String> officialToNamed) {
        if (desc == null || desc.isEmpty()) return desc;

        StringBuilder result = new StringBuilder(desc.length() + 64);
        Matcher m = CLASS_REF_PATTERN.matcher(desc);
        int lastEnd = 0;

        while (m.find()) {
            result.append(desc, lastEnd, m.start());

            String className = m.group(1);
            String named = interToNamed.get(className);
            if (named == null) {
                named = officialToNamed.get(className);
            }
            if (named != null) {
                result.append('L').append(named).append(';');
            } else {
                result.append(m.group()); // keep original
            }

            lastEnd = m.end();
        }

        result.append(desc.substring(lastEnd));
        return result.toString();
    }
}

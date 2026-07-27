package org.moddedmite.fish.faloom;

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
 * Rewrites an accessWidener file from the {@code named} namespace into
 * {@code intermediary}.
 *
 * <p>{@code fishmodloader.accesswidener} is authored against named names
 * ({@code EnumGameType.id}) but the game jar is remapped to intermediary at
 * launch ({@code EnumGameType.field_77154_e}). AccessWidener matches members by
 * name, so a named-namespace file silently widens nothing: the class is visited
 * (class names are identical in both namespaces) yet no field or method matches,
 * and MITE's many cross-package accesses then blow up at runtime with
 * {@code IllegalAccessError}.
 *
 * <p>Usage: {@code RemapAccessWidener <inputAw> <outputAw> <named2intermediary.tiny>}
 */
public final class RemapAccessWidener {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: RemapAccessWidener <inputAw> <outputAw> <named2intermediaryTiny>");
            System.exit(1);
        }

        Path inputAw = Path.of(args[0]);
        Path outputAw = Path.of(args[1]);
        Path mappingsFile = Path.of(args[2]);

        for (Path required : new Path[]{inputAw, mappingsFile}) {
            if (!Files.exists(required)) {
                System.err.println("Not found: " + required);
                System.exit(1);
            }
        }

        // class -> (memberName -> intermediaryName), split by member kind so a
        // field and a method sharing a name cannot collide.
        Map<String, Map<String, String>> fields = new HashMap<>();
        Map<String, Map<String, String>> methods = new HashMap<>();
        readMappings(mappingsFile, fields, methods);

        List<String> out = new ArrayList<>();
        int rewritten = 0;

        try (BufferedReader r = Files.newBufferedReader(inputAw, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) {
                    first = false;
                    // header: accessWidener v2 named  ->  ... intermediary
                    out.add(line.trim().replaceAll("\\s+named\\s*$", "\tintermediary"));
                    continue;
                }

                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    out.add(line);
                    continue;
                }

                // <access> <kind> <class> [<member> <desc>]
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 5 && (parts[1].equals("field") || parts[1].equals("method"))) {
                    String owner = parts[2];
                    String member = parts[3];
                    Map<String, String> table =
                            parts[1].equals("field") ? fields.getOrDefault(owner, Map.of())
                                                     : methods.getOrDefault(owner, Map.of());
                    String mapped = table.get(member);
                    if (mapped != null && !mapped.equals(member)) {
                        parts[3] = mapped;
                        out.add(String.join("\t", parts));
                        rewritten++;
                        continue;
                    }
                }
                out.add(line);
            }
        }

        if (outputAw.getParent() != null) {
            Files.createDirectories(outputAw.getParent());
        }
        Files.write(outputAw, out, StandardCharsets.UTF_8);
        System.out.println("Remapped accessWidener: " + rewritten
                + " member entries named -> intermediary (" + outputAw + ")");
    }

    /** Read a tiny v2 {@code named -> intermediary} file into per-class member tables. */
    private static void readMappings(Path tinyV2,
                                     Map<String, Map<String, String>> fields,
                                     Map<String, Map<String, String>> methods) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(tinyV2, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null || !header.startsWith("tiny\t")) {
                throw new IOException("Not a tiny file: " + tinyV2);
            }

            String currentClass = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == 'c') {
                    String[] parts = line.split("\t");
                    currentClass = parts.length >= 2 ? parts[1] : null;
                } else if (currentClass != null && line.startsWith("\t")) {
                    String[] parts = line.split("\t");
                    // ["", kind, desc, srcName, dstName]
                    if (parts.length >= 5) {
                        String kind = parts[1];
                        String src = parts[3];
                        String dst = parts[4];
                        if ("f".equals(kind)) {
                            fields.computeIfAbsent(currentClass, k -> new HashMap<>()).put(src, dst);
                        } else if ("m".equals(kind)) {
                            methods.computeIfAbsent(currentClass, k -> new HashMap<>()).put(src, dst);
                        }
                    }
                }
            }
        }
    }
}

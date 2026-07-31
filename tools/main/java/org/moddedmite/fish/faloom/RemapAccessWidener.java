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
 * <p>Usage: {@code RemapAccessWidener <inputAw> <outputAw> <named2intermediary.tiny> [namedJar]}
 *
 * <p>The optional {@code namedJar} supplies the class hierarchy so that an
 * entry naming a subclass as owner can still be resolved against the
 * superclass that actually declares the member -- see {@link #resolveMember}.
 */
public final class RemapAccessWidener {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: RemapAccessWidener <inputAw> <outputAw> <named2intermediaryTiny> [namedJar]");
            System.exit(1);
        }

        Path inputAw = Path.of(args[0]);
        Path outputAw = Path.of(args[1]);
        Path mappingsFile = Path.of(args[2]);
        Path namedJar = args.length >= 4 ? Path.of(args[3]) : null;

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

        // owner -> direct supertypes (superclass first, then interfaces)
        Map<String, List<String>> supers = namedJar != null && Files.exists(namedJar)
                ? readHierarchy(namedJar)
                : Map.of();
        if (supers.isEmpty() && namedJar != null) {
            System.out.println("WARNING: no class hierarchy available (" + namedJar
                    + "); AW entries whose owner does not itself declare the member cannot be remapped");
        }

        List<String> out = new ArrayList<>();
        int rewritten = 0;
        int viaSupertype = 0;
        List<String> unresolved = new ArrayList<>();

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
                    String descriptor = parts[4];
                    Map<String, Map<String, String>> tables =
                            parts[1].equals("field") ? fields : methods;

                    Resolved resolved = resolveMember(owner, member, descriptor, tables, supers);
                    if (resolved != null && !resolved.mapped.equals(member)) {
                        parts[3] = resolved.mapped;
                        out.add(String.join("\t", parts));
                        rewritten++;
                        if (resolved.fromSupertype) viaSupertype++;
                        continue;
                    }
                    if (resolved == null) {
                        // Neither the owner nor any supertype maps this member. The
                        // entry ships in named form and will silently widen nothing
                        // at runtime, which is how EntityMob.canDespawn stayed a
                        // no-op and let a VerifyError through.
                        unresolved.add(parts[1] + " " + owner + " " + member);
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
                + " member entries named -> intermediary (" + viaSupertype
                + " via supertype) (" + outputAw + ")");
        if (!unresolved.isEmpty()) {
            // Not fatal: many MITE-only members are spelled identically in both
            // namespaces, so passing through unchanged is correct for them.
            System.out.println("NOTE: " + unresolved.size()
                    + " entries had no mapping in owner or supertypes (kept verbatim):");
            for (String entry : unresolved) {
                System.out.println("  " + entry);
            }
        }
    }

    /** A member name resolved through the hierarchy. */
    private static final class Resolved {
        final String mapped;
        final boolean fromSupertype;

        Resolved(String mapped, boolean fromSupertype) {
            this.mapped = mapped;
            this.fromSupertype = fromSupertype;
        }
    }

    /**
     * Look up {@code member} on {@code owner}, then walk the supertype chain.
     *
     * <p>An AW entry may legitimately name a subclass as owner while the
     * mappings only carry the member under the declaring superclass: MITE
     * overrides methods that vanilla declares higher up, and {@code named.tiny}
     * is vanilla-derived. Owner-exact lookup alone therefore misses them and
     * the entry silently degrades to a no-op.
     */
    private static Resolved resolveMember(String owner,
                                          String member,
                                          String descriptor,
                                          Map<String, Map<String, String>> tables,
                                          Map<String, List<String>> supers) {
        String memberKey = memberKey(member, descriptor);
        String direct = tables.getOrDefault(owner, Map.of()).get(memberKey);
        if (direct != null) {
            return new Resolved(direct, false);
        }

        // Breadth-first over supertypes; guard against cycles in malformed input.
        List<String> queue = new ArrayList<>(supers.getOrDefault(owner, List.of()));
        java.util.Set<String> seen = new java.util.HashSet<>(queue);
        for (int i = 0; i < queue.size(); i++) {
            String superName = queue.get(i);
            String mapped = tables.getOrDefault(superName, Map.of()).get(memberKey);
            if (mapped != null) {
                return new Resolved(mapped, true);
            }
            for (String next : supers.getOrDefault(superName, List.of())) {
                if (seen.add(next)) queue.add(next);
            }
        }
        return null;
    }

    /** Read {@code owner -> direct supertypes} from a jar, superclass first. */
    private static Map<String, List<String>> readHierarchy(Path jar) throws IOException {
        Map<String, List<String>> supers = new HashMap<>();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                try (java.io.InputStream in = jf.getInputStream(entry)) {
                    org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(in);
                    List<String> parents = new ArrayList<>();
                    if (reader.getSuperName() != null) parents.add(reader.getSuperName());
                    String[] interfaces = reader.getInterfaces();
                    if (interfaces != null) {
                        for (String iface : interfaces) parents.add(iface);
                    }
                    supers.put(reader.getClassName(), parents);
                } catch (Exception ignored) {
                    // A single unreadable class must not abort the whole build step.
                }
            }
        }
        return supers;
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
                            fields.computeIfAbsent(currentClass, k -> new HashMap<>()).put(memberKey(src, parts[2]), dst);
                        } else if ("m".equals(kind)) {
                            methods.computeIfAbsent(currentClass, k -> new HashMap<>()).put(memberKey(src, parts[2]), dst);
                        }
                    }
                }
            }
        }
    }

    /** Members are overloaded; a name-only lookup can remap a MITE-added overload using a vanilla descriptor. */
    private static String memberKey(String name, String descriptor) {
        return name + '\u0000' + descriptor;
    }
}

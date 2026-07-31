package net.xiaoyu233.fml.modfixer;

import net.xiaoyu233.fml.FishModLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/** Loads legacy Forge access-transformer rules and applies them in their runtime namespace. */
@Deprecated
public final class ForgeAccessTransformerImporter {
    public static final String[] LOCATIONS = {"META-INF/forge_at.cfg", "META-INF/fml_at.cfg", "META-INF/at.cfg"};
    private static final Map<String, List<Rule>> RULES = new HashMap<>();
    private static final Set<String> LOADED_SOURCES = new HashSet<>();

    private ForgeAccessTransformerImporter() {}

    /** Returns all AT config entries advertised by a mod jar, without duplicates. */
    public static List<String> findLocations(JarFile jar) throws IOException {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Attributes attributes = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes();
        if (attributes != null) {
            String fmlAt = attributes.getValue("FMLAT");
            if (fmlAt != null) {
                for (String value : fmlAt.split("[ ,]+")) {
                    if (!value.isEmpty()) found.add(normalizeLocation(value));
                }
            }
        }
        Collections.addAll(found, LOCATIONS);
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith("META-INF/") && name.endsWith("_at.cfg")) found.add(name);
        }
        found.removeIf(name -> jar.getEntry(name) == null);
        return new ArrayList<>(found);
    }

    public static boolean hasAccessTransform(String className) {
        return RULES.containsKey(normalizeOwner(className));
    }

    public static void importFrom(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String loc : findLocations(jar)) {
                String source = jarPath.toAbsolutePath().normalize() + "!/" + loc;
                synchronized (RULES) {
                    if (!LOADED_SOURCES.add(source)) continue;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(jar.getEntry(loc)), StandardCharsets.UTF_8))) {
                    int count = importStream(reader, source);
                    FishModLoader.LOGGER.info("Loaded {} Forge AT rules from {}", count, source);
                }
            }
        } catch (IOException e) {
            FishModLoader.LOGGER.warn("Could not read Forge AT from {}", jarPath, e);
        }
    }

    /** Test/probe entry point. Rules are still deduplicated by source and textual identity. */
    public static int importString(String content, String sourceLabel) {
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            return importStream(reader, sourceLabel);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static int importStream(BufferedReader reader, String source) throws IOException {
        int count = 0;
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            String clean = stripComment(line).trim();
            if (clean.isEmpty()) continue;
            Rule rule;
            try {
                rule = Rule.parse(clean, source, lineNumber);
            } catch (RuntimeException malformed) {
                rule = null;
            }
            if (rule == null) {
                FishModLoader.LOGGER.warn("Unresolved Forge AT rule at {}:{}: {}", source, lineNumber, clean);
                continue;
            }
            synchronized (RULES) {
                List<Rule> ownerRules = RULES.computeIfAbsent(rule.owner, key -> new ArrayList<>());
                if (!ownerRules.contains(rule)) {
                    ownerRules.add(rule);
                    count++;
                }
            }
        }
        return count;
    }

    /** Applies all rules for the node and warns for each member rule that did not resolve. */
    public static int apply(String runtimeName, ClassNode node) {
        List<Rule> rules;
        synchronized (RULES) {
            List<Rule> registered = RULES.get(normalizeOwner(runtimeName));
            if (registered == null) return 0;
            rules = new ArrayList<>(registered);
        }
        int changed = 0;
        for (Rule rule : rules) {
            boolean resolved = false;
            if (rule.kind == Kind.CLASS) {
                node.access = fixedAccess(node.access, rule);
                resolved = true;
            } else if (rule.kind == Kind.FIELD) {
                for (FieldNode field : node.fields) {
                    if (field.name.equals(rule.name) || "*".equals(rule.name)) {
                        field.access = fixedAccess(field.access, rule);
                        resolved = true;
                        if (!"*".equals(rule.name)) break;
                    }
                }
            } else {
                for (MethodNode method : node.methods) {
                    if ((method.name.equals(rule.name) && method.desc.equals(rule.descriptor)) || "*".equals(rule.name)) {
                        method.access = fixedAccess(method.access, rule);
                        resolved = true;
                        if (!"*".equals(rule.name)) break;
                    }
                }
            }
            if (resolved) changed++;
            else FishModLoader.LOGGER.warn("Unresolved Forge AT target {} ({}:{})", rule.targetText, rule.source, rule.line);
        }
        return changed;
    }

    /** Same visibility lattice and final handling as Forge 1.6.4 AccessTransformer#getFixedAccess. */
    private static int fixedAccess(int access, Rule target) {
        int requested = target.access;
        int result = access & ~7;
        switch (access & 7) {
            case Opcodes.ACC_PRIVATE: result |= requested; break;
            case 0: result |= requested != Opcodes.ACC_PRIVATE ? requested : 0; break;
            case Opcodes.ACC_PROTECTED: result |= requested != Opcodes.ACC_PRIVATE && requested != 0 ? requested : Opcodes.ACC_PROTECTED; break;
            case Opcodes.ACC_PUBLIC: result |= requested == Opcodes.ACC_PUBLIC ? requested : Opcodes.ACC_PUBLIC; break;
            default: throw new IllegalArgumentException("Invalid visibility flags: " + access);
        }
        if (target.changeFinal) result = target.markFinal ? result | Opcodes.ACC_FINAL : result & ~Opcodes.ACC_FINAL;
        return result;
    }

    private static String normalizeLocation(String value) {
        String result = value.replace('\\', '/');
        return result.startsWith("META-INF/") ? result : "META-INF/" + result;
    }

    private static String normalizeOwner(String owner) { return owner.replace('/', '.'); }
    private static String stripComment(String line) { int hash = line.indexOf('#'); return hash < 0 ? line : line.substring(0, hash); }

    private enum Kind { CLASS, FIELD, METHOD }

    private static final class Rule {
        final String owner, name, descriptor, source, targetText;
        final Kind kind;
        final int access, line;
        final boolean changeFinal, markFinal;

        Rule(String owner, String name, String descriptor, Kind kind, int access, boolean changeFinal, boolean markFinal, String source, int line, String targetText) {
            this.owner = owner; this.name = name; this.descriptor = descriptor; this.kind = kind; this.access = access;
            this.changeFinal = changeFinal; this.markFinal = markFinal; this.source = source; this.line = line; this.targetText = targetText;
        }

        static Rule parse(String text, String source, int line) {
            String[] parts = text.split("\\s+");
            if (parts.length != 2) return null;
            String modifier = parts[0];
            int access;
            if (modifier.startsWith("public")) access = Opcodes.ACC_PUBLIC;
            else if (modifier.startsWith("protected")) access = Opcodes.ACC_PROTECTED;
            else if (modifier.startsWith("private")) access = Opcodes.ACC_PRIVATE;
            else if (modifier.startsWith("default")) access = 0;
            else return null;
            String suffix = modifier.substring(modifier.indexOf(modifier.startsWith("protected") ? "protected" : modifier.startsWith("private") ? "private" : modifier.startsWith("default") ? "default" : "public") + (modifier.startsWith("protected") ? 9 : modifier.startsWith("private") ? 7 : modifier.startsWith("default") ? 7 : 6));
            if (!suffix.isEmpty() && !suffix.equals("-f") && !suffix.equals("+f")) return null;
            String target = parts[1];
            int method = target.indexOf('(');
            int separator = method >= 0 ? target.lastIndexOf('.', method) : target.lastIndexOf('.');
            // Slash-qualified owners make member separation unambiguous. Dotted class-only names are retained as classes.
            if (separator < 0 || (target.indexOf('/') < 0 && method < 0 && target.substring(separator + 1).indexOf('$') < 0 && Character.isUpperCase(target.charAt(separator + 1)))) {
                return new Rule(normalizeOwner(target), "", "", Kind.CLASS, access, !suffix.isEmpty(), suffix.equals("+f"), source, line, target);
            }
            String owner = normalizeOwner(target.substring(0, separator));
            String member = target.substring(separator + 1);
            // Runtime classes are intermediary/SRG-qualified. A reobfuscated AT such as
            // "bff.a" cannot be applied safely without official -> intermediary mapping;
            // reject it now instead of registering a rule that can never be visited.
            if (owner.indexOf('.') < 0 || member.isEmpty()) return null;
            if (method >= 0) return new Rule(owner, member.substring(0, member.indexOf('(')), member.substring(member.indexOf('(')), Kind.METHOD, access, !suffix.isEmpty(), suffix.equals("+f"), source, line, target);
            return new Rule(owner, member, "", Kind.FIELD, access, !suffix.isEmpty(), suffix.equals("+f"), source, line, target);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Rule)) return false;
            Rule r = (Rule) other;
            return owner.equals(r.owner) && name.equals(r.name) && descriptor.equals(r.descriptor) && kind == r.kind && access == r.access && changeFinal == r.changeFinal && markFinal == r.markFinal;
        }
        @Override public int hashCode() { return Objects.hash(owner, name, descriptor, kind, access, changeFinal, markFinal); }
    }
}

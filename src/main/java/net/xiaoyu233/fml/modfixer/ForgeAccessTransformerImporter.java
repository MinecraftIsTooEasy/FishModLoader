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
            if (fmlAt != null) for (String value : fmlAt.split("[ ,]+")) if (!value.isEmpty()) found.add(normalizeLocation(value));
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
        synchronized (RULES) {
            return RULES.containsKey(normalizeOwner(className));
        }
    }

    public static void importFrom(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String loc : findLocations(jar)) {
                String source = jarPath.toAbsolutePath().normalize() + "!/" + loc;
                synchronized (RULES) { if (!LOADED_SOURCES.add(source)) continue; }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(jar.getEntry(loc)), StandardCharsets.UTF_8))) {
                    int count = importStream(reader, source);
                    FishModLoader.LOGGER.info("Forge AT rules loaded: {} from {}", count, source);
                }
            }
        } catch (IOException e) { FishModLoader.LOGGER.warn("Could not read Forge AT from {}", jarPath, e); }
    }

    public static int importString(String content, String sourceLabel) {
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) { return importStream(reader, sourceLabel); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private static int importStream(BufferedReader reader, String source) throws IOException {
        int count = 0, lineNumber = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            String clean = stripComment(line).trim();
            if (clean.isEmpty()) continue;
            Rule rule;
            try { rule = Rule.parse(clean, source, lineNumber); }
            catch (RuntimeException malformed) { rule = null; }
            if (rule == null) {
                FishModLoader.LOGGER.warn("Unresolved Forge AT rule at {}:{}: {}", source, lineNumber, clean);
                continue;
            }
            synchronized (RULES) {
                List<Rule> ownerRules = RULES.computeIfAbsent(rule.owner, key -> new ArrayList<>());
                if (!ownerRules.contains(rule)) { ownerRules.add(rule); count++; }
            }
        }
        return count;
    }

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
            if (rule.kind == Kind.CLASS) { node.access = fixedAccess(node.access, rule); resolved = true; }
            else if (rule.kind == Kind.FIELD) {
                for (FieldNode field : node.fields) if (field.name.equals(rule.name) || "*".equals(rule.name)) {
                    field.access = fixedAccess(field.access, rule); resolved = true; if (!"*".equals(rule.name)) break;
                }
            } else {
                for (MethodNode method : node.methods) if ((method.name.equals(rule.name) && method.desc.equals(rule.descriptor)) || "*".equals(rule.name)) {
                    method.access = fixedAccess(method.access, rule); resolved = true; if (!"*".equals(rule.name)) break;
                }
            }
            if (resolved) changed++;
            else FishModLoader.LOGGER.warn("Unresolved Forge AT target {} ({}:{})", rule.targetText, rule.source, rule.line);
        }
        return changed;
    }

    private static int fixedAccess(int access, Rule target) {
        int requested = target.access, result = access & ~7;
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

    private static String normalizeLocation(String value) { String r = value.replace('\\', '/'); return r.startsWith("META-INF/") ? r : "META-INF/" + r; }
    private static String normalizeOwner(String owner) { return owner.replace('/', '.'); }
    private static String stripComment(String line) { int hash = line.indexOf('#'); return hash < 0 ? line : line.substring(0, hash); }
    private enum Kind { CLASS, FIELD, METHOD }

    private static final class Mappings {
        final Map<String, String> classes = new HashMap<>();
        final Map<String, List<String>> fields = new HashMap<>();
        final Map<String, String> methods = new HashMap<>();

        static Mappings load() {
            Mappings result = new Mappings();
            InputStream stream = ForgeAccessTransformerImporter.class.getResourceAsStream("/intermediary.tiny");
            if (stream == null) throw new IllegalStateException("Missing classpath resource intermediary.tiny");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (!"v1\tofficial\tintermediary".equals(header)) throw new IOException("Unexpected Tiny header: " + header);
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] p = line.split("\\t", -1);
                    if (p.length == 3 && "CLASS".equals(p[0])) result.classes.put(p[1], p[2]);
                    else if (p.length == 5 && "FIELD".equals(p[0])) result.fields.computeIfAbsent(p[1] + "\u0000" + p[3], k -> new ArrayList<>()).add(p[4]);
                    else if (p.length == 5 && "METHOD".equals(p[0])) result.methods.put(p[1] + "\u0000" + p[3] + "\u0000" + p[2], p[4]);
                }
            } catch (IOException e) { throw new ExceptionInInitializerError(e); }
            return result;
        }

        String descriptor(String official) {
            StringBuilder out = new StringBuilder(official.length() + 24);
            for (int i = 0; i < official.length();) {
                char c = official.charAt(i);
                if (c != 'L') { out.append(c); i++; continue; }
                int end = official.indexOf(';', i);
                if (end < 0) return null;
                String name = official.substring(i + 1, end);
                String mapped = classes.get(name);
                if (mapped == null) {
                    // Library/runtime types are already qualified. An unmapped default-package
                    // type is an unresolved official game class and must not be registered.
                    if (name.indexOf('/') < 0) return null;
                    mapped = name;
                }
                out.append('L').append(mapped).append(';');
                i = end + 1;
            }
            return out.toString();
        }
    }
    private static final class MappingHolder { static final Mappings INSTANCE = Mappings.load(); }

    static boolean probeRejectsAmbiguousOfficialField() {
        Mappings mappings = new Mappings();
        mappings.classes.put("probeOwner", "probe/MappedOwner");
        mappings.fields.put("probeOwner\u0000field", Arrays.asList("first", "second"));
        return Rule.parse("public probeOwner.field", "ambiguity-probe", 1, mappings) == null;
    }

    private static final class Rule {
        final String owner, name, descriptor, source, targetText;
        final Kind kind; final int access, line; final boolean changeFinal, markFinal;
        Rule(String owner, String name, String descriptor, Kind kind, int access, boolean changeFinal, boolean markFinal, String source, int line, String targetText) {
            this.owner=owner; this.name=name; this.descriptor=descriptor; this.kind=kind; this.access=access; this.changeFinal=changeFinal; this.markFinal=markFinal; this.source=source; this.line=line; this.targetText=targetText;
        }

        static Rule parse(String text, String source, int line) { return parse(text, source, line, MappingHolder.INSTANCE); }

        static Rule parse(String text, String source, int line, Mappings mappings) {
            String[] parts = text.split("\\s+"); if (parts.length != 2) return null;
            String modifier=parts[0]; int access;
            String base;
            if (modifier.startsWith("public")) { access=Opcodes.ACC_PUBLIC; base="public"; }
            else if (modifier.startsWith("protected")) { access=Opcodes.ACC_PROTECTED; base="protected"; }
            else if (modifier.startsWith("private")) { access=Opcodes.ACC_PRIVATE; base="private"; }
            else if (modifier.startsWith("default")) { access=0; base="default"; }
            else return null;
            String suffix=modifier.substring(base.length());
            if (!suffix.isEmpty() && !suffix.equals("-f") && !suffix.equals("+f")) return null;
            String target=parts[1]; int method=target.indexOf('('); int separator=method>=0?target.lastIndexOf('.',method):target.lastIndexOf('.');
            boolean classTarget = separator < 0 || (target.indexOf('/') < 0 && method < 0 && Character.isUpperCase(target.charAt(separator + 1)));
            if (classTarget) {
                String mapped=mappings.classes.get(target);
                if (mapped != null) return make(mapped,"","",Kind.CLASS,access,suffix,source,line,target);
                if (target.indexOf('.') < 0 && target.indexOf('/') < 0) return null;
                return make(target,"","",Kind.CLASS,access,suffix,source,line,target);
            }
            if (separator < 0) return null;
            String rawOwner=target.substring(0,separator), member=target.substring(separator+1);
            String mappedOwner=mappings.classes.get(rawOwner);
            if (mappedOwner != null) {
                if (method >= 0) {
                    String name=member.substring(0,member.indexOf('(')), desc=member.substring(member.indexOf('('));
                    String mappedName=mappings.methods.get(rawOwner+'\u0000'+name+'\u0000'+desc);
                    String mappedDesc=mappings.descriptor(desc);
                    if (mappedName == null || mappedDesc == null) return null;
                    return make(mappedOwner,mappedName,mappedDesc,Kind.METHOD,access,suffix,source,line,target);
                }
                List<String> names=mappings.fields.get(rawOwner+'\u0000'+member);
                if (names == null || names.size() != 1) return null;
                return make(mappedOwner,names.get(0),"",Kind.FIELD,access,suffix,source,line,target);
            }
            if (rawOwner.indexOf('.') < 0 && rawOwner.indexOf('/') < 0 || member.isEmpty()) return null;
            if (method >= 0) return make(rawOwner,member.substring(0,member.indexOf('(')),member.substring(member.indexOf('(')),Kind.METHOD,access,suffix,source,line,target);
            return make(rawOwner,member,"",Kind.FIELD,access,suffix,source,line,target);
        }

        private static Rule make(String owner,String name,String descriptor,Kind kind,int access,String suffix,String source,int line,String target) {
            return new Rule(normalizeOwner(owner),name,descriptor,kind,access,!suffix.isEmpty(),suffix.equals("+f"),source,line,target);
        }
        @Override public boolean equals(Object o) { if (!(o instanceof Rule)) return false; Rule r=(Rule)o; return owner.equals(r.owner)&&name.equals(r.name)&&descriptor.equals(r.descriptor)&&kind==r.kind&&access==r.access&&changeFinal==r.changeFinal&&markFinal==r.markFinal; }
        @Override public int hashCode() { return Objects.hash(owner,name,descriptor,kind,access,changeFinal,markFinal); }
    }
}

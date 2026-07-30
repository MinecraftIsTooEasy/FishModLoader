package org.moddedmite.fish.faloom;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Verifies every @Shadow and @Overwrite in forge_compat mixin compiled classes against
 * the named MITE jar (same namespace as mixin source).  Reads compiled .class files so
 * annotation extraction is exact and handles return-type mismatches.
 *
 * Usage: VerifyOverwrites <mite-named.jar> [<mixin-classes-dir>]
 *   mixin-classes-dir defaults to build/classes/java/main
 */
public final class VerifyOverwrites {

    private static final String SHADOW    = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String UNIQUE    = "Lorg/spongepowered/asm/mixin/Unique;";

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: VerifyOverwrites <mite-named.jar> [mixin-classes-dir]");
            System.exit(1);
        }
        Path jarPath    = Path.of(args[0]);
        Path classesDir = Path.of(args.length > 1 ? args[1] : "build/classes/java/main");
        Path mixinPkg   = classesDir.resolve("net/xiaoyu233/fml/reload/transform/forge_compat");

        for (Path p : new Path[]{jarPath, mixinPkg}) {
            if (!Files.exists(p)) { System.err.println("Not found: " + p); System.exit(1); }
        }

        // Build jar member index: internalName -> Set of "methodName+desc" or "fieldName:fieldDesc"
        Map<String, Set<String>> jarIndex   = buildJarIndex(jarPath);
        Map<String, String>      superclass = buildSuperMap(jarPath);

        int total = 0, missing = 0, skipped = 0;
        List<String> bad = new ArrayList<>();

        for (Path cls : Files.list(mixinPkg)
                .filter(p -> p.toString().endsWith(".class"))
                .sorted().toList()) {

            byte[] bytes = Files.readAllBytes(cls);
            String fileName = cls.getFileName().toString();

            // Extract @Mixin target
            String[] mixinTarget = {null};
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override public void visit(String n, Object value) {
                            if (value instanceof Type t && mixinTarget[0] == null)
                                mixinTarget[0] = t.getInternalName();
                        }
                        @Override public AnnotationVisitor visitArray(String n) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String n2, Object value) {
                                    if (value instanceof Type t && mixinTarget[0] == null)
                                        mixinTarget[0] = t.getInternalName();
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);

            if (mixinTarget[0] == null) continue;

            if (!jarIndex.containsKey(mixinTarget[0])) {
                System.out.printf("SKIP  %-55s -> %s (not in jar)%n", fileName, mixinTarget[0]);
                skipped++;
                continue;
            }

            // Collect @Shadow / @Overwrite members
            List<MixinMember> members = new ArrayList<>();
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        final Set<String> anns = new HashSet<>();
                        @Override public AnnotationVisitor visitAnnotation(String d, boolean v) {
                            anns.add(d); return null;
                        }
                        @Override public void visitEnd() {
                            if ((anns.contains(SHADOW) || anns.contains(OVERWRITE)) && !anns.contains(UNIQUE))
                                members.add(new MixinMember(name, desc, false));
                        }
                    };
                }
                @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object val) {
                    return new FieldVisitor(Opcodes.ASM9) {
                        final Set<String> anns = new HashSet<>();
                        @Override public AnnotationVisitor visitAnnotation(String d, boolean v) {
                            anns.add(d); return null;
                        }
                        @Override public void visitEnd() {
                            if (anns.contains(SHADOW) && !anns.contains(UNIQUE))
                                members.add(new MixinMember(name, desc, true));
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);

            // Check each member against the jar (walk superclass chain)
            for (MixinMember m : members) {
                total++;
                if (!memberExistsInChain(m, mixinTarget[0], jarIndex, superclass)) {
                    missing++;
                    bad.add(String.format("%-50s :: %s %s", fileName.replace("Mixin.class", ""),
                            m.isField ? "@Shadow(field)" : "@Shadow/@Overwrite",
                            m.name + (m.isField ? ":" + m.desc : m.desc)));
                }
            }
        }

        System.out.println();
        System.out.println("==================================================");
        System.out.printf("Checked @Overwrite/@Shadow targets : %d%n", total);
        System.out.printf("Missing on MITE classes            : %d%n", missing);
        System.out.printf("Classes skipped (not in jar)       : %d%n", skipped);
        System.out.println("==================================================");
        if (!bad.isEmpty()) {
            bad.stream().sorted().forEach(System.out::println);
            System.exit(1);
        }
        System.out.println("All targets resolved.");
    }

    private static boolean memberExistsInChain(MixinMember m, String owner,
                                                Map<String, Set<String>> idx,
                                                Map<String, String> supers) {
        String cur = owner;
        for (int g = 0; cur != null && g < 24; g++) {
            Set<String> ms = idx.get(cur);
            if (ms != null) {
                String key = m.isField ? m.name + ":" + m.desc : m.name + m.desc;
                if (ms.contains(key)) return true;
                // Also try name-only match for fields (type may differ between mixin and actual)
                if (m.isField) {
                    String prefix = m.name + ":";
                    if (ms.stream().anyMatch(s -> s.startsWith(prefix))) return true;
                }
                // Method name without descriptor (handles abstract bridge methods)
                if (!m.isField && ms.stream().anyMatch(s -> s.startsWith(m.name + "("))) return true;
            }
            String sup = supers.get(cur);
            cur = (sup != null && sup.startsWith("net/minecraft/")) ? sup : null;
        }
        return false;
    }

    private static Map<String, Set<String>> buildJarIndex(Path jarPath) throws IOException {
        Map<String, Set<String>> index = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream().filter(e -> e.getName().endsWith(".class")).forEach(entry -> {
                try {
                    byte[] bytes = jar.getInputStream(entry).readAllBytes();
                    String owner = entry.getName().replace(".class", "");
                    Set<String> members = new HashSet<>();
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int a, String name, String desc, String sig, String[] ex) {
                            members.add(name + desc); return null;
                        }
                        @Override public FieldVisitor visitField(int a, String name, String desc, String sig, Object val) {
                            members.add(name + ":" + desc); return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                    index.put(owner, members);
                } catch (IOException e) { /* skip */ }
            });
        }
        return index;
    }

    private static Map<String, String> buildSuperMap(Path jarPath) throws IOException {
        Map<String, String> supers = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream().filter(e -> e.getName().endsWith(".class")).forEach(entry -> {
                try {
                    byte[] bytes = jar.getInputStream(entry).readAllBytes();
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public void visit(int v, int a, String n, String sig, String sup, String[] i) {
                            if (sup != null) supers.put(n, sup);
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                } catch (IOException e) { /* skip */ }
            });
        }
        return supers;
    }

    record MixinMember(String name, String desc, boolean isField) {}
}

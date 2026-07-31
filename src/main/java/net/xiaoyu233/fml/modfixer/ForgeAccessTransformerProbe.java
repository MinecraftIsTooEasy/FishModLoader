package net.xiaoyu233.fml.modfixer;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Direct executable behavior probe for Forge AT parsing, discovery and flag mutation. */
public final class ForgeAccessTransformerProbe {
    private ForgeAccessTransformerProbe() {}

    public static void main(String[] args) throws Exception {
        String owner = "example.mod.ProbeTarget";
        int loaded = ForgeAccessTransformerImporter.importString(
                "public-f example/mod/ProbeTarget\n" +
                "protected+f example/mod/ProbeTarget.value\n" +
                "public-f example/mod/ProbeTarget.run()V\n" +
                "private example/mod/ProbeTarget.alreadyPublic\n" +
                "public-f bff.a # reobfuscated owner must be rejected explicitly\n" +
                "public broken. # malformed target must not abort discovery\n", "behavior-probe");
        check(loaded == 4, "four runtime/SRG rules loaded; official and malformed rules rejected");

        ClassNode node = new ClassNode();
        node.name = "example/mod/ProbeTarget";
        node.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "value", "Ljava/lang/String;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "alreadyPublic", "I", null, null));
        node.methods.add(new MethodNode(Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL, "run", "()V", null, null));
        int applied = ForgeAccessTransformerImporter.apply(owner, node);
        check(applied == 4, "all targets resolve");
        check((node.access & Opcodes.ACC_PUBLIC) != 0 && (node.access & Opcodes.ACC_FINAL) == 0, "class public-f");
        check((node.fields.get(0).access & Opcodes.ACC_PROTECTED) != 0 && (node.fields.get(0).access & Opcodes.ACC_FINAL) != 0, "descriptorless field protected+f");
        check((node.methods.get(0).access & Opcodes.ACC_PUBLIC) != 0 && (node.methods.get(0).access & Opcodes.ACC_FINAL) == 0, "method public-f");
        check((node.fields.get(1).access & Opcodes.ACC_PUBLIC) != 0, "Forge visibility lattice does not narrow public");
        check(ForgeAccessTransformerImporter.hasAccessTransform(owner), "non-Minecraft owner registered");

        Path jarPath = Files.createTempFile("forge-at-probe", ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("FMLAT", "custom_at.cfg META-INF/custom_at.cfg");
        try (OutputStream out = Files.newOutputStream(jarPath); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            add(jar, "META-INF/custom_at.cfg");
            add(jar, "META-INF/forge_at.cfg");
            add(jar, "META-INF/other_at.cfg");
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            List<String> locations = ForgeAccessTransformerImporter.findLocations(jar);
            check(locations.size() == 3, "manifest/fixed/wildcard discovery is deduplicated: " + locations);
            check(locations.contains("META-INF/custom_at.cfg") && locations.contains("META-INF/forge_at.cfg") && locations.contains("META-INF/other_at.cfg"), "all discovery forms present");
        } finally {
            Files.deleteIfExists(jarPath);
        }
        System.out.println("ForgeAccessTransformerProbe: 12 assertions passed");
    }

    private static void add(JarOutputStream jar, String name) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write("public example/mod/Unused\n".getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

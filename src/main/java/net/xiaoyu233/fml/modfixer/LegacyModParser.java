package net.xiaoyu233.fml.modfixer;

import net.xiaoyu233.fml.FishModLoader;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans a jar for {@code @cpw.mods.fml.common.Mod} annotations and emits
 * {@link LegacyModInfo} descriptors. Used by FishModLoader's mod discovery
 * pipeline to recognize Forge 1.6.4 mods as candidates.
 *
 * Stage-3 of the Forge compat plan. The downstream {@link LegacyModCandidate}
 * wraps the result so the rest of the loader treats it like a normal mod.
 */
public class LegacyModParser {

    private static final String MOD_ANNOTATION_DESC = "Lcpw/mods/fml/common/Mod;";

    private LegacyModParser() {}

    /**
     * Scan a single jar and return all {@code @Mod} entries found inside.
     * Returns empty list if the jar has no Forge mods.
     */
    public static List<LegacyModInfo> scan(Path jarPath) {
        List<LegacyModInfo> result = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream().forEach(entry -> {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) return;
                try (InputStream in = new BufferedInputStream(jar.getInputStream(entry))) {
                    LegacyModInfo info = scanClass(jarPath, entry, in);
                    if (info != null) result.add(info);
                } catch (IOException cause) {
                    FishModLoader.LOGGER.warn("Skipping unreadable class {} in {}", entry.getName(), jarPath, cause);
                }
            });
        } catch (IOException cause) {
            FishModLoader.LOGGER.warn("Failed to open jar {}", jarPath, cause);
        }
        return result;
    }

    private static LegacyModInfo scanClass(Path jarPath, JarEntry entry, InputStream classStream) throws IOException {
        ClassReader reader = new ClassReader(classStream);
        ModAnnotationCollector collector = new ModAnnotationCollector();
        reader.accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!collector.found) return null;

        String className = entry.getName().substring(0, entry.getName().length() - ".class".length()).replace('/', '.');
        return new LegacyModInfo(jarPath, className, collector.values);
    }

    /** Visit a class file and capture the {@code @Mod} annotation values, if any. */
    private static class ModAnnotationCollector extends ClassVisitor {
        final Map<String, Object> values = new HashMap<>();
        boolean found = false;

        ModAnnotationCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (MOD_ANNOTATION_DESC.equals(descriptor)) {
                found = true;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        values.put(name, value);
                    }
                };
            }
            return null;
        }
    }
}

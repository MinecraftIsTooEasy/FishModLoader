package net.xiaoyu233.fml.modfixer;

import net.xiaoyu233.fml.FishModLoader;
import net.xiaoyu233.fml.util.LoaderUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime SRG -> MITE member-name remapper for Forge 1.6.4 mods.
 *
 * <p>Forge 1.6.4 mods are commonly distributed with SRG member names
 * ({@code func_*}/{@code field_*}) in their bytecode. FishModLoader keeps the
 * original mod jar on the classpath and rewrites class bytes as they are loaded,
 * so users can drop an unmodified Forge jar into {@code mods/}.
 */
public final class ForgeSrgModRemapper {

    private static final String MAPPINGS_RESOURCE = "/forge-srg-1.6.4.tiny";

    private static final Set<Path> forgeCodeSources =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile Mappings mappings;

    private ForgeSrgModRemapper() {}

    public static void registerForgeModJar(Path jarPath) {
        forgeCodeSources.add(LoaderUtil.normalizeExistingPath(jarPath));
    }

    public static boolean isForgeModSource(Path codeSource) {
        return forgeCodeSources.contains(LoaderUtil.normalizeExistingPath(codeSource));
    }

    public static byte[] remapClass(String className, Path codeSource, byte[] bytes) {
        if (bytes == null || codeSource == null || !isForgeModSource(codeSource)) {
            return bytes;
        }

        Mappings loadedMappings = getMappings();
        if (loadedMappings.isEmpty()) {
            return bytes;
        }

        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            reader.accept(new ClassRemapper(writer, new SrgMemberRemapper(loadedMappings)), 0);
            return writer.toByteArray();
        } catch (Throwable thrown) {
            FishModLoader.LOGGER.warn("Failed to runtime-remap Forge class {} from {}",
                    className, codeSource.getFileName(), thrown);
            return bytes;
        }
    }

    private static Mappings getMappings() {
        Mappings loaded = mappings;
        if (loaded != null) return loaded;
        synchronized (ForgeSrgModRemapper.class) {
            if (mappings == null) {
                mappings = loadMappings();
            }
            return mappings;
        }
    }

    private static Mappings loadMappings() {
        InputStream resource = ForgeSrgModRemapper.class.getResourceAsStream(MAPPINGS_RESOURCE);
        if (resource == null) {
            FishModLoader.LOGGER.warn("Missing {}; Forge SRG names will not be remapped", MAPPINGS_RESOURCE);
            return Mappings.EMPTY;
        }

        Map<MemberKey, String> methods = new HashMap<>();
        Map<MemberKey, String> fields = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\t");
                if (parts.length < 4) continue;
                if ("m".equals(parts[0])) {
                    methods.put(new MemberKey(parts[2], parts[1]), parts[3]);
                } else if ("f".equals(parts[0])) {
                    fields.put(new MemberKey(parts[2], parts[1]), parts[3]);
                }
            }
        } catch (IOException e) {
            FishModLoader.LOGGER.warn("Failed to read {}; Forge SRG names will not be remapped",
                    MAPPINGS_RESOURCE, e);
            return Mappings.EMPTY;
        }

        FishModLoader.LOGGER.info("Loaded Forge SRG runtime mappings: {} methods, {} fields",
                methods.size(), fields.size());
        return new Mappings(methods, fields);
    }

    private static final class SrgMemberRemapper extends Remapper {
        private final Mappings mappings;

        private SrgMemberRemapper(Mappings mappings) {
            this.mappings = mappings;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (!name.startsWith("func_")) return name;
            String mapped = mappings.methods.get(new MemberKey(name, descriptor));
            return mapped == null ? name : mapped;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            if (!name.startsWith("field_")) return name;
            String mapped = mappings.fields.get(new MemberKey(name, descriptor));
            return mapped == null ? name : mapped;
        }
    }

    private static final class Mappings {
        static final Mappings EMPTY = new Mappings(Collections.emptyMap(), Collections.emptyMap());

        final Map<MemberKey, String> methods;
        final Map<MemberKey, String> fields;

        Mappings(Map<MemberKey, String> methods, Map<MemberKey, String> fields) {
            this.methods = methods;
            this.fields = fields;
        }

        boolean isEmpty() {
            return methods.isEmpty() && fields.isEmpty();
        }
    }

    private static final class MemberKey {
        final String name;
        final String descriptor;

        MemberKey(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof MemberKey)) return false;
            MemberKey key = (MemberKey) other;
            return name.equals(key.name) && descriptor.equals(key.descriptor);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + descriptor.hashCode();
        }
    }
}

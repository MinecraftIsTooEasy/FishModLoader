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
 * Runtime SRG member-name remapper for Forge 1.6.4 mods.
 *
 * <p>FishModLoader runs the game jar in the {@code intermediary} (SRG) namespace,
 * so Forge mods, which are also distributed with SRG member names
 * ({@code func_*}/{@code field_*}), need no remapping at runtime. This class
 * loads {@code /intermediary.tiny} to validate that intermediary mappings are
 * present and acts as an identity pass-through.
 *
 * <p>Keeping the (non-functional) remapper infrastructure lets us detect
 * namespace mismatches early — if {@code intermediary.tiny} is missing or
 * the runtime namespace changes, the log warnings will alert us.
 */
@Deprecated
public final class ForgeSrgModRemapper {

    private static final String MAPPINGS_RESOURCE = "/intermediary.tiny";

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
            FishModLoader.LOGGER.warn("Missing {}; Forge SRG name remapping disabled", MAPPINGS_RESOURCE);
            return Mappings.EMPTY;
        }

        // intermediary.tiny uses v1 format:
        //   METHOD\t<owner>\t<desc>\t<official>\t<intermediary>
        //   FIELD\t<owner>\t<desc>\t<official>\t<intermediary>
        //
        // At runtime the game jar is in the intermediary (SRG) namespace, so
        // Forge mods already using SRG need no remapping.  We still load the
        // file to validate that the intermediary namespace is available.
        int methodCount = 0;
        int fieldCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("v1\t")) continue;
                String[] parts = trimmed.split("\t");
                if (parts.length < 5) continue;
                if ("METHOD".equals(parts[0])) {
                    methodCount++;
                } else if ("FIELD".equals(parts[0])) {
                    fieldCount++;
                }
            }
        } catch (IOException e) {
            FishModLoader.LOGGER.warn("Failed to read {}; Forge SRG name remapping disabled",
                    MAPPINGS_RESOURCE, e);
            return Mappings.EMPTY;
        }

        FishModLoader.LOGGER.info("Loaded {}: {} methods, {} fields (runtime uses intermediary, identity remap)",
                MAPPINGS_RESOURCE, methodCount, fieldCount);
        return Mappings.EMPTY_IDENTITY;
    }

    private static final class SrgMemberRemapper extends Remapper {
        private final Mappings mappings;

        private SrgMemberRemapper(Mappings mappings) {
            this.mappings = mappings;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            // Runtime is in intermediary (SRG) namespace; Forge mods already use SRG names.
            // No remapping needed — pass through unchanged.
            return name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            // Runtime is in intermediary (SRG) namespace; Forge mods already use SRG names.
            // No remapping needed — pass through unchanged.
            return name;
        }
    }

    private static final class Mappings {
        static final Mappings EMPTY = new Mappings(Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), true);
        // Identity mapping — runtime is in intermediary (SRG), same as Forge mods.
        // Non-empty so remapClass() proceeds, but SrgMemberRemapper passes all
        // names through unchanged.
        static final Mappings EMPTY_IDENTITY = new Mappings(Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), false);

        final Map<MemberKey, String> methods;
        final Map<MemberKey, String> fields;
        final Map<String, String> methodsByName;
        final Map<String, String> fieldsByName;
        private final boolean empty;

        Mappings(Map<MemberKey, String> methods, Map<MemberKey, String> fields,
                 Map<String, String> methodsByName, Map<String, String> fieldsByName) {
            this(methods, fields, methodsByName, fieldsByName, methods.isEmpty() && fields.isEmpty());
        }

        Mappings(Map<MemberKey, String> methods, Map<MemberKey, String> fields,
                 Map<String, String> methodsByName, Map<String, String> fieldsByName,
                 boolean empty) {
            this.methods = methods;
            this.fields = fields;
            this.methodsByName = methodsByName;
            this.fieldsByName = fieldsByName;
            this.empty = empty;
        }

        boolean isEmpty() {
            return empty;
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

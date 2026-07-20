package org.moddedmite.fish.faloom;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

/**
 * Reads both v1 and v2 {@code .tiny} mapping files into in-memory tables.
 *
 * <p><b>v1 format</b> (e.g. {@code intermediary.tiny}):
 * <pre>
 * v1\t<srcNs>\t<dstNs>
 * CLASS\t<srcName>\t<dstName>
 * METHOD\t<owner>\t<desc>\t<srcName>\t<dstName>
 * FIELD\t<owner>\t<desc>\t<srcName>\t<dstName>
 * </pre>
 *
 * <p><b>v2 format</b> (e.g. {@code named.tiny}, {@code mappings.tiny}):
 * <pre>
 * tiny\t2\t0\t<srcNs>\t<dstNs>
 * c\t<srcName>\t<dstName>
 * \tf\t<desc>\t<srcName>\t<dstName>
 * \tm\t<desc>\t<srcName>\t<dstName>
 * </pre>
 *
 * <p>This reader extracts the namespace names, class mappings, method mappings,
 * and field mappings for use by downstream consumers such as
 * {@link TinyMappingResolver} and {@code ChainedMappingProvider}.
 */
public final class TinyFileReader {

    private final String srcNs;
    private final String dstNs;
    private final Map<String, String> classes = new LinkedHashMap<>();
    private final Map<MemberKey, String> methods = new LinkedHashMap<>();
    private final Map<MemberKey, String> fields = new LinkedHashMap<>();

    private TinyFileReader(String srcNs, String dstNs) {
        this.srcNs = srcNs;
        this.dstNs = dstNs;
    }

    /** Parse a {@code .tiny} mapping file from the given reader. */
    public static TinyFileReader read(BufferedReader reader) throws IOException {
        String headerLine = reader.readLine();
        if (headerLine == null) throw new IOException("Empty tiny file");

        String[] parts = headerLine.split("\t");

        if (parts[0].equals("v1")) {
            // v1 format: v1  srcNs  dstNs
            return readV1(reader, parts[1], parts[2]);
        } else if (parts[0].equals("tiny")) {
            // v2 format: tiny  2  0  srcNs  dstNs
            return readV2(reader, parts[3], parts[4]);
        } else {
            throw new IOException("Unknown tiny format: " + parts[0]);
        }
    }

    private static TinyFileReader readV1(BufferedReader reader, String srcNs, String dstNs) throws IOException {
        TinyFileReader result = new TinyFileReader(srcNs, dstNs);
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\t");
            String type = parts[0];

            switch (type) {
                case "CLASS":
                    if (parts.length >= 3) result.classes.put(parts[1], parts[2]);
                    break;
                case "METHOD":
                    // METHOD  owner  desc  srcName  dstName
                    if (parts.length >= 5) {
                        result.methods.put(new MemberKey(parts[1] + "/" + parts[2], parts[3]), parts[4]);
                    }
                    break;
                case "FIELD":
                    // FIELD  owner  desc  srcName  dstName
                    if (parts.length >= 5) {
                        result.fields.put(new MemberKey(parts[1] + "/" + parts[2], parts[3]), parts[4]);
                    }
                    break;
            }
        }
        return result;
    }

    private static TinyFileReader readV2(BufferedReader reader, String srcNs, String dstNs) throws IOException {
        TinyFileReader result = new TinyFileReader(srcNs, dstNs);
        String currentClass = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            char firstChar = line.charAt(0);
            if (firstChar == 'c') {
                // Class entry: c  srcName  dstName
                String[] parts = line.split("\t", 3);
                if (parts.length >= 3) {
                    currentClass = parts[1];
                    result.classes.put(parts[1], parts[2]);
                }
            } else if (firstChar == '\t' && currentClass != null) {
                // Member entry: \tf  desc  srcName  dstName
                //               \tm  desc  srcName  dstName
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split("\t", 4);
                if (parts.length < 4) continue;
                String type = parts[0]; // "f" or "m"
                String desc = parts[1];
                String srcName = parts[2];
                String dstName = parts[3];
                MemberKey key = new MemberKey(currentClass + "/" + desc, srcName);
                if (type.equals("f")) {
                    result.fields.put(key, dstName);
                } else if (type.equals("m")) {
                    result.methods.put(key, dstName);
                }
            }
        }
        return result;
    }

    public String getSrcNs() { return srcNs; }
    public String getDstNs() { return dstNs; }
    public Map<String, String> getClasses() { return Collections.unmodifiableMap(classes); }
    public Map<MemberKey, String> getMethods() { return Collections.unmodifiableMap(methods); }
    public Map<MemberKey, String> getFields() { return Collections.unmodifiableMap(fields); }

    /** Composite key for member lookups: owner/desc + name. */
    public static final class MemberKey {
        private final String ownerDesc;  // e.g. "net/minecraft/block/Block/(I)V"
        private final String name;

        public MemberKey(String ownerDesc, String name) {
            this.ownerDesc = ownerDesc;
            this.name = name;
        }

        public String getOwnerDesc() { return ownerDesc; }
        public String getName() { return name; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MemberKey)) return false;
            MemberKey key = (MemberKey) o;
            return ownerDesc.equals(key.ownerDesc) && name.equals(key.name);
        }

        @Override
        public int hashCode() {
            return 31 * ownerDesc.hashCode() + name.hashCode();
        }

        @Override
        public String toString() {
            return name + ":" + ownerDesc;
        }
    }

    /**
     * Build a table that chains {@code this} (src → mid) with {@code next} (mid → dst).
     * The result maps directly from src → dst.
     */
    public Mappings chain(TinyFileReader next) {
        Map<String, String> chainedClasses = new LinkedHashMap<>(classes.size());
        Map<MemberKey, String> chainedMethods = new LinkedHashMap<>(methods.size());
        Map<MemberKey, String> chainedFields = new LinkedHashMap<>(fields.size());

        // Chain class mappings: this.src → this.dst(=next.src) → next.dst
        for (Map.Entry<String, String> e : classes.entrySet()) {
            String srcClass = e.getKey();
            String midClass = e.getValue();
            String dstClass = next.classes.getOrDefault(midClass, midClass);
            chainedClasses.put(srcClass, dstClass);
        }
        // Also add classes that only exist in next (no intermediate step)
        for (Map.Entry<String, String> e : next.classes.entrySet()) {
            if (!chainedClasses.containsKey(e.getKey())) {
                chainedClasses.put(e.getKey(), e.getValue());
            }
        }

        // Chain method and field mappings
        // For each entry in this (src → mid), look up mid in next to get named
        chainMembers(methods, next.methods, chainedMethods);
        chainMembers(fields, next.fields, chainedFields);

        return new Mappings(
                srcNs, next.dstNs,
                chainedClasses, chainedMethods, chainedFields
        );
    }

    private static void chainMembers(
            Map<MemberKey, String> srcMap,
            Map<MemberKey, String> nextMap,
            Map<MemberKey, String> out) {
        for (Map.Entry<MemberKey, String> e : srcMap.entrySet()) {
            String midName = e.getValue();
            MemberKey midKey = new MemberKey(e.getKey().getOwnerDesc(), midName);
            String dstName = nextMap.getOrDefault(midKey, midName);
            out.put(e.getKey(), dstName);
        }
    }

    /** Combined chained mapping tables. */
    public static final class Mappings {
        private final String srcNs;
        private final String dstNs;
        private final Map<String, String> classes;
        private final Map<MemberKey, String> methods;
        private final Map<MemberKey, String> fields;

        public Mappings(String srcNs, String dstNs,
                        Map<String, String> classes,
                        Map<MemberKey, String> methods,
                        Map<MemberKey, String> fields) {
            this.srcNs = srcNs;
            this.dstNs = dstNs;
            this.classes = classes;
            this.methods = methods;
            this.fields = fields;
        }

        public String getSrcNs() { return srcNs; }
        public String getDstNs() { return dstNs; }
        public Map<String, String> getClasses() { return classes; }
        public Map<MemberKey, String> getMethods() { return methods; }
        public Map<MemberKey, String> getFields() { return fields; }

        public String mapClass(String srcName) {
            return classes.getOrDefault(srcName, srcName);
        }

        public String mapMethod(String owner, String desc, String name) {
            String mapped = methods.get(new MemberKey(owner + "/" + desc, name));
            return mapped != null ? mapped : name;
        }

        public String mapField(String owner, String desc, String name) {
            String mapped = fields.get(new MemberKey(owner + "/" + desc, name));
            return mapped != null ? mapped : name;
        }
    }
}

package org.moddedmite.fish.faloom;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NamedToIntermediaryTinyGenerator {

    private static final Pattern CLASS_REF_PATTERN = Pattern.compile("L([^;]+);");

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: NamedToIntermediaryTinyGenerator <outputTinyPath>");
            System.exit(1);
        }
        Path outputPath = Path.of(args[0]);

        TinyFileReader mappingsTiny;
        TinyFileReader interTiny;
        TinyFileReader namedTiny;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(NamedToIntermediaryTinyGenerator.class.getResourceAsStream("/mappings.tiny"))))) {
            mappingsTiny = TinyFileReader.read(r);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(NamedToIntermediaryTinyGenerator.class.getResourceAsStream("/intermediary.tiny"))))) {
            interTiny = TinyFileReader.read(r);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(NamedToIntermediaryTinyGenerator.class.getResourceAsStream("/named.tiny"))))) {
            namedTiny = TinyFileReader.read(r);
        }

        Map<String, String> namedToIntermediary = new LinkedHashMap<>();
        Map<String, String> intermediaryToNamed = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : mappingsTiny.getClasses().entrySet()) {
            String official = e.getKey();
            String named = e.getValue();
            String intermediary = interTiny.getClasses().get(official);
            if (intermediary != null) {
                namedToIntermediary.put(named, intermediary);
                intermediaryToNamed.put(intermediary, named);
            } else {
                namedToIntermediary.put(named, official);
                intermediaryToNamed.put(official, named);
            }
        }

        for (Map.Entry<String, String> e : interTiny.getClasses().entrySet()) {
            String official = e.getKey();
            String intermediary = e.getValue();
            if (!mappingsTiny.getClasses().containsKey(official)) {
                namedToIntermediary.put(intermediary, intermediary);
                intermediaryToNamed.put(intermediary, intermediary);
            }
        }

        for (Map.Entry<String, String> e : namedTiny.getClasses().entrySet()) {
            String interName = e.getKey();
            String namedName = e.getValue();
            if (!intermediaryToNamed.containsKey(interName)) {
                intermediaryToNamed.put(interName, namedName);
            }
            if (!namedToIntermediary.containsKey(namedName)) {
                namedToIntermediary.put(namedName, interName);
            }
        }

        Map<String, String> officialToNamed = new LinkedHashMap<>(mappingsTiny.getClasses());

        Map<String, List<MemberMapping>> membersByNamedClass = new LinkedHashMap<>();

        for (Map.Entry<TinyFileReader.MemberKey, String> e : namedTiny.getMethods().entrySet()) {
            processMember(e.getKey(), e.getValue(), officialToNamed, intermediaryToNamed, namedToIntermediary, true, membersByNamedClass);
        }
        for (Map.Entry<TinyFileReader.MemberKey, String> e : namedTiny.getFields().entrySet()) {
            processMember(e.getKey(), e.getValue(), officialToNamed, intermediaryToNamed, namedToIntermediary, false, membersByNamedClass);
        }

        try (BufferedWriter w = Files.newBufferedWriter(outputPath)) {
            w.write("tiny\t2\t0\tnamed\tintermediary");
            w.newLine();

            for (Map.Entry<String, String> e : namedToIntermediary.entrySet()) {
                String namedClass = e.getKey();
                String intermediaryClass = e.getValue();

                w.write("c\t");
                w.write(namedClass);
                w.write('\t');
                w.write(intermediaryClass);
                w.newLine();

                List<MemberMapping> members = membersByNamedClass.get(namedClass);
                if (members != null) {
                    for (MemberMapping m : members) {
                        w.write('\t');
                        w.write(m.isMethod ? 'm' : 'f');
                        w.write('\t');
                        w.write(m.namedDesc);
                        w.write('\t');
                        w.write(m.namedName);
                        w.write('\t');
                        w.write(m.intermediaryName);
                        w.newLine();
                    }
                }
            }
        }
    }

    private static void processMember(TinyFileReader.MemberKey key, String dstName, Map<String, String> officialToNamed, Map<String, String> intermediaryToNamed,
                                      Map<String, String> namedToIntermediary, boolean isMethod, Map<String, List<MemberMapping>> membersByNamedClass) {
        String ownerDesc = key.getOwnerDesc();
        int descStart = findDescriptorStart(ownerDesc);
        int slashIdx = descStart > 0 ? ownerDesc.lastIndexOf('/', descStart) : -1;
        String intermediaryOwner = slashIdx > 0 ? ownerDesc.substring(0, slashIdx) : ownerDesc;
        String intermediaryDesc = slashIdx > 0 ? ownerDesc.substring(slashIdx + 1) : ownerDesc;

        String intermediaryName = key.getName();
	    
        String namedOwner = intermediaryToNamed.get(intermediaryOwner);
        if (namedOwner == null) {
            return;
        }
        String namedDesc = transformDescriptor(intermediaryDesc, officialToNamed, intermediaryToNamed);
        if (!namedToIntermediary.containsKey(namedOwner)) {
            return;
        }

        membersByNamedClass.computeIfAbsent(namedOwner, k -> new ArrayList<>()).add(new MemberMapping(namedDesc, dstName, intermediaryName, isMethod));
    }

    static String transformDescriptor(String desc, Map<String, String> officialToNamed, Map<String, String> intermediaryToNamed) {
        if (desc == null || desc.isEmpty()) return desc;

        StringBuilder result = new StringBuilder(desc.length() + 64);
        Matcher m = CLASS_REF_PATTERN.matcher(desc);
        int lastEnd = 0;

        while (m.find()) {
            result.append(desc, lastEnd, m.start());
            String className = m.group(1);
            String qualified = officialToNamed.get(className);
            if (qualified == null) {
                qualified = intermediaryToNamed.get(className);
            }
            if (qualified != null) {
                result.append('L').append(qualified).append(';');
            } else {
                result.append(m.group());
            }

            lastEnd = m.end();
        }

        result.append(desc.substring(lastEnd));
        return result.toString();
    }

    /**
     * Find the position of the descriptor start character within an
     * "{@code owner/desc}" combined string.
     *
     * <p>Definitive descriptor-start characters are {@code (} (method),
     * {@code L} (object field), and {@code [} (array field). These never
     * appear as the first character of a class path segment.
     *
     * <p>For primitive field descriptors ({@code I}, {@code Z}, etc.), if no
     * definitive start is found, the last {@code /} before the end of
     * the string is assumed to be the separator — this works because
     * primitives are single-character descriptors.
     */
    private static int findDescriptorStart(String ownerDesc) {
        int lastSlash = -1;
        // Iterate from the end so we find the LAST /<descriptor-start> pair,
        // not the first one (class paths like "LongHashMap" can contain 'L'
        // which falsely looks like a descriptor-start character).
        for (int i = ownerDesc.length() - 2; i >= 0; i--) {
            if (ownerDesc.charAt(i) == '/') {
                char next = ownerDesc.charAt(i + 1);
                if (next == '(' || next == 'L' || next == '[') {
                    return i + 1;
                }
                if (lastSlash == -1) {
                    lastSlash = i;
                }
            }
        }
        // No definitive descriptor start found.
        // If we have a slash at the end (primitive field descriptor),
        // and the char after lastSlash is a primitive, return it.
        if (lastSlash > 0 && lastSlash < ownerDesc.length() - 1) {
            char c = ownerDesc.charAt(lastSlash + 1);
            if ("IZVBCDFJS".indexOf(c) >= 0) {
                return lastSlash + 1;
            }
        }
        return -1;
    }

    private static final class MemberMapping {
        final String namedDesc;
        final String namedName;
        final String intermediaryName;
        final boolean isMethod;

        MemberMapping(String namedDesc, String namedName, String intermediaryName, boolean isMethod) {
            this.namedDesc = namedDesc;
            this.namedName = namedName;
            this.intermediaryName = intermediaryName;
            this.isMethod = isMethod;
        }
    }
}

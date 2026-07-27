package org.moddedmite.fish.faloom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

public final class MixinRefmapGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: MixinRefmapGenerator <inputJar> <mappingsTiny> <outputRefmap>");
            System.exit(1);
        }

        Path inputJar = Path.of(args[0]);
        Path mappingsFile = Path.of(args[1]);
        Path outputRefmap = Path.of(args[2]);

        if (!Files.exists(inputJar)) {
            System.err.println("Input jar not found: " + inputJar);
            System.exit(1);
        }
        if (!Files.exists(mappingsFile)) {
            System.err.println("Mapping file not found: " + mappingsFile);
            System.exit(1);
        }

        Map<String, String> namedToIntermediaryClass = new HashMap<>();
        Map<String, Map<String, String>> namedFieldIndex = new HashMap<>();
        Map<String, Map<String, Map<String, String>>> namedMethodIndex = new HashMap<>();

        try (BufferedReader r = Files.newBufferedReader(mappingsFile)) {
            String header = r.readLine();
            if (header == null || !header.startsWith("tiny\t")) {
                System.err.println("Not a valid tiny mapping file: " + mappingsFile);
                System.exit(1);
            }
            String[] headerParts = header.split("\t");
            if (headerParts.length < 5) {
                System.err.println("Invalid tiny header: " + header);
                System.exit(1);
            }
            String ns0 = headerParts[3];
            String ns1 = headerParts[4];
            boolean namedFirst = ns0.equals("named") && ns1.equals("intermediary");
            if (!namedFirst) {
                System.err.println("Expected 'named' then 'intermediary', got: " + ns0 + " then " + ns1);
                System.exit(1);
            }

            String currentClass = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.startsWith("c\t")) {
                    String[] parts = line.split("\t");
                    if (parts.length >= 3) {
                        currentClass = parts[1];
                        namedToIntermediaryClass.put(parts[1], parts[2]);
                    }
                } else if (line.startsWith("\tm\t") || line.startsWith("\tf\t")) {
                    if (currentClass == null) continue;
                    String[] parts = line.split("\t");
                    if (parts.length >= 4) {
                        char type = line.charAt(1);
                        String desc = parts[2];
                        String srcName = parts[3];
                        String dstName = parts[4];

                        if (type == 'f') {
                            namedFieldIndex.computeIfAbsent(currentClass, k -> new HashMap<>()).put(srcName, dstName);
                        } else {
                            namedMethodIndex
                                    .computeIfAbsent(currentClass, k -> new HashMap<>())
                                    .computeIfAbsent(srcName, k -> new HashMap<>())
                                    .put(desc, dstName);
                        }
                    }
                }
            }
        }

        System.err.println("DEBUG: Loaded " + namedFieldIndex.size() + " classes with field mappings from " + mappingsFile);

        Map<String, MixinInfo> mixins = scanMixinClasses(inputJar);

        Map<String, Object> refmap = new LinkedHashMap<>();
        refmap.put("minVersion", "0.8");

        Map<String, Map<String, String>> mappingsSection = new LinkedHashMap<>();
        Map<String, Map<String, String>> dataSection = new LinkedHashMap<>();

        for (Map.Entry<String, MixinInfo> entry : mixins.entrySet()) {
            String mixinClass = entry.getKey();
            MixinInfo info = entry.getValue();

            Map<String, String> mixinMappings = new LinkedHashMap<>();
            Map<String, String> mixinData = new LinkedHashMap<>();

            for (MixinInfo.ShadowField sf : info.shadowFields) {
                String mapped = resolveField(sf.name, sf.desc, info.targets, namedFieldIndex);
                if (mapped != null && !mapped.equals(sf.name)) {
                    mixinMappings.put(sf.name, mapped);
                    mixinData.put(sf.name, mapped);
                }
            }

            for (MixinInfo.ShadowMethod sm : info.shadowMethods) {
                String mapped = resolveMethod(sm.name, sm.desc, info.targets, namedMethodIndex);
                if (mapped != null && !mapped.equals(sm.name)) {
                    mixinMappings.put(sm.name, mapped);
                    String key = sm.name + sm.desc;
                    String val = mapped + sm.desc;
                    mixinData.put(key, val);
                    mixinData.put(sm.name, mapped);
                }
            }

            for (MixinInfo.AccessorRef ar : info.accessors) {
                String mapped;
                if (ar.isField) {
                    mapped = resolveField(ar.name, "", info.targets, namedFieldIndex);
                } else {
                    mapped = resolveMethod(ar.name, "", info.targets, namedMethodIndex);
                }
                if (mapped != null && !mapped.equals(ar.name)) {
                    mixinMappings.put(ar.name, mapped);
                    mixinData.put(ar.name, mapped);
                }
            }

            for (MixinInfo.InvokerRef ir : info.invokers) {
                String mapped = resolveMethod(ir.name, "", info.targets, namedMethodIndex);
                if (mapped != null && !mapped.equals(ir.name)) {
                    mixinMappings.put(ir.name, mapped);
                    mixinData.put(ir.name, mapped);
                }
            }

            for (String methodRef : info.injectMethods) {
                String mappedMethod = remapMethodRef(methodRef, info.targets, namedMethodIndex);
                if (!mappedMethod.equals(methodRef)) {
                    mixinMappings.put(methodRef, mappedMethod);
                    mixinData.put(methodRef, mappedMethod);
                }
            }

            for (String targetRef : info.atTargets) {
                if (!targetRef.startsWith("L")) continue;
                int semiIdx = targetRef.indexOf(';');
                if (semiIdx < 1) continue;
                String ownerClass = targetRef.substring(1, semiIdx);
                String rest = targetRef.substring(semiIdx + 1);
                int parenIdx = rest.indexOf('(');
                String methodName = parenIdx > 0 ? rest.substring(0, parenIdx) : rest;
                String methodDesc = parenIdx > 0 ? rest.substring(parenIdx) : "";

                String mapped = resolveMethod(methodName, methodDesc,
                        Collections.singletonList(ownerClass), namedMethodIndex);

                // ReferenceMapper does an EXACT lookup on the whole reference
                // string, so the key must be the full @At target as written in
                // the annotation (e.g. "Lnet/minecraft/stats/StatList;nopInit()V").
                // Emitting only the bare method name never matches and the
                // injection silently resolves 0 targets.
                String mappedOwner = namedToIntermediaryClass.getOrDefault(ownerClass, ownerClass);
                String mappedDesc = remapDescriptorTypes(methodDesc, namedToIntermediaryClass);
                String fullMapped = "L" + mappedOwner + ";" + mapped + mappedDesc;

                if (!fullMapped.equals(targetRef)) {
                    mixinMappings.put(targetRef, fullMapped);
                    mixinData.put(targetRef, fullMapped);
                }
                // Keep the bare-name entry too: some injectors look up the
                // member name on its own.
                if (!mapped.equals(methodName)) {
                    mixinMappings.put(methodName, mapped);
                    mixinData.put(methodName, mapped);
                }
            }

            if (!mixinMappings.isEmpty()) {
                mappingsSection.put(mixinClass, mixinMappings);
                dataSection.put(mixinClass, mixinData);
            }
        }

        refmap.put("mappings", mappingsSection);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("named:intermediary", dataSection);
        refmap.put("data", data);

        Files.createDirectories(outputRefmap.getParent());
        String json = GSON.toJson(refmap);
        Files.writeString(outputRefmap, json);
        System.out.println("Generated refmap with " + mixins.size() + " mixin classes -> " + outputRefmap);
    }


    private static String resolveField(String name, String desc, List<String> targets, Map<String, Map<String, String>> fieldIndex) {
        for (String target : targets) {
            Map<String, String> fields = fieldIndex.get(target);
            if (fields != null && fields.containsKey(name)) {
                return fields.get(name);
            }
        }
        for (String target : targets) {
            String mapped = resolveFieldInHierarchy(name, target, fieldIndex, new HashSet<>());
            if (mapped != null) return mapped;
        }
        return name;
    }

    /**
     * Rewrite every {@code L<class>;} occurrence in a method descriptor using
     * the named-&gt;intermediary class table. Primitives and array prefixes are
     * left untouched.
     */
    private static String remapDescriptorTypes(String desc, Map<String, String> classMap) {
        if (desc == null || desc.indexOf('L') < 0) return desc == null ? "" : desc;

        StringBuilder out = new StringBuilder(desc.length());
        int pos = 0;
        while (pos < desc.length()) {
            char c = desc.charAt(pos);
            if (c != 'L') {
                out.append(c);
                pos++;
                continue;
            }
            int end = desc.indexOf(';', pos);
            if (end < 0) {
                out.append(desc, pos, desc.length());
                break;
            }
            String internal = desc.substring(pos + 1, end);
            out.append('L').append(classMap.getOrDefault(internal, internal)).append(';');
            pos = end + 1;
        }
        return out.toString();
    }

    private static String resolveMethod(String name, String desc, List<String> targets, Map<String, Map<String, Map<String, String>>> methodIndex) {
        for (String target : targets) {
            Map<String, Map<String, String>> methods = methodIndex.get(target);
            if (methods != null && methods.containsKey(name)) {
                Map<String, String> overloads = methods.get(name);
                if (!desc.isEmpty() && overloads.containsKey(desc)) {
                    return overloads.get(desc);
                }
                if (!overloads.isEmpty()) {
                    return overloads.values().iterator().next();
                }
            }
        }
        for (String target : targets) {
            String mapped = resolveMethodInHierarchy(name, desc, target, methodIndex, new HashSet<>());
            if (mapped != null) return mapped;
        }
        return name;
    }

    private static String resolveFieldInHierarchy(String name, String internalClassName, Map<String, Map<String, String>> fieldIndex, Set<String> visited) {
        if (internalClassName == null || visited.contains(internalClassName)) return null;
        visited.add(internalClassName);
        Map<String, String> fields = fieldIndex.get(internalClassName);
        if (fields != null && fields.containsKey(name)) {
            return fields.get(name);
        }

        return resolveFieldInHierarchy(name, getSuperClassName(internalClassName), fieldIndex, visited);
    }

    private static String resolveMethodInHierarchy(String name, String desc, String internalClassName, Map<String, Map<String, Map<String, String>>> methodIndex, Set<String> visited) {
        if (internalClassName == null || visited.contains(internalClassName)) return null;
        visited.add(internalClassName);
        Map<String, Map<String, String>> methods = methodIndex.get(internalClassName);
        if (methods != null && methods.containsKey(name)) {
            Map<String, String> overloads = methods.get(name);
            if (!desc.isEmpty() && overloads.containsKey(desc)) {
                return overloads.get(desc);
            }
            if (!overloads.isEmpty()) {
                return overloads.values().iterator().next();
            }
        }

        return resolveMethodInHierarchy(name, desc, getSuperClassName(internalClassName), methodIndex, visited);
    }

    private static String getSuperClassName(String internalClassName) {
        String resource = "/" + internalClassName + ".class";
        try (InputStream is = MixinRefmapGenerator.class.getResourceAsStream(resource)) {
            if (is == null) {
                return null;
            }
	        return new ClassReader(is).getSuperName();
        } catch (IOException e) {
            System.err.printf("DEBUG: Error reading %s: %1s%n",  resource, e.getMessage());
            return null;
        }
    }

    private static String remapMethodRef(String methodRef, List<String> targets, Map<String, Map<String, Map<String, String>>> methodIndex) {
        if ("<init>".equals(methodRef) || "<clinit>".equals(methodRef)) {
            return methodRef;
        }
        String methodName = methodRef;
        String methodDesc = "";
        int parenIdx = methodRef.indexOf('(');
        if (parenIdx > 0) {
            methodName = methodRef.substring(0, parenIdx);
            methodDesc = methodRef.substring(parenIdx);
        }
        String mapped = resolveMethod(methodName, methodDesc, targets, methodIndex);
        if (!mapped.equals(methodName)) {
            return mapped + methodDesc;
        }
        return methodRef;
    }

    private static Map<String, MixinInfo> scanMixinClasses(Path jarPath) throws IOException {
        Map<String, MixinInfo> mixins = new HashMap<>();

        try (JarInputStream jis = new JarInputStream(Files.newInputStream(jarPath))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (!name.endsWith(".class")) continue;

                byte[] classBytes = jis.readAllBytes();
                ClassNode classNode = new ClassNode();
                new ClassReader(classBytes).accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                List<String> targets = getMixinTargets(classNode);
                if (targets.isEmpty()) continue;

                String className = classNode.name.replace('/', '.');
                MixinInfo info = new MixinInfo(targets);

                if (classNode.fields != null) {
                    for (FieldNode field : classNode.fields) {
                        if (hasAnnotation(field.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")) {
                            info.shadowFields.add(new MixinInfo.ShadowField(field.name, field.desc));
                        }
                    }
                }

                if (classNode.methods != null) {
                    for (MethodNode method : classNode.methods) {
                        if (method.visibleAnnotations == null) continue;

                        for (AnnotationNode ann : method.visibleAnnotations) {
                            if (ann.desc == null) continue;
                            switch (ann.desc) {
                                case "Lorg/spongepowered/asm/mixin/Shadow;":
                                    String shadowName = getAnnotationValue(ann, "name");
                                    if (shadowName != null && !shadowName.isEmpty()) {
                                        info.shadowMethods.add(new MixinInfo.ShadowMethod(shadowName, method.desc));
                                    } else {
                                        info.shadowMethods.add(new MixinInfo.ShadowMethod(method.name, method.desc));
                                    }
                                    break;
                                case "Lorg/spongepowered/asm/mixin/gen/Accessor;":
                                    String accessorName = getAnnotationValue(ann, "value");
                                    if (accessorName != null && !accessorName.isEmpty()) {
                                        boolean isField = Character.isLowerCase(accessorName.charAt(0));
                                        info.accessors.add(new MixinInfo.AccessorRef(accessorName, isField));
                                    } else {
                                        String derived = deriveFieldName(method.name);
                                        if (derived != null) {
                                            info.accessors.add(new MixinInfo.AccessorRef(derived, true));
                                        }
                                    }
                                    break;
                                case "Lorg/spongepowered/asm/mixin/gen/Invoker;":
                                    String invokerName = getAnnotationValue(ann, "value");
                                    if (invokerName != null && !invokerName.isEmpty()) {
                                        info.invokers.add(new MixinInfo.InvokerRef(invokerName));
                                    } else {
                                        info.invokers.add(new MixinInfo.InvokerRef(method.name));
                                    }
                                    break;
                            }
                        }

                        if (method.visibleAnnotations != null) {
                            for (AnnotationNode ann : method.visibleAnnotations) {
                                if (ann.desc != null && ann.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")) {
                                    extractMethodRefs(ann, info);
                                }
                            }
                        }
                        if (method.invisibleAnnotations != null) {
                            for (AnnotationNode ann : method.invisibleAnnotations) {
                                if (ann.desc != null && ann.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")) {
                                    extractMethodRefs(ann, info);
                                }
                            }
                        }
                    }
                }

                if (!info.isEmpty()) {
                    mixins.put(className, info);
                }
            }
        }

        return mixins;
    }

    @SuppressWarnings("unchecked")
    private static void extractMethodRefs(AnnotationNode ann, MixinInfo info) {
        if (ann.values == null) return;
        for (int i = 0; i < ann.values.size(); i += 2) {
            String name = (String) ann.values.get(i);
            Object value = ann.values.get(i + 1);
            if ("method".equals(name)) {
                if (value instanceof String) {
                    info.injectMethods.add((String) value);
                } else if (value instanceof List) {
                    for (Object item : (List<?>) value) {
                        if (item instanceof String) {
                            info.injectMethods.add((String) item);
                        }
                    }
                }
            } else if ("at".equals(name)) {
                if (value instanceof AnnotationNode) {
                    extractAtTarget((AnnotationNode) value, info);
                } else if (value instanceof List) {
                    for (Object item : (List<?>) value) {
                        if (item instanceof AnnotationNode) {
                            extractAtTarget((AnnotationNode) item, info);
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void extractAtTarget(AnnotationNode atAnn, MixinInfo info) {
        if (atAnn.values == null) return;
        for (int i = 0; i < atAnn.values.size(); i += 2) {
            String name = (String) atAnn.values.get(i);
            Object value = atAnn.values.get(i + 1);
            if ("target".equals(name) && value instanceof String) {
                info.atTargets.add((String) value);
            }
        }
    }


    @SuppressWarnings("unchecked")
    private static List<String> getMixinTargets(ClassNode classNode) {
        List<String> targets = new ArrayList<>();
        for (List<AnnotationNode> annList : new List[]{classNode.visibleAnnotations, classNode.invisibleAnnotations}) {
            if (annList == null) continue;
            for (AnnotationNode ann : annList) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc)) continue;
                if (ann.values == null) break;
                for (int i = 0; i < ann.values.size(); i += 2) {
                    String name = (String) ann.values.get(i);
                    Object value = ann.values.get(i + 1);
                    if ("value".equals(name)) addTargetsFrom(value, targets);
                    else if ("targets".equals(name)) {
                        if (value instanceof List) {
                            for (Object item : (List<?>) value)
                                targets.add(item.toString().replace('.', '/'));
                        } else if (value instanceof String) {
                            targets.add(((String) value).replace('.', '/'));
                        }
                    }
                }
            }
        }
        return targets;
    }

    @SuppressWarnings("unchecked")
    private static void addTargetsFrom(Object value, List<String> targets) {
        if (value instanceof Type) {
            targets.add(((Type) value).getInternalName());
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof Type) targets.add(((Type) item).getInternalName());
                else if (item instanceof AnnotationNode) {
                    AnnotationNode ann = (AnnotationNode) item;
                    if (ann.values != null) {
                        for (int j = 0; j < ann.values.size(); j += 2) {
                            if ("value".equals(ann.values.get(j))) addTargetsFrom(ann.values.get(j + 1), targets);
                        }
                    }
                }
            }
        }
    }

    private static boolean hasAnnotation(List<AnnotationNode> anns, String desc) {
        if (anns == null) return false;
        for (AnnotationNode ann : anns) {
            if (desc.equals(ann.desc)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static String getAnnotationValue(AnnotationNode ann, String memberName) {
        if (ann.values == null) return null;
        for (int i = 0; i < ann.values.size(); i += 2) {
            if (memberName.equals(ann.values.get(i))) {
                Object value = ann.values.get(i + 1);
                return value instanceof String ? (String) value : null;
            }
        }
        return null;
    }

    private static String deriveFieldName(String methodName) {
        if (methodName == null || methodName.isEmpty()) return null;
        if (methodName.startsWith("get") && methodName.length() > 3)
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        if (methodName.startsWith("set") && methodName.length() > 3)
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        if (methodName.startsWith("is") && methodName.length() > 2)
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        return null;
    }

    private static final class MixinInfo {
        final List<String> targets;
        final List<ShadowField> shadowFields = new ArrayList<>();
        final List<ShadowMethod> shadowMethods = new ArrayList<>();
        final List<AccessorRef> accessors = new ArrayList<>();
        final List<InvokerRef> invokers = new ArrayList<>();
        final List<String> injectMethods = new ArrayList<>();
        final List<String> atTargets = new ArrayList<>();

        MixinInfo(List<String> targets) {
            this.targets = targets;
        }

        boolean isEmpty() {
            return shadowFields.isEmpty() && shadowMethods.isEmpty()
                    && accessors.isEmpty() && invokers.isEmpty()
                    && injectMethods.isEmpty() && atTargets.isEmpty();
        }
        
        record ShadowField(String name, String desc) {}
        record ShadowMethod(String name, String desc) {}
        record AccessorRef(String name, boolean isField) {}
        record InvokerRef(String name) {}
    }
}

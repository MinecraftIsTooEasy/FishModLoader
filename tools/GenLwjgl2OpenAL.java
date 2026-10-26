import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.ConstantValueEntry;
import java.lang.classfile.constantpool.DoubleEntry;
import java.lang.classfile.constantpool.FloatEntry;
import java.lang.classfile.constantpool.IntegerEntry;
import java.lang.classfile.constantpool.LongEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Generates the LWJGL 2.9.0 OpenAL API surface of {@code org.lwjgl.openal} from the original jar.
 *
 * <ul>
 *   <li>{@code AL10}, {@code AL11}, {@code EFX10}: complete facades. All constants come from the ConstantValue attributes of the
 *       class files (no class initialisation, so the values are exactly the LWJGL 2.9.0 ones) and every public static method
 *       delegates, with the identical name and descriptor, to {@code net.xiaoyu233.fml.vulkan.audio.OpenALCore}.</li>
 *   <li>{@code ALC10}, {@code ALC11}: hand-written classes (they keep the ALCdevice/ALCcontext registries of LWJGL 2). Only the
 *       block between the {@code <generated-constants>} markers is rewritten.</li>
 * </ul>
 *
 * <p>Usage: {@code java tools/GenLwjgl2OpenAL.java <lwjgl-2.9.0.jar> <src/main/java>}. The output is reproducible: running it
 * twice leaves the working tree unchanged. Hand-written: AL, ALCdevice, ALCcontext, EFXUtil, OpenALException, Util.
 */
public class GenLwjgl2OpenAL {
    static final String PKG = "org.lwjgl.openal";
    static final String IMPL = "net.xiaoyu233.fml.vulkan.audio.OpenALCore";
    static final List<String> FACADES = List.of("AL10", "AL11", "EFX10");
    static final List<String> CONSTANTS_ONLY = List.of("ALC10", "ALC11");
    static final String BEGIN = "// <generated-constants>";
    static final String END = "// </generated-constants>";

    static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java tools/GenLwjgl2OpenAL.java <lwjgl-2.9.0.jar> <src/main/java>");
            System.exit(1);
        }
        Path jar = Path.of(args[0]);
        Path pkgDir = Path.of(args[1]).resolve(PKG.replace('.', '/'));
        Files.createDirectories(pkgDir);
        int constants = 0, methods = 0;
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            for (String cls : FACADES) {
                ClassModel cm = parse(zf, cls);
                StringBuilder sb = new StringBuilder();
                int[] counts = genFacade(cls, cm, sb);
                constants += counts[0];
                methods += counts[1];
                Files.writeString(pkgDir.resolve(cls + ".java"), sb.toString());
            }
            for (String cls : CONSTANTS_ONLY) {
                ClassModel cm = parse(zf, cls);
                StringBuilder sb = new StringBuilder();
                constants += genConstants(cm, sb);
                patchConstants(pkgDir.resolve(cls + ".java"), sb.toString());
            }
        }
        for (String r : report) System.out.println(r);
        System.out.println("generated " + FACADES.size() + " facades (" + methods + " methods), " + constants + " constants");
    }

    static ClassModel parse(ZipFile zf, String cls) throws IOException {
        var e = zf.getEntry("org/lwjgl/openal/" + cls + ".class");
        if (e == null) throw new IllegalStateException("missing in LWJGL 2 jar: " + cls);
        return ClassFile.of().parse(zf.getInputStream(e).readAllBytes());
    }

    // ---------------------------------------------------------------- constants

    static String jtype(ClassDesc cd) {
        String d = cd.descriptorString();
        int dims = 0;
        while (d.startsWith("[")) { dims++; d = d.substring(1); }
        String base = switch (d) {
            case "I" -> "int"; case "J" -> "long"; case "F" -> "float"; case "D" -> "double"; case "Z" -> "boolean";
            case "B" -> "byte"; case "S" -> "short"; case "C" -> "char"; case "V" -> "void";
            default -> {
                String n = d.substring(1, d.length() - 1).replace('/', '.').replace('$', '.');
                if (n.startsWith("java.nio.") && n.indexOf('.', 9) < 0) yield n.substring(9);
                if (n.equals("java.lang.String")) yield "String";
                yield n;
            }
        };
        return base + "[]".repeat(dims);
    }

    static String literal(ConstantValueEntry cv) {
        return switch (cv) {
            case IntegerEntry i -> {
                int v = i.intValue();
                yield v < 0 ? Integer.toString(v) : v < 10 ? Integer.toString(v) : "0x" + Integer.toHexString(v).toUpperCase();
            }
            case LongEntry l -> Long.toString(l.longValue()) + "L";
            case FloatEntry f -> {
                float v = f.floatValue();
                yield Float.isNaN(v) ? "Float.NaN" : Float.isInfinite(v) ? (v > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY") : Float.toString(v) + "f";
            }
            case DoubleEntry d -> Double.toString(d.doubleValue());
            case StringEntry s -> "\"" + s.stringValue().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            default -> throw new IllegalArgumentException(cv.toString());
        };
    }

    /** Appends every public static constant of the class; returns how many were written. */
    static int genConstants(ClassModel cm, StringBuilder sb) {
        int n = 0;
        for (FieldModel f : cm.fields()) {
            if (!f.flags().has(AccessFlag.PUBLIC) || !f.flags().has(AccessFlag.STATIC)) continue;
            var cv = f.findAttribute(java.lang.classfile.Attributes.constantValue());
            if (cv.isEmpty()) { report.add("non-constant static field skipped: " + cm.thisClass().asInternalName() + "." + f.fieldName()); continue; }
            ConstantValueEntry c = ((ConstantValueAttribute) cv.get()).constant();
            sb.append("    public static final ").append(jtype(f.fieldTypeSymbol())).append(' ').append(f.fieldName().stringValue())
                    .append(" = ").append(literal(c)).append(";\n");
            n++;
        }
        return n;
    }

    static void patchConstants(Path file, String constants) throws IOException {
        if (!Files.exists(file)) throw new IllegalStateException("hand-written class missing: " + file);
        List<String> lines = Files.readAllLines(file);
        int b = -1, e = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.equals(BEGIN)) b = i;
            else if (t.equals(END)) e = i;
        }
        if (b < 0 || e < b) throw new IllegalStateException(file + ": markers '" + BEGIN + "' / '" + END + "' not found");
        List<String> out = new ArrayList<>(lines.subList(0, b + 1));
        for (String l : constants.split("\n")) out.add(l);
        out.addAll(lines.subList(e, lines.size()));
        Files.writeString(file, String.join("\n", out) + "\n");
    }

    // ---------------------------------------------------------------- facades

    static String role(String base) {
        if (base.contains("AuxiliaryEffectSlot")) return "slot";
        if (base.contains("Effect")) return "effect";
        if (base.contains("Filter")) return "filter";
        if (base.contains("Source")) return "source";
        if (base.contains("Buffer")) return "buffer";
        return null;
    }

    static String plural(String role) {
        return role == null ? "values" : role + "s";
    }

    /** Cosmetic parameter names (the LWJGL 2 class files carry no local variable tables). */
    static List<String> paramNames(String method, MethodTypeDesc d) {
        String base = method.startsWith("al") ? method.substring(2) : method;
        String role = role(base);
        boolean gen = base.startsWith("Gen");
        boolean del = base.startsWith("Delete");
        boolean multi = base.matches("Source(Play|Pause|Stop|Rewind)");
        List<String> names = new ArrayList<>();
        int scalar = 0;
        boolean roleUsed = false;
        for (int i = 0; i < d.parameterCount(); i++) {
            String t = d.parameterType(i).descriptorString();
            String name;
            if (t.equals("Ljava/lang/String;")) {
                name = base.equals("GetEnumValue") ? "ename" : base.equals("IsExtensionPresent") ? "fname" : "name";
            } else if (t.startsWith("Ljava/nio/")) {
                if (gen || del || multi) name = plural(role);
                else if (base.contains("QueueBuffers") || base.contains("UnqueueBuffers")) name = "buffers";
                else if (base.equals("BufferData")) name = "data";
                else name = "values";
            } else if (!roleUsed && role != null && !gen && t.equals("I")) {
                roleUsed = true;
                name = role;
            } else if (base.equals("BufferData") && t.equals("I")) {
                name = scalar++ == 0 ? "format" : "frequency";
            } else if (base.equals("Enable") || base.equals("Disable") || base.equals("IsEnabled")) {
                name = "capability";
            } else if (base.equals("DistanceModel")) {
                name = "distanceModel";
            } else if (t.equals("I") && base.startsWith("Get") && d.parameterCount() == 1) {
                name = "pname";
            } else if (t.equals("I") && scalar == 0 && d.parameterCount() > 1 && !base.startsWith("SourceQueueBuffers") && !base.startsWith("SourceUnqueueBuffers")) {
                scalar++;
                name = "pname";
            } else if (base.startsWith("SourceQueueBuffers") && t.equals("I")) {
                name = "buffer";
            } else {
                scalar++;
                name = d.parameterCount() > 0 && (t.equals("F") || t.equals("D") || t.equals("I")) ? "value" : "arg" + i;
            }
            while (names.contains(name)) name = name + "_";
            names.add(name);
        }
        // value / value_ / value__  ->  v1 v2 v3 for the 3-component setters
        if (base.endsWith("3f") || base.endsWith("3i")) {
            int v = 1;
            for (int i = 0; i < names.size(); i++) if (names.get(i).startsWith("value")) names.set(i, "v" + v++);
        }
        return names;
    }

    /** Writes a facade class, returns {constants, methods}. */
    static int[] genFacade(String cls, ClassModel cm, StringBuilder sb) {
        sb.append("package ").append(PKG).append(";\n\n");
        sb.append("import java.nio.*;\n");
        sb.append("import ").append(IMPL).append(";\n\n");
        sb.append("// GENERATED by tools/GenLwjgl2OpenAL.java from LWJGL 2.9.0 - do not edit by hand.\n");
        sb.append("// LWJGL 2 API facade; every function delegates to the Java 25 FFM implementation in ").append(IMPL).append(".\n");
        sb.append("@SuppressWarnings(\"unused\")\n");
        sb.append("public final class ").append(cls).append(" {\n");
        int constants = genConstants(cm, sb);
        sb.append("\n    private ").append(cls).append("() {\n    }\n");
        int methods = 0;
        for (MethodModel m : cm.methods()) {
            if (!m.flags().has(AccessFlag.PUBLIC) || !m.flags().has(AccessFlag.STATIC)) continue;
            if (m.flags().has(AccessFlag.SYNTHETIC) || m.flags().has(AccessFlag.BRIDGE)) continue;
            String name = m.methodName().stringValue();
            MethodTypeDesc desc = m.methodTypeSymbol();
            List<String> pn = paramNames(name, desc);
            StringBuilder params = new StringBuilder(), argList = new StringBuilder();
            for (int i = 0; i < desc.parameterCount(); i++) {
                if (i > 0) { params.append(", "); argList.append(", "); }
                params.append(jtype(desc.parameterType(i))).append(' ').append(pn.get(i));
                argList.append(pn.get(i));
            }
            String ret = jtype(desc.returnType());
            sb.append("\n    public static ").append(ret).append(' ').append(name).append('(').append(params).append(") {\n");
            sb.append("        ").append(ret.equals("void") ? "" : "return ").append("OpenALCore.").append(name).append('(').append(argList).append(");\n");
            sb.append("    }\n");
            methods++;
        }
        sb.append("}\n");
        return new int[]{constants, methods};
    }
}

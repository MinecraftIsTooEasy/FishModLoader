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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Generates the LWJGL 2 OpenGL API surface (org.lwjgl.opengl.GLxx / ARB* / EXT* / NV* classes and
 * ContextCapabilities) as thin static facades over the FishModLoader Vulkan backend.
 *
 * <p>Every public static method of the original LWJGL 2.9.0 class is reproduced with an identical
 * descriptor so that the game and mods link against it unchanged. Methods listed in {@link #OWNERS}
 * delegate to {@code net.xiaoyu233.fml.vulkan.gl.<Owner>}; everything else logs once through
 * {@code GLUnsupported} and returns a neutral default.
 *
 * <p>Usage: {@code java tools/GenLwjgl2Compat.java <lwjgl-2.9.0.jar> <gl.xml or -> <src/main/java> [--skeletons]}
 * <br>{@code --skeletons} additionally (re)writes the owner facade skeletons. Never pass it once the
 * facades contain real implementations.
 */
public class GenLwjgl2Compat {
    static final String OUT_PKG = "org.lwjgl.opengl";
    static final String IMPL_PKG = "net.xiaoyu233.fml.vulkan.gl";

    static final List<String> CLASSES = List.of(
            "GL11", "GL12", "GL13", "GL14", "GL15", "GL20", "GL21", "GL30", "GL31", "GL32", "GL33",
            "ARBMultitexture", "ARBOcclusionQuery", "ARBBufferObject", "ARBVertexBufferObject", "ARBPixelBufferObject",
            "ARBVertexShader", "ARBFragmentShader", "ARBShaderObjects", "ARBProgram", "ARBVertexProgram", "ARBFragmentProgram",
            "ARBFramebufferObject", "ARBTextureCompression", "ARBTextureCubeMap", "ARBTextureEnvCombine", "ARBTransposeMatrix",
            "ARBVertexArrayObject", "ARBMapBufferRange", "ARBCopyBuffer", "ARBDrawInstanced", "ARBInstancedArrays",
            "ARBUniformBufferObject", "ARBMultisample", "ARBPointParameters", "ARBDepthTexture", "ARBShadow",
            "ARBTextureMirroredRepeat", "ARBTextureBorderClamp", "ARBDrawBuffers", "ARBHalfFloatVertex", "ARBTextureFloat",
            "ARBTextureRg", "ARBVertexArrayBgra", "ARBDrawElementsBaseVertex", "ARBBlendFuncExtended",
            "EXTBlendFuncSeparate", "EXTBlendColor", "EXTBlendMinmax", "EXTBlendSubtract", "EXTBlendEquationSeparate",
            "EXTFramebufferObject", "EXTFramebufferBlit", "EXTFramebufferMultisample", "EXTPackedDepthStencil",
            "EXTTextureFilterAnisotropic", "EXTBgra", "EXTAbgr", "EXTRescaleNormal", "EXTSecondaryColor", "EXTFogCoord",
            "EXTMultiDrawArrays", "EXTDrawRangeElements", "EXTCompiledVertexArray", "EXTTextureEnvCombine",
            "EXTTextureLODBias", "EXTTextureMirrorClamp", "EXTPackedPixels", "EXTSeparateSpecularColor",
            "EXTTextureSRGB", "EXTVertexArrayBgra", "EXTStencilWrap", "EXTPointParameters",
            "NVFogDistance", "NVLightMaxExponent", "NVTexgenReflection", "NVBlendSquare");

    /** implName (vendor suffix stripped) -> owner facade class. */
    static final Map<String, String> OWNERS = new HashMap<>();

    static void own(String owner, String names) {
        for (String n : names.trim().split("\\s+")) {
            if (OWNERS.put(n, owner) != null) throw new IllegalStateException("duplicate owner for " + n);
        }
    }

    static {
        own("GLStateImpl", """
                glEnable glDisable glIsEnabled glBlendFunc glBlendFuncSeparate glBlendEquation glBlendEquationSeparate glBlendColor
                glAlphaFunc glDepthFunc glDepthMask glDepthRange glColorMask glCullFace glFrontFace glShadeModel glPolygonOffset
                glPolygonMode glLineWidth glPointSize glLogicOp glViewport glScissor glClearColor glClearDepth glClearStencil glClear
                glStencilFunc glStencilOp glStencilMask glHint glDrawBuffer glReadBuffer
                glMatrixMode glLoadIdentity glPushMatrix glPopMatrix glTranslatef glTranslated glRotatef glRotated glScalef glScaled
                glMultMatrix glLoadMatrix glMultTransposeMatrix glLoadTransposeMatrix glOrtho glFrustum
                glColor3b glColor3ub glColor3f glColor3d glColor4b glColor4ub glColor4f glColor4d
                glNormal3b glNormal3f glNormal3d glNormal3i
                glTexCoord1f glTexCoord1d glTexCoord2f glTexCoord2d glTexCoord3f glTexCoord3d glTexCoord4f glTexCoord4d
                glMultiTexCoord1f glMultiTexCoord1d glMultiTexCoord2f glMultiTexCoord2d glMultiTexCoord3f glMultiTexCoord3d
                glMultiTexCoord4f glMultiTexCoord4d
                glFogf glFogi glFog glLightf glLighti glLight glLightModelf glLightModeli glLightModel
                glMaterialf glMateriali glMaterial glColorMaterial glTexEnvf glTexEnvi glTexEnv glTexGenf glTexGend glTexGeni glTexGen
                glActiveTexture glGetFloat glGetInteger glGetBoolean glGetDouble glGetString glGetError
                glPushAttrib glPopAttrib glFlush glFinish glGetLight glGetMaterial glGetTexEnv glGetTexGen
                """);
        own("GLDrawImpl", """
                glBegin glEnd glVertex2f glVertex2d glVertex2i glVertex3f glVertex3d glVertex3i glVertex4f glVertex4d glVertex4i
                glRectf glRectd glRecti glEnableClientState glDisableClientState glClientActiveTexture
                glVertexPointer glColorPointer glTexCoordPointer glNormalPointer glInterleavedArrays glArrayElement
                glDrawArrays glDrawElements glDrawRangeElements glMultiDrawArrays
                glGenLists glNewList glEndList glCallList glCallLists glDeleteLists glIsList glListBase
                glGenBuffers glBindBuffer glBufferData glBufferSubData glDeleteBuffers glIsBuffer glMapBuffer glUnmapBuffer
                glGetBufferParameter glGetBufferParameteri glGetBufferSubData glCopyBufferSubData
                glGenQueries glDeleteQueries glIsQuery glBeginQuery glEndQuery glGetQuery glGetQueryi
                glGetQueryObject glGetQueryObjecti glGetQueryObjectu glGetQueryObjectui
                glPushClientAttrib glPopClientAttrib glGenVertexArrays glBindVertexArray glDeleteVertexArrays glIsVertexArray
                glEnableVertexAttribArray glDisableVertexAttribArray glVertexAttribPointer
                """);
        own("GLTexImpl", """
                glGenTextures glDeleteTextures glBindTexture glIsTexture glTexImage2D glTexSubImage2D glCopyTexImage2D
                glCopyTexSubImage2D glTexParameteri glTexParameterf glTexParameter glGetTexParameter glGetTexParameteri
                glGetTexParameterf glGetTexLevelParameter glGetTexLevelParameteri glGetTexLevelParameterf glGetTexImage
                glPixelStorei glPixelStoref glReadPixels glGenerateMipmap
                """);
    }

    static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
            "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "var", "record");

    static final String[] SUFFIXES = {"", "f", "i", "d", "s", "b", "ub", "us", "ui", "fv", "iv", "dv", "sv", "bv", "ubv",
            "usv", "uiv", "v", "x", "xv", "i64v", "ui64v"};

    record Sig(String owner, String name, MethodTypeDesc desc, List<String> paramNames) {}

    static Map<String, List<List<String>>> glXmlParams = new HashMap<>();
    static final Map<String, Sig> ownerSigs = new TreeMap<>();
    /** owner.impl + parameter descriptor -> return descriptor of the first owner method registered for it. */
    static final Map<String, String> ownerReturns = new HashMap<>();
    static final List<String> report = new ArrayList<>();
    static Set<String> generated = new LinkedHashSet<>();

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        if (!args[1].equals("-")) parseGlXml(Files.readString(Path.of(args[1])));
        Path out = Path.of(args[2]);
        boolean skeletons = args.length > 3 && args[3].equals("--skeletons");

        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            for (String c : CLASSES) {
                var e = zf.getEntry("org/lwjgl/opengl/" + c + ".class");
                if (e == null) { report.add("missing in LWJGL 2 jar: " + c); continue; }
                classes.put(c, zf.getInputStream(e).readAllBytes());
            }
            generated.addAll(classes.keySet());
            Path pkgDir = out.resolve(OUT_PKG.replace('.', '/'));
            Files.createDirectories(pkgDir);
            for (var en : classes.entrySet()) {
                Files.writeString(pkgDir.resolve(en.getKey() + ".java"), genClass(en.getKey(), ClassFile.of().parse(en.getValue())));
            }
            var capsEntry = zf.getEntry("org/lwjgl/opengl/ContextCapabilities.class");
            Files.writeString(pkgDir.resolve("ContextCapabilities.java"), genCaps(ClassFile.of().parse(zf.getInputStream(capsEntry).readAllBytes())));
        }
        if (skeletons) {
            Path implDir = out.resolve(IMPL_PKG.replace('.', '/'));
            Files.createDirectories(implDir);
            for (String owner : Set.of("GLStateImpl", "GLDrawImpl", "GLTexImpl")) {
                Files.writeString(implDir.resolve(owner + ".java"), genOwner(owner));
            }
        }
        for (String r : report) System.out.println(r);
        System.out.println("generated " + classes.size() + " classes, " + ownerSigs.size() + " owner methods");
    }

    // ---------------------------------------------------------------- gl.xml

    static void parseGlXml(String xml) {
        Pattern cmd = Pattern.compile("<command[^>]*>(.*?)</command>", Pattern.DOTALL);
        Pattern name = Pattern.compile("<proto[^>]*>.*?<name>([^<]+)</name>.*?</proto>", Pattern.DOTALL);
        Pattern param = Pattern.compile("<param[^>]*>.*?<name>([^<]+)</name>.*?</param>", Pattern.DOTALL);
        Matcher m = cmd.matcher(xml);
        while (m.find()) {
            String body = m.group(1);
            Matcher nm = name.matcher(body);
            if (!nm.find()) continue;
            List<String> ps = new ArrayList<>();
            Matcher pm = param.matcher(body);
            while (pm.find()) ps.add(pm.group(1));
            glXmlParams.computeIfAbsent(nm.group(1), k -> new ArrayList<>()).add(ps);
        }
    }

    static final Set<String> DROPPABLE = Set.of("n", "count", "size", "bufSize", "length", "params", "data", "type", "imageSize");

    static boolean isBuffer(ClassDesc t) {
        String d = t.descriptorString();
        return d.startsWith("Ljava/nio/") || d.equals("Lorg/lwjgl/PointerBuffer;");
    }

    /** Lower is better; penalises names that contradict the LWJGL 2 parameter type. */
    static int score(List<String> names, MethodTypeDesc desc) {
        int s = 0;
        for (int i = 0; i < names.size(); i++) {
            String p = names.get(i);
            ClassDesc t = desc.parameterType(i);
            boolean buf = isBuffer(t);
            if (buf && (p.equals("size") || p.equals("n") || p.equals("count") || p.equals("type") || p.equals("target"))) s += 3;
            if (!buf && t.descriptorString().length() == 1 && (p.equals("data") || p.equals("lists") || p.equals("params") || p.equals("pointer"))) s += 2;
        }
        return s;
    }

    /** LWJGL drops count/size/out parameters relative to the C API; try removing up to two droppable params. */
    static List<List<String>> trims(List<String> c, int n) {
        List<List<String>> res = new ArrayList<>();
        if (c.size() == n) { res.add(c); return res; }
        for (int i = 0; i < c.size(); i++) {
            if (!DROPPABLE.contains(c.get(i))) continue;
            List<String> one = new ArrayList<>(c);
            one.remove(i);
            if (one.size() == n) res.add(one);
            for (int j = i; j < one.size(); j++) {
                if (!DROPPABLE.contains(one.get(j))) continue;
                List<String> two = new ArrayList<>(one);
                two.remove(j);
                if (two.size() == n) res.add(two);
            }
        }
        return res;
    }

    static List<String> paramNames(String lwjglName, String implName, MethodTypeDesc desc) {
        int n = desc.parameterCount();
        List<String> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String base : new String[]{lwjglName, implName}) {
            for (String suf : SUFFIXES) {
                List<List<String>> cands = glXmlParams.get(base + suf);
                if (cands == null) continue;
                for (List<String> c : cands) {
                    for (List<String> t : trims(c, n)) {
                        int sc = score(t, desc) + (t.size() == c.size() ? 0 : 1);
                        if (sc < bestScore) { bestScore = sc; best = t; }
                    }
                }
            }
            if (best != null && bestScore == 0) break;
        }
        List<String> res = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            String p = best != null ? best.get(i) : "a" + i;
            p = p.replaceAll("[^A-Za-z0-9_]", "_");
            if (JAVA_KEYWORDS.contains(p)) p = p + "_";
            // LWJGL 2 uses a trailing long for buffer-object offsets
            ClassDesc t = desc.parameterType(i);
            if (t.descriptorString().equals("J") && best != null && (p.equals("pixels") || p.equals("pointer") || p.equals("indices") || p.equals("data")))
                p = p + "_buffer_offset";
            while (!used.add(p)) p = p + "_";
            res.add(p);
        }
        return res;
    }

    // ---------------------------------------------------------------- class generation

    static String implName(String cls, String name) {
        for (String v : new String[]{"ARB", "EXT", "NV", "ATI", "AMD", "APPLE", "KHR"}) {
            if (cls.startsWith(v) && name.endsWith(v)) return name.substring(0, name.length() - v.length());
        }
        return name;
    }

    static boolean typeAllowed(ClassDesc cd) {
        String d = cd.descriptorString();
        while (d.startsWith("[")) d = d.substring(1);
        if (d.length() == 1) return true;
        String in = d.substring(1, d.length() - 1);
        if (in.startsWith("java/")) return true;
        if (in.equals("org/lwjgl/PointerBuffer")) return true;
        if (in.startsWith("org/lwjgl/opengl/")) return generated.contains(in.substring("org/lwjgl/opengl/".length()));
        return false;
    }

    static String jtype(ClassDesc cd) {
        String d = cd.descriptorString();
        int dims = 0;
        while (d.startsWith("[")) { dims++; d = d.substring(1); }
        String base = switch (d) {
            case "I" -> "int"; case "J" -> "long"; case "F" -> "float"; case "D" -> "double"; case "Z" -> "boolean";
            case "B" -> "byte"; case "S" -> "short"; case "C" -> "char"; case "V" -> "void";
            default -> d.substring(1, d.length() - 1).replace('/', '.').replace('$', '.');
        };
        return base + "[]".repeat(dims);
    }

    static String defaultReturn(ClassDesc ret) {
        return switch (ret.descriptorString()) {
            case "V" -> "";
            case "Z" -> " false";
            case "I", "B", "S", "C" -> " 0";
            case "J" -> " 0L";
            case "F" -> " 0f";
            case "D" -> " 0d";
            default -> " null";
        };
    }

    static String literal(ConstantValueEntry cv) {
        return switch (cv) {
            case IntegerEntry i -> "0x" + Integer.toHexString(i.intValue()).toUpperCase();
            case LongEntry l -> "0x" + Long.toHexString(l.longValue()).toUpperCase() + "L";
            case FloatEntry f -> Float.isNaN(f.floatValue()) ? "Float.NaN" : Float.toString(f.floatValue()) + "f";
            case DoubleEntry d -> Double.toString(d.doubleValue());
            case StringEntry s -> "\"" + s.stringValue().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            default -> throw new IllegalArgumentException(cv.toString());
        };
    }

    static String genClass(String cls, ClassModel cm) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(OUT_PKG).append(";\n\n");
        sb.append("// GENERATED by tools/GenLwjgl2Compat.java from LWJGL 2.9.0 - do not edit by hand.\n");
        sb.append("// LWJGL 2 API facade; implemented functions delegate to the Vulkan GL emulation in ").append(IMPL_PKG).append(".\n");
        sb.append("@SuppressWarnings({\"unused\", \"deprecation\"})\n");
        String sup = cm.superclass().map(s -> s.asInternalName()).orElse("java/lang/Object");
        boolean isFinal = cm.flags().has(AccessFlag.FINAL);
        sb.append("public ").append(isFinal ? "final " : "").append("class ").append(cls);
        if (sup.startsWith("org/lwjgl/opengl/")) sb.append(" extends ").append(sup.substring("org/lwjgl/opengl/".length()));
        sb.append(" {\n");

        for (FieldModel f : cm.fields()) {
            if (!f.flags().has(AccessFlag.PUBLIC) || !f.flags().has(AccessFlag.STATIC)) continue;
            var cv = f.findAttribute(java.lang.classfile.Attributes.constantValue());
            if (cv.isEmpty()) { report.add("non-constant static field skipped: " + cls + "." + f.fieldName()); continue; }
            sb.append("    public static final ").append(jtype(f.fieldTypeSymbol())).append(' ').append(f.fieldName().stringValue())
                    .append(" = ").append(literal(cv.get().constant())).append(";\n");
        }
        sb.append('\n');

        boolean hasPublicCtor = cm.methods().stream().anyMatch(m -> m.methodName().equalsString("<init>") && (m.flags().has(AccessFlag.PUBLIC) || m.flags().has(AccessFlag.PROTECTED)));
        sb.append("    ").append(hasPublicCtor ? "protected " : "private ").append(cls).append("() {\n    }\n\n");

        for (MethodModel m : cm.methods()) {
            if (!m.flags().has(AccessFlag.PUBLIC) || !m.flags().has(AccessFlag.STATIC)) continue;
            if (m.flags().has(AccessFlag.SYNTHETIC) || m.flags().has(AccessFlag.BRIDGE)) continue;
            String name = m.methodName().stringValue();
            MethodTypeDesc desc = m.methodTypeSymbol();
            boolean ok = typeAllowed(desc.returnType());
            for (var p : desc.parameterList()) ok &= typeAllowed(p);
            if (!ok) { report.add("skipped (foreign type): " + cls + "." + name + desc.descriptorString()); continue; }
            String impl = implName(cls, name);
            String owner = OWNERS.get(impl);
            if (owner != null) {
                // The JVM allows overloads that differ only by return type (GL15.glGetBufferParameter(II)I vs
                // GL32.glGetBufferParameter(II)J) but javac does not: the first one (lowest GL version) is owned, later ones
                // that would clash in the owner facade become unsupported no-ops.
                String full = desc.descriptorString();
                String paramKey = owner + "." + impl + full.substring(0, full.indexOf(')') + 1);
                String firstReturn = ownerReturns.putIfAbsent(paramKey, desc.returnType().descriptorString());
                if (firstReturn != null && !firstReturn.equals(desc.returnType().descriptorString())) {
                    report.add("owner overload differs only by return type, emitted as unsupported: " + cls + "." + name + full);
                    owner = null;
                }
            }
            List<String> pn = paramNames(name, impl, desc);
            StringBuilder params = new StringBuilder(), argList = new StringBuilder();
            for (int i = 0; i < desc.parameterCount(); i++) {
                if (i > 0) { params.append(", "); argList.append(", "); }
                params.append(jtype(desc.parameterType(i))).append(' ').append(pn.get(i));
                argList.append(pn.get(i));
            }
            String ret = jtype(desc.returnType());
            sb.append("    public static ").append(ret).append(' ').append(name).append('(').append(params).append(") {\n");
            if (owner != null) {
                ownerSigs.putIfAbsent(owner + "." + impl + desc.descriptorString(), new Sig(owner, impl, desc, pn));
                sb.append("        ").append(ret.equals("void") ? "" : "return ").append(IMPL_PKG).append('.').append(owner).append('.')
                        .append(impl).append('(').append(argList).append(");\n");
            } else {
                sb.append("        ").append(IMPL_PKG).append(".GLUnsupported.call(\"").append(cls).append('.').append(name).append("\");\n");
                if (!ret.equals("void")) sb.append("        return").append(defaultReturn(desc.returnType())).append(";\n");
            }
            sb.append("    }\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    static String genCaps(ClassModel cm) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(OUT_PKG).append(";\n\n");
        sb.append("// GENERATED by tools/GenLwjgl2Compat.java from LWJGL 2.9.0 - do not edit by hand.\n");
        sb.append("// Capability flags are decided by ").append(IMPL_PKG).append(".GLCapabilities.\n");
        sb.append("@SuppressWarnings(\"unused\")\npublic class ContextCapabilities {\n");
        List<String> names = new ArrayList<>();
        for (FieldModel f : cm.fields()) {
            if (f.flags().has(AccessFlag.PUBLIC) && !f.flags().has(AccessFlag.STATIC) && f.fieldTypeSymbol().descriptorString().equals("Z")) {
                names.add(f.fieldName().stringValue());
            }
        }
        for (String n : names) sb.append("    public final boolean ").append(n).append(";\n");
        sb.append("\n    ContextCapabilities(java.util.Set<String> supported) {\n");
        for (String n : names) sb.append("        this.").append(n).append(" = supported.contains(\"").append(n).append("\");\n");
        sb.append("    }\n}\n");
        return sb.toString();
    }

    static String genOwner(String owner) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(IMPL_PKG).append(";\n\n");
        sb.append("/**\n * GL entry points owned by this facade (see ../docs/ARCHITECTURE.md, outside the repo). The LWJGL 2 compat classes in\n");
        sb.append(" * org.lwjgl.opengl delegate here with the exact LWJGL 2 descriptors (vendor suffixes stripped).\n */\n");
        sb.append("@SuppressWarnings(\"unused\")\npublic final class ").append(owner).append(" {\n    private ").append(owner).append("() {\n    }\n\n");
        for (Sig s : ownerSigs.values()) {
            if (!s.owner().equals(owner)) continue;
            MethodTypeDesc desc = s.desc();
            StringBuilder params = new StringBuilder();
            for (int i = 0; i < desc.parameterCount(); i++) {
                if (i > 0) params.append(", ");
                params.append(jtype(desc.parameterType(i))).append(' ').append(s.paramNames().get(i));
            }
            String ret = jtype(desc.returnType());
            sb.append("    public static ").append(ret).append(' ').append(s.name()).append('(').append(params).append(") {\n");
            sb.append("        GLUnsupported.todo(\"").append(owner).append('.').append(s.name()).append("\");\n");
            if (!ret.equals("void")) sb.append("        return").append(defaultReturn(desc.returnType())).append(";\n");
            sb.append("    }\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}

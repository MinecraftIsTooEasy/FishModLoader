package org.moddedmite.fish.faloom;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects self-recursive @Inject, @Shadow descriptor mismatches, and missing @Inject targets. */
public final class VerifyInjections {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: VerifyInjections <mite-named.jar> <mixin-src-root>");
            System.exit(1);
        }
        Path jarPath = Path.of(args[0]);
        Path srcRoot = Path.of(args[1]);
        if (!Files.exists(jarPath)) { System.err.println("Not found: " + jarPath); System.exit(1); }
        if (!Files.exists(srcRoot)) { System.err.println("Not found: " + srcRoot); System.exit(1); }

        Map<String, Set<String>> jarMethods = new HashMap<>();
        Map<String, Map<String, String>> jarMethodDescs = new HashMap<>();
        Map<String, String> superclasses = new HashMap<>();
        buildJarIndex(jarPath, jarMethods, jarMethodDescs, superclasses);

        List<String> recursive = new ArrayList<>();
        List<String> mismatch  = new ArrayList<>();
        List<String> noTarget  = new ArrayList<>();

        Files.walk(srcRoot)
            .filter(p -> p.toString().endsWith(".java"))
            .sorted()
            .forEach(p -> {
                try { analyzeFile(p, srcRoot, jarMethods, jarMethodDescs, superclasses,
                                  recursive, mismatch, noTarget);
                } catch (IOException e) { System.err.println("WARN: " + p + ": " + e.getMessage()); }
            });

        printSection("Self-recursive @Inject (guaranteed StackOverflowError)", recursive);
        printSection("@Shadow return type disagrees with the jar", mismatch);
        printSection("@Inject target missing on the MITE class", noTarget);

        int total = recursive.size() + mismatch.size() + noTarget.size();
        System.out.println();
        System.out.println(total == 0 ? "No injection defects found." : "DEFECTS: " + total);
        System.exit(total > 0 ? 1 : 0);
    }

    private static void buildJarIndex(Path jarPath,
                                      Map<String, Set<String>> methods,
                                      Map<String, Map<String, String>> descs,
                                      Map<String, String> supers) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream().filter(e -> e.getName().endsWith(".class")).forEach(entry -> {
                try {
                    byte[] bytes = jar.getInputStream(entry).readAllBytes();
                    String owner = entry.getName().replace(".class", "");
                    ClassReader cr = new ClassReader(bytes);
                    Set<String> ms = new HashSet<>();
                    Map<String, String> ds = new HashMap<>();
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public void visit(int v, int a, String n, String sig, String sup, String[] i) {
                            if (sup != null) supers.put(n, sup);
                        }
                        @Override public MethodVisitor visitMethod(int a, String name, String desc, String sig, String[] ex) {
                            ms.add(name);
                            ms.add(name + desc);
                            ds.merge(name, desc, (x, y) -> x);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                    methods.put(owner, ms);
                    descs.put(owner, ds);
                } catch (IOException e) { /* skip */ }
            });
        }
    }

    private static boolean methodExists(String internalName, String methodName,
                                        Map<String, Set<String>> methods,
                                        Map<String, String> supers) {
        String cur = internalName;
        for (int g = 0; cur != null && g < 24; g++) {
            Set<String> ms = methods.get(cur);
            if (ms != null && ms.contains(methodName)) return true;
            cur = supers.get(cur);
        }
        return false;
    }

    private static final Pattern MIXIN_PAT    = Pattern.compile("@Mixin\\(([\\w.$]+)\\.class");
    private static final Pattern IMPORT_PAT   = Pattern.compile("^import ([\\w.]+\\.([\\w]+));", Pattern.MULTILINE);
    private static final Pattern SHADOW_PAT   = Pattern.compile(
        "@Shadow\\b[^;{]*?(?:\\s+\\w+)*?\\s+([\\w<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern INJECT_PAT   = Pattern.compile(
        "@(?:Inject|Redirect|ModifyArg|ModifyArgs|ModifyVariable|ModifyConstant)\\s*\\([^)]*?method\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SELF_CALL_PAT = Pattern.compile("\\bthis\\.(\\w+)\\s*\\(");

    private static void analyzeFile(Path src, Path srcRoot,
                                    Map<String, Set<String>> methods,
                                    Map<String, Map<String, String>> descs,
                                    Map<String, String> supers,
                                    List<String> recursive, List<String> mismatch, List<String> noTarget)
            throws IOException {
        String text = Files.readString(src, StandardCharsets.UTF_8);
        // Strip single-line comments before regex matching to avoid false positives
        text = text.replaceAll("//[^\n]*", "");
        String rel  = srcRoot.relativize(src).toString().replace('\\', '/');

        Matcher mm = MIXIN_PAT.matcher(text);
        if (!mm.find()) return;
        String simple = mm.group(1).contains(".")
            ? mm.group(1).substring(mm.group(1).lastIndexOf('.') + 1) : mm.group(1);
        String fqcn = resolveImport(text, simple);
        if (fqcn == null) return;
        String internalName = fqcn.replace('.', '/');

        Map<String, Integer> shadowMethods = new HashMap<>();
        Matcher sm = SHADOW_PAT.matcher(text);
        while (sm.find()) {
            String retType = sm.group(1), name = sm.group(2);
            if (isKeyword(name)) continue;
            int line = countLines(text, sm.start());
            shadowMethods.put(name, line);
            String cur = internalName;
            for (int g = 0; cur != null && g < 24; g++) {
                Map<String, String> ds = descs.get(cur);
                if (ds != null && ds.containsKey(name)) {
                    String jarRet = descToSimpleReturn(ds.get(name));
                    String srcRet = simpleLast(retType);
                    if (!srcRet.equals(jarRet) && !srcRet.equals("abstract") && !srcRet.equals("Object")) {
                        mismatch.add(String.format("%s:%d  %s: mixin=\"%s\" jar=\"%s\"",
                                     rel, line, name, srcRet, jarRet));
                    }
                    break;
                }
                cur = supers.get(cur);
            }
        }

        Matcher im = INJECT_PAT.matcher(text);
        while (im.find()) {
            String methodSel = im.group(1).trim();
            int line = countLines(text, im.start());
            String methodName = methodSel.contains("(") ? methodSel.substring(0, methodSel.indexOf('(')) : methodSel;
            if (methodName.startsWith("<")) continue;
            if (!methodExists(internalName, methodSel, methods, supers) &&
                !methodExists(internalName, methodName, methods, supers)) {
                noTarget.add(String.format("%s:%d  \"%s\" not found on %s", rel, line, methodSel, fqcn));
                continue;
            }
            int bodyStart = text.indexOf('{', im.end());
            if (bodyStart >= 0 && shadowMethods.containsKey(methodName)) {
                Matcher sc = SELF_CALL_PAT.matcher(extractBody(text, bodyStart));
                while (sc.find()) {
                    if (sc.group(1).equals(methodName)) {
                        recursive.add(String.format("%s:%d  @Inject into \"%s\" calls this.%s()",
                                     rel, line, methodName, methodName));
                        break;
                    }
                }
            }
        }
    }

    private static String resolveImport(String text, String simple) {
        Matcher m = IMPORT_PAT.matcher(text);
        while (m.find()) { if (m.group(2).equals(simple)) return m.group(1); }
        Matcher fq = Pattern.compile("@Mixin\\(([\\w.]*\\." + Pattern.quote(simple) + ")\\.class").matcher(text);
        return fq.find() ? fq.group(1) : null;
    }

    private static String extractBody(String text, int openBrace) {
        int depth = 0, i = openBrace;
        StringBuilder sb = new StringBuilder();
        while (i < text.length()) {
            char c = text.charAt(i++);
            if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) break; }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int countLines(String text, int idx) {
        int n = 1;
        for (int i = 0; i < idx && i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    private static String descToSimpleReturn(String desc) {
        if (desc == null) return "?";
        int ret = desc.lastIndexOf(')');
        if (ret < 0) return "?";
        String r = desc.substring(ret + 1);
        return switch (r) {
            case "V" -> "void"; case "Z" -> "boolean"; case "I" -> "int";
            case "J" -> "long"; case "F" -> "float";   case "D" -> "double";
            case "B" -> "byte"; case "C" -> "char";    case "S" -> "short";
            default  -> r.startsWith("L")
                ? r.substring(1, r.length() - 1).replace('/', '.').replaceAll(".*\\.", "")
                : r;
        };
    }

    private static String simpleLast(String type) {
        return type.contains(".")
            ? type.substring(type.lastIndexOf('.') + 1).replaceAll("[<>\\[\\]]", "").trim()
            : type.replaceAll("[<>\\[\\]]", "").trim();
    }

    private static boolean isKeyword(String s) {
        return switch (s) {
            case "public","private","protected","static","final","abstract","native",
                 "synchronized","void","int","long","float","double","boolean",
                 "byte","char","short","new","return","if","else","for","while" -> true;
            default -> false;
        };
    }

    private static void printSection(String title, List<String> rows) {
        System.out.println();
        System.out.println("=".repeat(66));
        System.out.println(title + ": " + rows.size());
        System.out.println("=".repeat(66));
        rows.forEach(r -> System.out.println("  " + r));
    }
}

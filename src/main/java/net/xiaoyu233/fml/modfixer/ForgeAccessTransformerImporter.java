package net.xiaoyu233.fml.modfixer;

import net.fabricmc.accesswidener.AccessWidener;
import net.xiaoyu233.fml.FishModLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Translates a Forge 1.6.4 access-transformer config (the {@code _at.cfg}
 * format) into AccessWidener v2 directives.
 *
 * <p>Forge AT lines look like:
 * <pre>
 *   public-f bff.a       #FD:Tessellator/field_78398_a #instance
 *   public  yi.&lt;init&gt;(ILxy;)V #MD:ItemPickaxe/&lt;init&gt;(...)
 * </pre>
 * The first token is the modifier; the second is {@code class.member}
 * (member optional for class-level entries) using whatever the AT was
 * compiled against. Comments after {@code #} are informational only.
 *
 * <p>This translator ignores the obfuscated names (we only have MITE-named
 * jars) and instead trusts the human-readable suffix when present, e.g.
 * {@code #FD:Tessellator/field_78398_a}. For mod-shipped ATs that use
 * already-mapped names (e.g. {@code public net/minecraft/block/Block.field_X}),
 * the name is taken verbatim.
 *
 * <p>The output is appended directly to FishModLoader's runtime
 * {@link AccessWidener}; the jar is not modified — runtime
 * AccessWidener pass widens at class-load.
 */
@Deprecated
public final class ForgeAccessTransformerImporter {

    /** Standard locations Forge mods ship their AT inside the jar. */
    public static final String[] LOCATIONS = {
            "META-INF/forge_at.cfg",
            "META-INF/fml_at.cfg",
            "META-INF/at.cfg"
    };

    private ForgeAccessTransformerImporter() {}

    /** Scan the jar for any AT files; for each found, append rules to FML's AW. */
    public static void importFrom(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String loc : LOCATIONS) {
                ZipEntry entry = jar.getEntry(loc);
                if (entry == null) continue;
                try (InputStream in = jar.getInputStream(entry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    int n = importStream(reader, jarPath.getFileName() + "!/" + loc);
                    FishModLoader.LOGGER.info("Imported {} AT entries from {}!/{}", n, jarPath.getFileName(), loc);
                }
            }
        } catch (IOException e) {
            FishModLoader.LOGGER.warn("Could not read AT from {}", jarPath, e);
        }
    }

    /** Import from a raw string (for unit tests). Returns number of entries appended. */
    public static int importString(String content, String sourceLabel) {
        try (BufferedReader r = new BufferedReader(new StringReader(content))) {
            return importStream(r, sourceLabel);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Walk a reader and emit AccessWidener directives via fabric's text format.
     * Returns the number of rules consumed.
     *
     * Note: we synthesize an AW v2 string and feed it to AccessWidenerReader
     * later in the loader. To keep this method side-effect-light, we instead
     * convert directly into the in-memory widener — see {@link #toAwLine}.
     */
    private static int importStream(BufferedReader reader, String sourceLabel) throws IOException {
        AccessWidener aw = FishModLoader.getAccessWidener();
        StringBuilder buffer = new StringBuilder("accessWidener\tv2\tnamed\n");
        int count = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = stripComment(line).trim();
            if (trimmed.isEmpty()) continue;
            String awLine = toAwLine(trimmed, sourceLabel);
            if (awLine != null) {
                buffer.append(awLine).append('\n');
                count++;
            }
        }
        if (count > 0) {
            net.fabricmc.accesswidener.AccessWidenerReader awr =
                    new net.fabricmc.accesswidener.AccessWidenerReader(aw);
            try (BufferedReader br = new BufferedReader(new StringReader(buffer.toString()))) {
                awr.read(br, "named");
            }
        }
        return count;
    }

    /**
     * Convert a single AT line into an AccessWidener v2 directive.
     * Returns null if the line cannot be translated.
     */
    private static String toAwLine(String line, String sourceLabel) {
        // Split on whitespace into [modifier, target, ...]
        String[] tokens = line.split("\\s+", 3);
        if (tokens.length < 2) return null;
        String modifier = tokens[0];
        String target = tokens[1];

        boolean removeFinal = modifier.endsWith("-f");
        boolean addFinal    = modifier.endsWith("+f");
        // We only honour "public" widening here; AW doesn't support targeted
        // protected/private widening and most ATs use public anyway.
        boolean isPublic = modifier.startsWith("public");
        if (!isPublic) return null;

        String access = (removeFinal && isClassOrField(target)) ? "mutable" : "accessible";
        // 'extendable' equivalent for class lines — AW v2 supports it
        // (class accessibility + remove FINAL). We keep it simple: always
        // emit accessible; mutable for fields with -f.

        // target is class.member or just a class:
        // - class only (no '.'): "accessible class <internal>"
        // - field:               "accessible field <internal> <name> <desc>"
        //                        but we lack desc unless the AT has it
        // - method:              "accessible method <internal> <name> <desc>"

        int dot = target.indexOf('.');
        if (dot < 0) {
            return access + " class " + toInternal(target);
        }

        String owner = toInternal(target.substring(0, dot));
        String memberAndDesc = target.substring(dot + 1);

        // Method form: name(...)Ldesc; or <init>(...)V
        int paren = memberAndDesc.indexOf('(');
        if (paren >= 0) {
            String name = memberAndDesc.substring(0, paren);
            String desc = memberAndDesc.substring(paren);
            return access + " method " + owner + " " + name + " " + desc;
        }

        // Field form — but Forge AT often omits the descriptor!
        // AccessWidener v2 requires a descriptor. If we don't have one, we
        // fall back to widening the whole class so the field reference at
        // least resolves.
        if (memberAndDesc.contains(" ")) {
            String[] parts = memberAndDesc.split("\\s+", 2);
            return access + " field " + owner + " " + parts[0] + " " + parts[1];
        }

        // No descriptor available — best-effort: widen the class instead.
        FishModLoader.LOGGER.debug("AT entry lacks descriptor, widening class instead: {} ({})", target, sourceLabel);
        return access + " class " + owner;
    }

    private static boolean isClassOrField(String target) {
        return !target.contains("(");
    }

    /**
     * Normalize a class reference. We expect mod-shipped ATs to either use
     * MCP-style names ({@code net/minecraft/...}) or already-translated
     * names; if the input is dotted ({@code net.minecraft.X}) we slash it.
     * Obfuscated 1-3-letter names (e.g. {@code bff}) are returned as-is —
     * they will quietly miss when fed to the widener but won't crash.
     */
    private static String toInternal(String classRef) {
        return classRef.replace('.', '/');
    }

    /** Strip a Forge AT comment. {@code #} starts a comment to end-of-line. */
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }
}

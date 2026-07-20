package net.xiaoyu233.fml.modfixer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.xiaoyu233.fml.FishModLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Parses Forge's {@code mcmod.info} from a mod jar.
 *
 * The format is JSON: either an array of mod descriptors at the top level,
 * or an object containing {@code modList} (newer Forge style). Each entry
 * holds modid/name/version/description/url/credits/authorList/dependencies.
 *
 * Returns the parsed entries; the caller merges them with whatever was
 * already discovered via {@code @Mod} annotation scanning.
 */
@Deprecated
public final class McModInfoParser {
    private McModInfoParser() {}

    public static List<Entry> read(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return readJar(jar);
        } catch (IOException e) {
            FishModLoader.LOGGER.warn("Could not read mcmod.info from {}", jarPath, e);
            return Collections.emptyList();
        }
    }

    public static List<Entry> readJar(JarFile jar) throws IOException {
        java.util.zip.ZipEntry entry = jar.getEntry("mcmod.info");
        if (entry == null) return Collections.emptyList();
        try (InputStream in = jar.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            JsonElement root = JsonParser.parseReader(reader);
            return parseRoot(root);
        }
    }

    private static List<Entry> parseRoot(JsonElement root) {
        List<Entry> out = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray()) {
                if (el.isJsonObject()) out.add(parseEntry(el.getAsJsonObject()));
            }
        } else if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("modList") && obj.get("modList").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("modList")) {
                    if (el.isJsonObject()) out.add(parseEntry(el.getAsJsonObject()));
                }
            } else {
                out.add(parseEntry(obj));
            }
        }
        return out;
    }

    private static Entry parseEntry(JsonObject json) {
        Entry e = new Entry();
        e.modid       = optString(json, "modid");
        e.name        = optString(json, "name");
        e.description = optString(json, "description");
        e.version     = optString(json, "version");
        e.mcversion   = optString(json, "mcversion");
        e.url         = optString(json, "url");
        e.updateUrl   = optString(json, "updateUrl");
        e.credits     = optString(json, "credits");
        e.logoFile    = optString(json, "logoFile");
        e.parent      = optString(json, "parent");
        e.authorList  = optStringArray(json, "authorList");
        e.dependencies = optStringArray(json, "dependencies");
        e.dependants   = optStringArray(json, "dependants");
        e.requiredMods = optStringArray(json, "requiredMods");
        e.useDependencyInformation = optBool(json, "useDependencyInformation");
        return e;
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static boolean optBool(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    private static List<String> optStringArray(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        JsonArray arr = o.getAsJsonArray(key);
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) out.add(el.getAsString());
        }
        return out;
    }

    /** Plain-data record matching one mcmod.info entry. */
    public static class Entry {
        public String modid = "";
        public String name = "";
        public String description = "";
        public String version = "";
        public String mcversion = "";
        public String url = "";
        public String updateUrl = "";
        public String credits = "";
        public String logoFile = "";
        public String parent = "";
        public List<String> authorList = Collections.emptyList();
        public List<String> dependencies = Collections.emptyList();
        public List<String> dependants = Collections.emptyList();
        public List<String> requiredMods = Collections.emptyList();
        public boolean useDependencyInformation = false;
    }
}

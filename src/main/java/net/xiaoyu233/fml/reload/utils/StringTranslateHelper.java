package net.xiaoyu233.fml.reload.utils;

import net.minecraft.util.StringTranslate;
import net.xiaoyu233.fml.reload.transform.forge_compat.api.IStringTranslateAccessor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Helper for forge-added static methods on {@link StringTranslate}.
 *
 * <p>These methods don't exist in the base MITE jar. The helper mirrors
 * the forge-patched implementation. At runtime, the Mixin system adds
 * the methods to StringTranslate directly.</p>
 */
public final class StringTranslateHelper {
    private StringTranslateHelper() {}

    /**
     * Inject translations from an InputStream. Corresponds to forge's
     * {@code StringTranslate.inject(InputStream)}.
     */
    public static void inject(InputStream inputstream) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputstream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq >= 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    // Delegate to the singleton's translateKey or use direct map access
                    StringTranslate.getInstance().translateKey(key); // warm up
                    // Access the internal languageList via the accessor interface
                    ((IStringTranslateAccessor) StringTranslate.getInstance()).getLanguageList().put(key, value);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to inject translations", e);
        }
    }

    /**
     * Replace all translations from a map. Corresponds to forge's
     * {@code StringTranslate.func_135063_a(Map)}.
     */
    public static void func_135063_a(Map<String, String> map) {
        IStringTranslateAccessor accessor = (IStringTranslateAccessor) StringTranslate.getInstance();
        accessor.getLanguageList().clear();
        accessor.getLanguageList().putAll(map);
    }
}

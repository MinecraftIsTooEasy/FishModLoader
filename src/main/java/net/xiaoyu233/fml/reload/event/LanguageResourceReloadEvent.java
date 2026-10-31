package net.xiaoyu233.fml.reload.event;

import java.util.Map;

public record LanguageResourceReloadEvent(Map translation, String languageKey) {
    /**
     * @deprecated the accessor name of FML 3.x (binary compatibility for mods compiled against it), use {@link #translation()}
     */
    @Deprecated(since = "4.0.0")
    public Map getTranslation() {
        return this.translation;
    }

    /**
     * @deprecated the accessor name of FML 3.x (binary compatibility for mods compiled against it), use {@link #languageKey()}
     */
    @Deprecated(since = "4.0.0")
    public String getLanguageKey() {
        return this.languageKey;
    }
}

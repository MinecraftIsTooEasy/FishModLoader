package net.xiaoyu233.fml.reload.event;

import java.util.Map;

public record LanguageResourceReloadEvent(Map translation, String languageKey) {
}

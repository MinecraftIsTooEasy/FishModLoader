package net.xiaoyu233.fml.reload.utils;

import java.util.HashMap;
import java.util.Map;

public class LocaleDataFixer {
    private static final Map<String, String> langMap = new HashMap<String, String>();

    static {
        langMap.put("en_US", "en_us");
        langMap.put("zh_CN", "zh_cn");
    }

    public static String translateToFuture(String lang) {
        if (langMap.containsKey(lang)) {
            return langMap.get(lang);
        }
        return lang;
    }
}


package net.xiaoyu233.fml.reload.utils.id;

import net.xiaoyu233.fml.FishModLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class IdAllocator {
    private static final String GLOBAL_FILE = FishModLoader.CONFIG_DIR + "/id/!ranges.cfg";
    private static final String MOD_DIR = FishModLoader.CONFIG_DIR + "/id/";
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static final Map<String, Map<IdType, ModRange>> GLOBAL_RANGES = new ConcurrentHashMap<>();
    private final String modId;
    private final Map<IdType, ModRange> ranges = new EnumMap<>(IdType.class);
    private final Map<IdType, Map<String, Integer>> idMaps = new EnumMap<>(IdType.class);
    
    public IdAllocator(String modId) {
        this.modId = modId.toLowerCase(Locale.ROOT);
        loadGlobal();
        loadModConfig();
    }

    private static void loadGlobal() {
        File f = new File(GLOBAL_FILE);
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("=") || !line.contains(":")) continue;
                String[] p = line.split(":");
                String modId = p[0];
                String[] rest = p[1].split("=");
                IdType type = IdType.valueOf(rest[0]);
                String[] nums = rest[1].split(",");
                int start = Integer.parseInt(nums[0]);
                int count = Integer.parseInt(nums[1]);
                ModRange range = new ModRange(modId, type, start, count);
                GLOBAL_RANGES.computeIfAbsent(modId, s -> new EnumMap<>(IdType.class)).put(type, range);
                for (int i = 0; i < count; i++) {
                    IdStorage.RESERVED.get(type).add(start + i);
                }
            }
        } catch (Exception e) {
            FishModLoader.LOGGER.error("Failed to load !ranges.cfg", e);
        }
    }

    private static void saveGlobal() {
        try {
            File f = new File(GLOBAL_FILE);
            if (!f.exists()) f.getParentFile().mkdirs();
            try (PrintWriter w = new PrintWriter(f, StandardCharsets.UTF_8)) {
                for (var e : GLOBAL_RANGES.entrySet()) {
                    for (var r : e.getValue().values()) {
                        w.println(r);
                    }
                }
            }
        } catch (Exception e) {
            FishModLoader.LOGGER.error("Failed to save !ranges.cfg", e);
        }
    }

    public IdAllocator setCount(IdType type, int count) {
        LOCK.writeLock().lock();
        try {
            ModRange existing = ranges.get(type);
            if (existing != null) {
                for (int i = 0; i < existing.count(); i++) {
                    IdStorage.RESERVED.get(type).remove(existing.initial() + i);
                }
            }
            
            ModRange range = IdRangeAllocator.createRange(modId, type, count);
            if (range != null) {
                ranges.put(type, range);
                Map<IdType, ModRange> modRanges = GLOBAL_RANGES.computeIfAbsent(modId, s -> new EnumMap<>(IdType.class));
                modRanges.put(type, range);
            }
        } finally {
            LOCK.writeLock().unlock();
        }
        return this;
    }

    public int getId(IdType type, String name) {
        Map<String, Integer> map = idMaps.computeIfAbsent(type, s -> new ConcurrentHashMap<>());
        Integer existing = map.get(name);
        if (existing != null) {
            return convert(type, existing);
        }
        LOCK.writeLock().lock();
        try {
            return convert(type, map.computeIfAbsent(name, s -> {
                ModRange r = ranges.get(type);
                if (r == null) {
                    throw new RuntimeException("Type not enabled: " + type);
                }
                int id = nextFree(type, r);
                IdStorage.USED.get(type).add(id);
                saveAll();
                return id;
            }));
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public int getBlockId(String name) {
        return getId(IdType.BLOCK, name);
    }

    public int getItemId(String name) {
        return getId(IdType.ITEM, name);
    }

    private int nextFree(IdType type, ModRange range) {
        Set<Integer> used = IdStorage.USED.get(type);
        for (int i = 0; i < range.count(); i++) {
            int id = range.initial() + i;
            if (!used.contains(id)) {
                return id;
            }
        }
        throw new RuntimeException("ID exhausted: " + type);
    }

    private int convert(IdType type, int raw) {
        return (type == IdType.ITEM) ? raw + 256 : raw;
    }
    
    private void saveAll() {
        saveGlobal();
        saveMod();
    }
    
    private void saveMod() {
	    File f = null;
	    try {
		    f = new File(MOD_DIR, modId + ".cfg");
		    if (!f.exists()) f.getParentFile().mkdirs();
		    try (PrintWriter w = new PrintWriter(f, StandardCharsets.UTF_8)) {
			    for (var entry : idMaps.entrySet()) {
				    IdType type = entry.getKey();
				    w.println("[" + type.name().toLowerCase() + "]");
				    List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(entry.getValue().entrySet());
				    sortedEntries.sort(Map.Entry.comparingByValue());
				    for (var kv : sortedEntries) {
					    int out = (type == IdType.ITEM) ? kv.getValue() + 256 : kv.getValue();
					    w.println(kv.getKey() + "=" + out);
				    }
			    }
		    }
	    } catch (Exception e) {
		    FishModLoader.LOGGER.error("Failed to save {}", f, e);
	    }
    }
    
    private void loadModConfig() {
        File f = new File(MOD_DIR, modId + ".cfg");
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            IdType current = null;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = IdType.valueOf(line.substring(1, line.length() - 1).toUpperCase());
                    continue;
                }
                if (current == null || !line.contains("=")) continue;
                String[] p = line.split("=");
                int id = Integer.parseInt(p[1].trim());
                int raw = (current == IdType.ITEM) ? id - 256 : id;
                idMaps.computeIfAbsent(current, t -> new ConcurrentHashMap<>()).put(p[0], raw);
                IdStorage.USED.get(current).add(raw);
            }
        } catch (Exception e) {
            FishModLoader.LOGGER.error("Failed to load {}", f, e);
        }
    }
}
package net.xiaoyu233.fml.reload.utils;

import net.xiaoyu233.fml.FishModLoader;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class IDAllocator {
    private static final String GLOBAL_RANGE_CONFIG_FILE = FishModLoader.CONFIG_DIR + "/id/!modIdRanges.cfg";
    private static final String MOD_CONFIG_DIR = FishModLoader.CONFIG_DIR + "/id/";

    private static final int BLOCK_ID_START = 2024;
    private static final int BLOCK_ID_END = 4095;
    private static final int ITEM_ID_START = 3840;
    private static final int ITEM_ID_END = 31999;
    private static final int DEFAULT_BLOCK_COUNT = 64;
    private static final int DEFAULT_ITEM_COUNT = 256;

    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static final Map<String, ModIdRange> modIdRanges = new ConcurrentHashMap<>();
    private static final Set<Integer> allocatedBlockIds = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> allocatedItemIds = ConcurrentHashMap.newKeySet();
    private final String modId;
    private final int blockCount;
    private final int itemCount;
    private final Map<String, Integer> blockIdMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> itemIdMap = new ConcurrentHashMap<>();
    private ModIdRange modRange;

    static {
        loadGlobalRangeConfig();
        initializeAllocatedIds();
        ensureGlobalConfigExists();
    }

    public IDAllocator(String modId) {
        this(modId, DEFAULT_BLOCK_COUNT, DEFAULT_ITEM_COUNT);
    }

    public IDAllocator(String modId, int blockCount, int itemCount) {
        this.modId = modId.toLowerCase(Locale.ROOT);
        this.blockCount = Math.min(BLOCK_ID_END - BLOCK_ID_START, blockCount);
        this.itemCount = Math.min(ITEM_ID_END - ITEM_ID_START, itemCount);
        this.modRange = getOrCreateModRange();
        this.loadModIdConfig();
    }

    private static void ensureGlobalConfigExists() {
        File configFile = new File(GLOBAL_RANGE_CONFIG_FILE);
        if (!configFile.exists()) {
            LOCK.writeLock().lock();
            try {
                saveGlobalRangeConfig();
            } finally {
                LOCK.writeLock().unlock();
            }
        }
    }

    private static void loadGlobalRangeConfig() {
        File configFile = new File(GLOBAL_RANGE_CONFIG_FILE);
        if (!configFile.exists()) return;

        Map<String, ModIdRange> tempRanges = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;

                String modId = parts[0].trim();
                String[] values = parts[1].split(",", -1);
                if (values.length != 6) continue;

                try {
                    ModIdRange range = getModIdRange(values, modId);
                    tempRanges.put(modId, range);
                } catch (NumberFormatException e) {
                    FishModLoader.LOGGER.warn("Invalid range config for mod {}: {}", modId, line);
                }
            }
        } catch (Exception e) {
            FishModLoader.LOGGER.error("Failed to load global ID range config", e);
        }

        LOCK.writeLock().lock();
        try {
            modIdRanges.clear();
            modIdRanges.putAll(tempRanges);
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    @Nonnull
    private static ModIdRange getModIdRange(String[] values, String modId) {
        int blockStart = Integer.parseInt(values[0]);
        int blockCount = Integer.parseInt(values[1]);
        int itemStart = Integer.parseInt(values[2]);
        int itemCount = Integer.parseInt(values[3]);
        int allocatedBlocks = Integer.parseInt(values[4]);
        int allocatedItems = Integer.parseInt(values[5]);

        return new ModIdRange(modId, blockStart, blockCount, itemStart, itemCount, allocatedBlocks, allocatedItems);
    }

    private static void initializeAllocatedIds() {
        LOCK.writeLock().lock();
        try {
            allocatedBlockIds.clear();
            allocatedItemIds.clear();

            for (ModIdRange range : modIdRanges.values()) {
                if (!isRangeValidStatic(range)) {
                    FishModLoader.LOGGER.warn("Invalid range for mod {}, will reassign", range.modId);
                    continue;
                }

                for (int i = 0; i < range.allocatedBlocks; i++) {
                    allocatedBlockIds.add(range.blockStart + i);
                }
                for (int i = 0; i < range.allocatedItems; i++) {
                    allocatedItemIds.add(range.itemStart + i);
                }
            }
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static void saveGlobalRangeConfig() {
        LOCK.readLock().lock();
        try {
            File configFile = new File(GLOBAL_RANGE_CONFIG_FILE);
            File dir = configFile.getParentFile();
            if (!dir.exists()) dir.mkdirs();

            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8))) {
                writer.println("# Global ID Range Configuration");
                writer.println("# Format: modid=blockStart,blockCount,itemStart,itemCount,allocatedBlocks,allocatedItems");
                writer.println();

                modIdRanges.values().forEach(writer::println);
            }
        } catch (IOException e) {
            FishModLoader.LOGGER.error("Failed to save global ID range config", e);
        } finally {
            LOCK.readLock().unlock();
        }
    }

    private static boolean isRangeValidStatic(ModIdRange range) {
        return range != null && range.blockStart >= BLOCK_ID_START &&
                range.blockStart + range.blockCount - 1 <= BLOCK_ID_END &&
                range.itemStart >= ITEM_ID_START &&
                range.itemStart + range.itemCount - 1 <= ITEM_ID_END;
    }

    private ModIdRange getOrCreateModRange() {
        LOCK.writeLock().lock();
        try {
            ModIdRange existing = modIdRanges.get(modId);
            if (existing != null && isRangeValid(existing) && !hasRangeConflict(existing)) {
                return existing;
            }

            ModIdRange loaded = loadModRangeFromGlobalConfig();
            if (loaded != null && isRangeValid(loaded) && !hasRangeConflict(loaded)) {
                modIdRanges.put(modId, loaded);
                return loaded;
            }

            ModIdRange newRange = createNewRange();
            modIdRanges.put(modId, newRange);
            saveGlobalRangeConfig();
            return newRange;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private ModIdRange loadModRangeFromGlobalConfig() {
        File configFile = new File(GLOBAL_RANGE_CONFIG_FILE);
        if (!configFile.exists()) return null;

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;

                if (parts[0].trim().equals(modId)) {
                    String[] values = parts[1].split(",", -1);
                    if (values.length == 6) {
                        try {
                            return getModIdRange(values, modId);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            FishModLoader.LOGGER.warn("Failed to read global config for mod: {}", modId, e);
        }
        return null;
    }

    private boolean isRangeValid(ModIdRange range) {
        return range.blockStart >= BLOCK_ID_START &&
                range.blockStart + range.blockCount - 1 <= BLOCK_ID_END &&
                range.itemStart >= ITEM_ID_START &&
                range.itemStart + range.itemCount - 1 <= ITEM_ID_END;
    }

    private boolean hasRangeConflict(ModIdRange range) {
        LOCK.readLock().lock();
        try {
            for (ModIdRange other : modIdRanges.values()) {
                if (other.modId.equals(modId)) continue;

                boolean blockOverlap = range.blockStart < other.blockStart + other.blockCount && range.blockStart + range.blockCount > other.blockStart;
                boolean itemOverlap = range.itemStart < other.itemStart + other.itemCount && range.itemStart + range.itemCount > other.itemStart;

                if (blockOverlap || itemOverlap) {
                    return true;
                }
            }
            return false;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    private ModIdRange createNewRange() {
        ModIdRange range = createNewRangeWithHash();

        if (hasRangeConflict(range)) {
            int blockStart = findAvailableBlockRange(BLOCK_ID_START);
            int itemStart = findAvailableItemRange(ITEM_ID_START);
            range = new ModIdRange(modId, blockStart, blockCount, itemStart, itemCount);
        }

        return range;
    }

    private ModIdRange createNewRangeWithHash() {
        int hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(modId.getBytes(StandardCharsets.UTF_8));
            hash = (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
        } catch (Exception e) {
            hash = modId.hashCode();
        }

        int blockOffset = Math.abs(hash) % ((BLOCK_ID_END - BLOCK_ID_START + 1 - blockCount + 1));
        int blockStart = BLOCK_ID_START + blockOffset;

        int itemOffset = Math.abs(hash ^ (hash >> 16)) % ((ITEM_ID_END - ITEM_ID_START + 1 - itemCount + 1));
        int itemStart = ITEM_ID_START + itemOffset;

        return new ModIdRange(modId, blockStart, blockCount, itemStart, itemCount);
    }

    private int findAvailableBlockRange(int start) {
        for (int attempt = 0; attempt < 2; attempt++) {
            int pos = start;
            do {
                if (isBlockRangeAvailable(pos, blockCount)) {
                    return pos;
                }
                pos += blockCount;
                if (pos + blockCount > BLOCK_ID_END) pos = BLOCK_ID_START;
            } while (pos != start);
        }
        throw new RuntimeException("No available block ID range for mod: " + modId);
    }

    private int findAvailableItemRange(int start) {
        for (int attempt = 0; attempt < 2; attempt++) {
            int pos = start;
            do {
                if (isItemRangeAvailable(pos, itemCount)) {
                    return pos;
                }
                pos += itemCount;
                if (pos + itemCount > ITEM_ID_END) pos = ITEM_ID_START;
            } while (pos != start);
        }
        throw new RuntimeException("No available item ID range for mod: " + modId);
    }

    private boolean isBlockRangeAvailable(int start, int count) {
        LOCK.readLock().lock();
        try {
            for (int i = 0; i < count; i++) {
                if (allocatedBlockIds.contains(start + i)) return false;
            }
            return true;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    private boolean isItemRangeAvailable(int start, int count) {
        LOCK.readLock().lock();
        try {
            for (int i = 0; i < count; i++) {
                if (allocatedItemIds.contains(start + i)) return false;
            }
            return true;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    private void loadModIdConfig() {
        File configFile = new File(MOD_CONFIG_DIR, modId + ".cfg");
        if (!configFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            boolean inBlocks = false;
            boolean inItems = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.equals("[blocks]")) {
                    inBlocks = true;
                    inItems = false;
                    continue;
                } else if (line.equals("[items]")) {
                    inBlocks = false;
                    inItems = true;
                    continue;
                }

                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;

                String name = parts[0].trim();
                try {
                    int id = Integer.parseInt(parts[1].trim());

                    if (inBlocks && id >= modRange.blockStart && id < modRange.blockStart + modRange.blockCount) {
                        blockIdMap.put(name, id);
                        if (!allocatedBlockIds.contains(id)) {
                            allocatedBlockIds.add(id);
                            modRange.allocatedBlocks++;
                        }
                    } else if (inItems) {
                        int actualId = id - 256;
                        if (actualId >= modRange.itemStart && actualId < modRange.itemStart + modRange.itemCount) {
                            itemIdMap.put(name, actualId);
                            if (!allocatedItemIds.contains(actualId)) {
                                allocatedItemIds.add(actualId);
                                modRange.allocatedItems++;
                            }
                        }
                    }
                } catch (NumberFormatException e) {
                    FishModLoader.LOGGER.warn("Invalid ID in config: {}", line);
                }
            }
        } catch (Exception e) {
            FishModLoader.LOGGER.error("Failed to load mod config for: {}", modId, e);
        }
    }

    private void saveModIdConfig() {
        File dir = new File(MOD_CONFIG_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            FishModLoader.LOGGER.warn("Failed to create config directory: {}", dir);
            return;
        }

        File configFile = new File(dir, modId + ".cfg");
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8))) {
            writer.println("# Mod ID Configuration for " + modId);
            writer.println("# Block Range: " + modRange.blockStart + " ~ " + (modRange.blockStart + modRange.blockCount - 1));
            writer.println("# Item Range: " + (modRange.itemStart + 256) + " ~ " + (modRange.itemStart + modRange.itemCount - 1 + 256));

            if (!blockIdMap.isEmpty()) {
                writer.println("[blocks]");
                blockIdMap.forEach((name, id) -> writer.println(name + "=" + id));
                writer.println();
            }

            if (!itemIdMap.isEmpty()) {
                writer.println("[items]");
                itemIdMap.forEach((name, id) -> writer.println(name + "=" + (id + 256)));
            }
        } catch (IOException e) {
            FishModLoader.LOGGER.error("Failed to save mod config: " + configFile, e);
        }
    }

    public int getBlockId(String blockName) {
        return blockIdMap.computeIfAbsent(blockName, this::getNextBlockIdInternal);
    }

    public int getItemId(String itemName) {
        return itemIdMap.computeIfAbsent(itemName, this::getNextItemIdInternal);
    }

    private int getNextBlockIdInternal(String blockName) {
        LOCK.writeLock().lock();
        try {
            if (modRange.allocatedBlocks >= modRange.blockCount) {
                throw new RuntimeException("No more block IDs for mod: " + modId);
            }

            int id = modRange.blockStart + modRange.allocatedBlocks;
            modRange.allocatedBlocks++;
            allocatedBlockIds.add(id);
            saveGlobalRangeConfig();
            saveModIdConfig();
            return id;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private int getNextItemIdInternal(String itemName) {
        LOCK.writeLock().lock();
        try {
            if (modRange.allocatedItems >= modRange.itemCount) {
                throw new RuntimeException("No more item IDs for mod: " + modId);
            }

            int id = modRange.itemStart + modRange.allocatedItems;
            modRange.allocatedItems++;
            allocatedItemIds.add(id);
            saveGlobalRangeConfig();
            saveModIdConfig();
            return id;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public int getBlockStartId() {
        return modRange.blockStart;
    }

    public int getItemStartId() {
        return modRange.itemStart;
    }

    public int getAllocatedBlocks() {
        return modRange.allocatedBlocks;
    }

    public int getAllocatedItems() {
        return modRange.allocatedItems;
    }

    public Map<String, Integer> getBlockIdMap() {
        return new HashMap<>(blockIdMap);
    }

    public Map<String, Integer> getItemIdMap() {
        Map<String, Integer> result = new HashMap<>();
        itemIdMap.forEach((k, v) -> result.put(k, v + 256));
        return result;
    }

    private static class ModIdRange {
        public final String modId;
        public final int blockStart;
        public final int blockCount;
        public final int itemStart;
        public final int itemCount;
        public int allocatedBlocks;
        public int allocatedItems;

        public ModIdRange(String modId, int blockStart, int blockCount, int itemStart, int itemCount) {
            this(modId, blockStart, blockCount, itemStart, itemCount, 0, 0);
        }

        public ModIdRange(String modId, int blockStart, int blockCount, int itemStart, int itemCount, int allocatedBlocks, int allocatedItems) {
            this.modId = modId;
            this.blockStart = blockStart;
            this.blockCount = blockCount;
            this.itemStart = itemStart;
            this.itemCount = itemCount;
            this.allocatedBlocks = allocatedBlocks;
            this.allocatedItems = allocatedItems;
        }

        @Override
        public String toString() {
            return modId + "=" + blockStart + "," + blockCount + "," + itemStart + "," + itemCount + "," + allocatedBlocks + "," + allocatedItems;
        }
    }
}
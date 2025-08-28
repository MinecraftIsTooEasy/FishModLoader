package net.xiaoyu233.fml.reload.utils;

import net.xiaoyu233.fml.FishModLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IDAllocator {
    private static final String GLOBAL_RANGE_CONFIG_FILE = FishModLoader.CONFIG_DIR + "/id/!modIdRanges.cfg";

    private static final String MOD_CONFIG_DIR = FishModLoader.CONFIG_DIR + "/id/";

    private static final int BLOCK_ID_START = 2024;
    private static final int BLOCK_ID_END = 4095;
    private static final int ITEM_ID_START = 3840;
    private static final int ITEM_ID_END = 31999;

    private static final int DEFAULT_BLOCK_COUNT = 64;
    private static final int DEFAULT_ITEM_COUNT = 256;

    private static final Map<String, ModIdRange> modIdRanges = new ConcurrentHashMap<>();

    private static final Set<Integer> allocatedBlockIds = Collections.synchronizedSet(new HashSet<Integer>());
    private static final Set<Integer> allocatedItemIds = Collections.synchronizedSet(new HashSet<Integer>());

    static {
        loadGlobalRangeConfig();
        initializeAllocatedIds();
        ensureGlobalConfigExists();
    }

    private final String modId;
    private final int blockCount;
    private final int itemCount;
    private final Map<String, Integer> blockIdMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> itemIdMap = new ConcurrentHashMap<>();
    private ModIdRange modRange;

    /**
     * Default Block ID Count: 64
     * Default Item ID Count: 256
     */
    public IDAllocator(String modId) {
        this(modId, DEFAULT_BLOCK_COUNT, DEFAULT_ITEM_COUNT);
    }

    public IDAllocator(String modId, int blockCount, int itemCount) {
        this.modId = modId.toLowerCase();
        this.blockCount = Math.min(BLOCK_ID_END - BLOCK_ID_START, Math.max(1, blockCount));
        this.itemCount = Math.min(ITEM_ID_END - ITEM_ID_START, Math.max(1, itemCount));
        this.modRange = getOrCreateModRange();
        this.loadModIdConfig();
    }

    private static void ensureGlobalConfigExists() {
        File configFile = new File(GLOBAL_RANGE_CONFIG_FILE);
        if (!configFile.exists()) {
            saveGlobalRangeConfig();
        }
    }

    private static void loadGlobalRangeConfig() {
        File configFile = new File(GLOBAL_RANGE_CONFIG_FILE);
        if (!configFile.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Format: modid=blockStart,blockCount,itemStart,itemCount,allocatedBlocks,allocatedItems
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    String modId = parts[0].trim();
                    String[] values = parts[1].trim().split(",");
                    if (values.length == 6) {
                        int blockStart = Integer.parseInt(values[0]);
                        int blockCount = Integer.parseInt(values[1]);
                        int itemStart = Integer.parseInt(values[2]);
                        int itemCount = Integer.parseInt(values[3]);
                        int allocatedBlocks = Integer.parseInt(values[4]);
                        int allocatedItems = Integer.parseInt(values[5]);

                        ModIdRange range = new ModIdRange(modId, blockStart, blockCount,
                                itemStart, itemCount, allocatedBlocks, allocatedItems);
                        modIdRanges.put(modId, range);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void initializeAllocatedIds() {
        Iterator<Map.Entry<String, ModIdRange>> iterator = modIdRanges.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ModIdRange> entry = iterator.next();
            ModIdRange range = entry.getValue();

            if (!isRangeValidStatic(range)) {
                FishModLoader.LOGGER.warn("Warning: Invalid ID range for mod {}, will be reassigned", entry.getKey());
                iterator.remove();
                continue;
            }

            for (int i = 0; i < range.allocatedBlocks; i++) {
                allocatedBlockIds.add(range.blockStart + i);
            }

            for (int i = 0; i < range.allocatedItems; i++) {
                allocatedItemIds.add(range.itemStart + i);
            }
        }
    }

    private static boolean isRangeValidStatic(ModIdRange range) {
        if (range.blockStart < BLOCK_ID_START || range.blockStart + range.blockCount - 1 > BLOCK_ID_END) {
            return false;
        }

        if (range.itemStart < ITEM_ID_START || range.itemStart + range.itemCount - 1 > ITEM_ID_END) {
            return false;
        }

        return true;
    }

    public static void saveGlobalRangeConfig() {
        File configFile = new File(GLOBAL_RANGE_CONFIG_FILE);
        File dir = configFile.getParentFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(configFile))) {
            writer.println("# Global ID Range Configuration");
            writer.println("# Format: modid=blockStart,blockCount,itemStart,itemCount,allocatedBlocks,allocatedItems");
            writer.println("# This file is automatically generated and managed");
            writer.println();

            for (Map.Entry<String, ModIdRange> entry : modIdRanges.entrySet()) {
                ModIdRange range = entry.getValue();
//                writer.printf("%s=%d,%d,%d,%d,%d,%d%n",
//                        entry.getKey(),
//                        range.blockStart, range.blockCount,
//                        range.itemStart, range.itemCount,
//                        range.allocatedBlocks, range.allocatedItems);
                writer.printf(range.toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private ModIdRange getOrCreateModRange() {
        if (modIdRanges.containsKey(modId)) {
            ModIdRange range = modIdRanges.get(modId);
            if (isRangeValid(range) && !hasRangeConflict(range)) {
                return range;
            } else {
                ModIdRange newRange = createNewRange();
                modIdRanges.put(modId, newRange);
                saveGlobalRangeConfig();
                return newRange;
            }
        }

        ModIdRange range = loadModRangeFromGlobalConfig();
        if (range != null && isRangeValid(range) && !hasRangeConflict(range)) {
            return range;
        }

        ModIdRange newRange = createNewRange();
        modIdRanges.put(modId, newRange);
        saveGlobalRangeConfig();
        return newRange;
    }

    private ModIdRange loadModRangeFromGlobalConfig() {
        File configFile = new File(GLOBAL_RANGE_CONFIG_FILE);
        if (!configFile.exists()) {
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("=");
                if (parts.length == 2) {
                    String configModId = parts[0].trim();
                    if (configModId.equals(modId)) {
                        String[] values = parts[1].trim().split(",");
                        if (values.length == 6) {
                            int blockStart = Integer.parseInt(values[0]);
                            int blockCount = Integer.parseInt(values[1]);
                            int itemStart = Integer.parseInt(values[2]);
                            int itemCount = Integer.parseInt(values[3]);
                            int allocatedBlocks = Integer.parseInt(values[4]);
                            int allocatedItems = Integer.parseInt(values[5]);

                            return new ModIdRange(modId, blockStart, blockCount,
                                    itemStart, itemCount, allocatedBlocks, allocatedItems);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean isRangeValid(ModIdRange range) {
        if (range.blockStart < BLOCK_ID_START || range.blockStart + range.blockCount - 1 > BLOCK_ID_END) {
            return false;
        }

        if (range.itemStart < ITEM_ID_START || range.itemStart + range.itemCount - 1 > ITEM_ID_END) {
            return false;
        }

        return true;
    }

    private boolean hasRangeConflict(ModIdRange range) {
        for (Map.Entry<String, ModIdRange> entry : modIdRanges.entrySet()) {
            if (!entry.getKey().equals(modId)) {
                ModIdRange otherRange = entry.getValue();

                if (!(range.blockStart + range.blockCount <= otherRange.blockStart || range.blockStart >= otherRange.blockStart + otherRange.blockCount)) {
                    return true;
                }

                if (!(range.itemStart + range.itemCount <= otherRange.itemStart || range.itemStart >= otherRange.itemStart + otherRange.itemCount)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ModIdRange createNewRangeWithAlternativeHash() {
        int hash = modId.hashCode() * 31 + modId.length();

        int blockRangeSize = BLOCK_ID_END - BLOCK_ID_START + 1;
        int blockStart = BLOCK_ID_START + Math.abs(hash % (blockRangeSize / DEFAULT_BLOCK_COUNT)) * DEFAULT_BLOCK_COUNT;
        if (blockStart + blockCount > BLOCK_ID_END) {
            blockStart = BLOCK_ID_START;
        }

        int itemRangeSize = ITEM_ID_END - ITEM_ID_START + 1;
        int itemStart = ITEM_ID_START + Math.abs((hash >> 13) % (itemRangeSize / DEFAULT_ITEM_COUNT)) * DEFAULT_ITEM_COUNT;
        if (itemStart + itemCount > ITEM_ID_END) {
            itemStart = ITEM_ID_START;
        }

        blockStart = findAvailableBlockRange(blockStart);
        itemStart = findAvailableItemRange(itemStart);

        return new ModIdRange(modId, blockStart, blockCount, itemStart, itemCount);
    }

    private ModIdRange createNewRange() {
        ModIdRange range = createNewRangeWithOriginalHash();

        int attempts = 0;
        while (hasRangeConflict(range) && attempts < 10) {
            range = createNewRangeWithAlternativeHash();
            attempts++;
        }

        if (hasRangeConflict(range)) {
            int blockStart = findAvailableBlockRange(range.blockStart);
            int itemStart = findAvailableItemRange(range.itemStart);
            range = new ModIdRange(modId, blockStart, blockCount, itemStart, itemCount);
        }

        return range;
    }

    private ModIdRange createNewRangeWithOriginalHash() {
        int hash = 0;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(modId.getBytes(StandardCharsets.UTF_8));

            for (int i = 0; i < 4; i++) {
                hash = (hash << 8) | (hashBytes[i] & 0xFF);
            }
        } catch (Exception e) {
            FishModLoader.LOGGER.error("Cannot use SHA-256 for Hash ID Range Allocator", e);
            hash = modId.hashCode();
        }

        int blockRangeSize = BLOCK_ID_END - BLOCK_ID_START + 1;
        int blockStart = BLOCK_ID_START + Math.abs(hash % (blockRangeSize / DEFAULT_BLOCK_COUNT)) * DEFAULT_BLOCK_COUNT;
        if (blockStart + blockCount > BLOCK_ID_END) {
            blockStart = BLOCK_ID_START;
        }

        int itemRangeSize = ITEM_ID_END - ITEM_ID_START + 1;
        int itemStart = ITEM_ID_START + Math.abs((hash >> 16) % (itemRangeSize / DEFAULT_ITEM_COUNT)) * DEFAULT_ITEM_COUNT;
        if (itemStart + itemCount > ITEM_ID_END) {
            itemStart = ITEM_ID_START;
        }

        return new ModIdRange(modId, blockStart, blockCount, itemStart, itemCount);
    }

    private int findAvailableBlockRange(int start) {
        int originalStart = start;
        int maxAttempts = (BLOCK_ID_END - BLOCK_ID_START) / blockCount + 1;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (isBlockRangeAvailable(start, blockCount)) {
                return start;
            }
            start += blockCount;
            if (start + blockCount > BLOCK_ID_END) {
                start = BLOCK_ID_START;
            }
            if (start == originalStart) {
                break;
            }
        }

        for (int i = BLOCK_ID_START; i <= BLOCK_ID_END - blockCount + 1; i++) {
            if (isBlockRangeAvailable(i, blockCount)) {
                return i;
            }
        }

        throw new RuntimeException("No available block ID range for mod: " + modId);
    }

    private int findAvailableItemRange(int start) {
        int originalStart = start;
        int maxAttempts = (ITEM_ID_END - ITEM_ID_START) / itemCount + 1;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (isItemRangeAvailable(start, itemCount)) {
                return start;
            }
            start += itemCount;
            if (start + itemCount > ITEM_ID_END) {
                start = ITEM_ID_START;
            }
            if (start == originalStart) {
                break;
            }
        }

        for (int i = ITEM_ID_START; i <= ITEM_ID_END - itemCount + 1; i++) {
            if (isItemRangeAvailable(i, itemCount)) {
                return i;
            }
        }

        throw new RuntimeException("No available item ID range for mod: " + modId);
    }

    private boolean isBlockRangeAvailable(int start, int count) {
        for (int i = 0; i < count; i++) {
            if (allocatedBlockIds.contains(start + i)) {
                return false;
            }
        }
        return true;
    }

    private boolean isItemRangeAvailable(int start, int count) {
        for (int i = 0; i < count; i++) {
            if (allocatedItemIds.contains(start + i)) {
                return false;
            }
        }
        return true;
    }

    private void loadModIdConfig() {
        File configFile = new File(MOD_CONFIG_DIR + modId + ".cfg");
        if (!configFile.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            boolean inBlocks = false;
            boolean inItems = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.equals("[blocks]")) {
                    inBlocks = true;
                    inItems = false;
                    continue;
                } else if (line.equals("[items]")) {
                    inBlocks = false;
                    inItems = true;
                    continue;
                }

                if (inBlocks || inItems) {
                    String[] parts = line.split("=");
                    if (parts.length == 2) {
                        String name = parts[0].trim();
                        int id = Integer.parseInt(parts[1].trim());

                        if (inBlocks) {
                            if (id >= modRange.blockStart && id < modRange.blockStart + modRange.blockCount) {
                                blockIdMap.put(name, id);
                            } else {
                                FishModLoader.LOGGER.warn("Warning: Block ID {} for {} is out of range for mod {}, ignoring...", id, name, modId);
                            }
                        } else if (inItems) {
                            int actualId = id - 256;
                            if (actualId >= modRange.itemStart && actualId < modRange.itemStart + modRange.itemCount) {
                                itemIdMap.put(name, actualId);
                            } else {
                                FishModLoader.LOGGER.warn("Warning: Item ID {} for {} is out of range for mod {}, ignoring...", id, name, modId);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 保存模组ID配置文件（只保存已分配的ID）
     */
    private void saveModIdConfig() {
        File dir = new File(MOD_CONFIG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File configFile = new File(MOD_CONFIG_DIR + modId + ".cfg");
        try (PrintWriter writer = new PrintWriter(new FileWriter(configFile))) {
            writer.println("########################################################");
            writer.println("# Mod ID Configuration for " + modId);
            writer.println("# Generated automatically by IdAllocator");
            writer.println("# You can modify individual IDs below");
            writer.println("# Block ID Range: " + modRange.blockStart + " - " + (modRange.blockStart + modRange.blockCount - 1));
            writer.println("# Item ID Range: " + (modRange.itemStart + 256) + " - " + (modRange.itemStart + modRange.itemCount - 1 + 256));
            writer.println("########################################################");
            writer.println();

            if (!blockIdMap.isEmpty()) {
                writer.println("[blocks]");
                for (Map.Entry<String, Integer> entry : blockIdMap.entrySet()) {
                    writer.println(entry.getKey() + "=" + entry.getValue());
                }
                writer.println();
            }

            if (!itemIdMap.isEmpty()) {
                writer.println("[items]");
                for (Map.Entry<String, Integer> entry : itemIdMap.entrySet()) {
                    writer.println(entry.getKey() + "=" + (entry.getValue() + 256));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getBlockId(String blockName) {
        Integer id = blockIdMap.get(blockName);
        if (id == null) {
            id = getNextBlockId(blockName);
        }
        return id;
    }

    public int getItemId(String itemName) {
        Integer id = itemIdMap.get(itemName);
        if (id == null) {
            id = getNextItemId(itemName);
        }
        return id;
    }

    public int getNextBlockId(String blockName) {
        Integer id = blockIdMap.get(blockName);
        if (id != null) {
            return id;
        }

        if (modRange.allocatedBlocks >= modRange.blockCount) {
            throw new RuntimeException("No more block IDs available for mod: " + modId);
        }

        int idValue = modRange.blockStart + modRange.allocatedBlocks;
        modRange.allocatedBlocks++;
        allocatedBlockIds.add(idValue);
        blockIdMap.put(blockName, idValue);
        saveGlobalRangeConfig();
        saveModIdConfig();
        return idValue;
    }

    public int getNextItemId(String itemName) {
        Integer id = itemIdMap.get(itemName);
        if (id != null) {
            return id;
        }

        if (modRange.allocatedItems >= modRange.itemCount) {
            throw new RuntimeException("No more item IDs available for mod: " + modId);
        }

        int idValue = modRange.itemStart + modRange.allocatedItems;
        modRange.allocatedItems++;
        allocatedItemIds.add(idValue);
        itemIdMap.put(itemName, idValue);
        saveGlobalRangeConfig();
        saveModIdConfig();
        return idValue;
    }

    @Deprecated
    public int getNextBlockId() {
        String blockName = "block_" + modRange.allocatedBlocks;
        return getNextBlockId(blockName);
    }

     @Deprecated
    public int getNextItemId() {
        String itemName = "item_" + modRange.allocatedItems;
        return getNextItemId(itemName);
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
        Map<String, Integer> configFileMap = new HashMap<>();
        for (Map.Entry<String, Integer> entry : itemIdMap.entrySet()) {
            configFileMap.put(entry.getKey(), entry.getValue() + 256);
        }
        return configFileMap;
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
            return modId + "=" + blockStart + "," +  blockCount + "," + itemStart + "," + itemCount + "," + allocatedBlocks + "," + allocatedItems;
        }
    }
}
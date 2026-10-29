package net.xiaoyu233.fml.vulkan.render.mesh;

import net.minecraft.Block;
import net.minecraft.IBlockAccess;
import net.xiaoyu233.fml.vulkan.VulkanConfig;

/**
 * Blocks whose rendering is safe on a mesh worker (2.3).
 *
 * <p>Verified render types: every value 0..39 is dispatched by {@code RenderBlocks.renderBlockByRenderType} (checked against the
 * decompiled source: 0 standard, 1 crossed squares, 2 torch, 3 fire, 4 fluids, 5 redstone wire, 6 crops, 7 door, 8 ladder, 9 rail,
 * 10 stairs, 11 fence, 12 lever, 13 cactus, 14 bed, 15 repeater, 16 piston base, 17 piston extension, 18 pane, 19 stem, 20 vine,
 * 21 fence gate, 22 chest-like (falls back to 0 with an override texture, otherwise handled by TESR), 23 lily pad, 24 cauldron,
 * 25 brewing stand, 26 end portal frame, 27 dragon egg, 28 cocoa, 29 tripwire source, 30 tripwire, 31 log, 32 wall,
 * 33 flower pot, 34 beacon, 35 anvil, 36 redstone logic, 37 comparator, 38 hopper, 39 quartz). Type -1 (no block rendering) and
 * types &gt;= 40 are not allowed. A block is denied when its class is listed in the system property {@code fml.vulkan.meshDenyClasses}
 * (comma list of simple or binary names) or lives outside {@code net.minecraft.*}.
 *
 * <p>A mod whose block rendering only reads the {@code IBlockAccess} it is given (no live world, no GL state) can opt in by adding its
 * package prefix to {@code fml.vulkan.meshAllowPackages} (comma list, e.g. {@code twilightforest.}) before the renderer starts. Blocks of
 * those packages mesh on workers with any render type the mod dispatches itself (its {@code RenderBlocks} hook runs on the worker too).
 * Without the opt-in every section containing one of its blocks is meshed on the render thread, which is slow in a world made of them.
 *
 * <p>The decision is cached per block id; {@link #refresh()} (render thread) precomputes every registered block, so workers only
 * read the array.
 */
public final class RenderTypeAllowList {
    private static final int SIZE = 4096;
    /** 0 unknown, 1 safe, 2 unsafe. */
    private static volatile byte[] cache = new byte[SIZE];

    private RenderTypeAllowList() {
    }

    /** Render thread: recomputes the decision of every registered block. */
    public static void refresh() {
        byte[] fresh = new byte[SIZE];
        Block[] list = Block.blocksList;
        for (int i = 0; i < SIZE; i++) {
            Block b = list[i];
            if (b != null) {
                fresh[i] = (byte) (compute(b) ? 1 : 2);
            }
        }
        cache = fresh;
    }

    public static boolean workerSafe(Block block) {
        if (block == null) {
            return true;
        }
        int id = block.blockID;
        if (id < 0 || id >= SIZE) {
            return compute(block);
        }
        byte[] c = cache;
        byte v = c[id];
        if (v == 0) {
            v = (byte) (compute(block) ? 1 : 2);
            c[id] = v; // benign race: same value from every thread
        }
        return v == 1;
    }

    /**
     * True when every block of the 16^3 section at {@code (x0, y0, z0)} is worker-safe (2.3 #11). Intended for the render thread
     * (snapshot capture), where the access is safe to read; a section that returns false must be meshed on the render thread
     * against the same snapshot. Air (id 0) is always safe.
     */
    public static boolean sectionWorkerSafe(IBlockAccess access, int x0, int y0, int z0) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id = access.getBlockId(x0 + x, y0 + y, z0 + z);
                    if (id != 0 && !workerSafe(Block.getBlock(id))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean compute(Block block) {
        try {
            int type = block.getRenderType();
            if (type == -1) {
                return true; // nothing is rendered
            }
            if (type < 0) {
                return false;
            }
            Class<?> cls = block.getClass();
            String name = cls.getName();
            if (name.startsWith("net.minecraft.")) {
                if (type > 39) {
                    return false;
                }
            } else if (!listed(System.getProperty(VulkanConfig.PROP_MESH_ALLOW_PACKAGES), name)) {
                return false;
            }
            String deny = System.getProperty("fml.vulkan.meshDenyClasses");
            if (deny != null && !deny.isEmpty()) {
                for (String d : deny.split(",")) {
                    d = d.trim();
                    if (!d.isEmpty() && (d.equals(name) || d.equals(cls.getSimpleName()))) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when {@code className} starts with one of the prefixes of the comma list {@code prefixes}. */
    private static boolean listed(String prefixes, String className) {
        if (prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String p : prefixes.split(",")) {
            p = p.trim();
            if (!p.isEmpty() && className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}

package net.xiaoyu233.fml.vulkan.texture;

import java.util.Arrays;
import java.util.HashMap;
import net.xiaoyu233.fml.vulkan.render.texture.AtlasInfo;

/**
 * Opacity map of a texture atlas (1.11, 2.4): for every sprite whether all of its alpha values are 255. Built while the atlas is stitched
 * ({@link #begin}, {@link #sprite}, {@link #register}) and queried by the terrain classifier through {@code Textures.atlas(id).isOpaque(u, v)},
 * also from mesh workers: the published table is immutable except for the opaque flags, which only ever change from true to false
 * (a frame with transparent texels arrives for a sprite that was opaque) and then bump {@link #opacityVersion}.
 */
public final class AtlasData implements AtlasInfo {
    private static final HashMap<Integer, AtlasData> BUILDERS = new HashMap<>();
    private static volatile AtlasData[] registered = new AtlasData[0];

    private final int id;
    private final int width;
    private final int height;

    private int[] rects = new int[256 * 4];
    private boolean[] opaque = new boolean[256];
    private int count;

    private int cellShift;
    private int cellsX;
    private char[] cellSprite = new char[0];
    private volatile int version;
    private volatile boolean ready;

    private AtlasData(int id, int width, int height) {
        this.id = id;
        this.width = width;
        this.height = height;
    }

    /** Starts the sprite table of atlas {@code id}; replaces an unfinished one. */
    public static void begin(int id, int width, int height) {
        BUILDERS.put(id, new AtlasData(id, width, height));
    }

    /** Adds a sprite rectangle to the atlas being built; ignored without a preceding {@link #begin}. */
    public static void sprite(int id, int x, int y, int w, int h, boolean opaque) {
        AtlasData a = BUILDERS.get(id);
        if (a != null) {
            a.add(x, y, w, h, opaque);
        }
    }

    /** Finishes the atlas: builds the lookup grid and publishes it, replacing an earlier registration of the id. */
    public static void register(int id) {
        AtlasData a = BUILDERS.remove(id);
        if (a == null) {
            a = new AtlasData(id, TextureManager.widthOf(id), TextureManager.heightOf(id));
        }
        a.buildGrid();
        AtlasData[] old = registered;
        for (int i = 0; i < old.length; i++) {
            if (old[i].id == id) {
                a.version = old[i].version + 1; // consumers compare versions: a new stitch is a change
                AtlasData[] updated = old.clone();
                updated[i] = a;
                registered = updated;
                return;
            }
        }
        AtlasData[] updated = Arrays.copyOf(old, old.length + 1);
        updated[old.length] = a;
        registered = updated;
    }

    /** The registered atlas with GL name {@code id}, or null. */
    public static AtlasData get(int id) {
        AtlasData[] list = registered;
        for (int i = 0; i < list.length; i++) {
            if (list[i].id == id) {
                return list[i];
            }
        }
        return null;
    }

    public static void forget(int id) {
        BUILDERS.remove(id);
        AtlasData[] old = registered;
        for (int i = 0; i < old.length; i++) {
            if (old[i].id == id) {
                AtlasData[] updated = new AtlasData[old.length - 1];
                System.arraycopy(old, 0, updated, 0, i);
                System.arraycopy(old, i + 1, updated, i, old.length - i - 1);
                registered = updated;
                return;
            }
        }
    }

    public static void clear() {
        BUILDERS.clear();
        registered = new AtlasData[0];
    }

    private void add(int x, int y, int w, int h, boolean isOpaque) {
        if (count == opaque.length) {
            opaque = Arrays.copyOf(opaque, count * 2);
            rects = Arrays.copyOf(rects, count * 8);
        }
        rects[count * 4] = x;
        rects[count * 4 + 1] = y;
        rects[count * 4 + 2] = w;
        rects[count * 4 + 3] = h;
        opaque[count] = isOpaque;
        count++;
    }

    /** Grid of the coarsest power-of-two cell every sprite rectangle is aligned to (stitcher output is aligned to the smallest sprite). */
    private void buildGrid() {
        int shift = 8;
        for (int i = 0; i < count && shift > 0; i++) {
            int bits = rects[i * 4] | rects[i * 4 + 1] | rects[i * 4 + 2] | rects[i * 4 + 3];
            shift = Math.min(shift, bits == 0 ? 8 : Integer.numberOfTrailingZeros(bits));
        }
        cellShift = shift;
        cellsX = Math.max(1, (width + (1 << shift) - 1) >> shift);
        int cellsY = Math.max(1, (height + (1 << shift) - 1) >> shift);
        cellSprite = new char[cellsX * cellsY];
        for (int i = 0; i < count && i < Character.MAX_VALUE; i++) {
            int x0 = rects[i * 4] >> shift;
            int y0 = rects[i * 4 + 1] >> shift;
            int x1 = Math.min(cellsX, (rects[i * 4] + rects[i * 4 + 2] + (1 << shift) - 1) >> shift);
            int y1 = Math.min(cellsY, (rects[i * 4 + 1] + rects[i * 4 + 3] + (1 << shift) - 1) >> shift);
            for (int cy = y0; cy < y1; cy++) {
                for (int cx = x0; cx < x1; cx++) {
                    cellSprite[cy * cellsX + cx] = (char) (i + 1);
                }
            }
        }
        ready = true;
    }

    /** Whether the sprite under (u, v) is fully opaque; false outside every sprite (the safe answer for the classifier). */
    @Override
    public boolean isOpaque(float u, float v) {
        if (!ready || width <= 0 || height <= 0) {
            return false;
        }
        int x = (int) (u * width);
        int y = (int) (v * height);
        x = x < 0 ? 0 : Math.min(x, width - 1);
        y = y < 0 ? 0 : Math.min(y, height - 1);
        char index = cellSprite[(y >> cellShift) * cellsX + (x >> cellShift)];
        return index != 0 && opaque[index - 1];
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int opacityVersion() {
        return version;
    }

    /** Number of sprites. */
    public int spriteCount() {
        return count;
    }

    /** Number of sprites currently flagged opaque. */
    public int opaqueCount() {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (opaque[i]) {
                n++;
            }
        }
        return n;
    }

    /** Writes {@code x, y, w, h} of sprite {@code index} (0 .. spriteCount - 1) into {@code out}. */
    public void spriteRect(int index, int[] out) {
        System.arraycopy(rects, index * 4, out, 0, 4);
    }

    public boolean spriteOpaque(int index) {
        return opaque[index];
    }

    /** True when every pixel of {@code argb[offset .. offset + count)} has alpha 255. */
    public static boolean allOpaque(int[] argb, int offset, int count) {
        int acc = -1;
        for (int i = offset, end = offset + count; i < end; i++) {
            acc &= argb[i];
        }
        return (acc >>> 24) == 0xFF;
    }

    /** A region of the atlas was uploaded: when it is exactly a sprite flagged opaque and now has transparent texels, flag it and bump. */
    void onUpload(int x, int y, int w, int h, int[] argb) {
        if (!ready || x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        char index = cellSprite[(y >> cellShift) * cellsX + (x >> cellShift)];
        if (index == 0) {
            return;
        }
        int s = (index - 1) * 4;
        if (rects[s] != x || rects[s + 1] != y || rects[s + 2] != w || rects[s + 3] != h || !opaque[index - 1]) {
            return;
        }
        if (!allOpaque(argb, 0, w * h)) {
            opaque[index - 1] = false;
            version++;
        }
    }
}

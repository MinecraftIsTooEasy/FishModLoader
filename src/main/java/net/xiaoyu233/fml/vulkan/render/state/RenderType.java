package net.xiaoyu233.fml.vulkan.render.state;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * (material key, vertex format, texture id, lightmap texture id), interned (1.7). Render thread only. The NR-00 skeleton implements the
 * interning and {@link #orderSensitive()} completely because the recording fakes of {@code render.testing} need render types.
 */
public final class RenderType {
    private record Key(long materialKey, VertexFormat format, int texture, int lightmapTexture) {
    }

    private static final HashMap<Key, RenderType> INTERNED = new HashMap<>();
    private static final ArrayList<RenderType> BY_ID = new ArrayList<>();

    private final long materialKey;
    private final VertexFormat format;
    private final int texture;
    private final int lightmapTexture;
    private final boolean orderSensitive;
    private final int id;

    private RenderType(long materialKey, VertexFormat format, int texture, int lightmapTexture, int id) {
        this.materialKey = materialKey;
        this.format = format;
        this.texture = texture;
        this.lightmapTexture = lightmapTexture;
        this.id = id;
        int depthFunc = MaterialKey.depthFunc(materialKey);
        this.orderSensitive = MaterialKey.blend(materialKey)
                || !MaterialKey.depthWrite(materialKey)
                || depthFunc != DepthFunc.LESS.ordinal() && depthFunc != DepthFunc.LEQUAL.ordinal()
                || !MaterialKey.depthTest(materialKey)
                || MaterialKey.colorMask(materialKey) != 7
                || MaterialKey.logicOp(materialKey);
    }

    private static final int CACHE_SIZE = 256;
    private static final RenderType[] CACHE = new RenderType[CACHE_SIZE];
    private static RenderType last;

    /** Drops the lookup caches and the intern table (resource reload); ids restart at 0, so holders must re-resolve. */
    public static void clear() {
        INTERNED.clear();
        BY_ID.clear();
        java.util.Arrays.fill(CACHE, null);
        last = null;
    }

    public static RenderType of(long materialKey, VertexFormat format, int texture, int lightmapTexture) {
        RenderType l = last;
        if (l != null && l.materialKey == materialKey && l.format == format && l.texture == texture && l.lightmapTexture == lightmapTexture) {
            return l;
        }
        long h = materialKey * 0x9E3779B97F4A7C15L + texture * 31L + lightmapTexture * 961L + System.identityHashCode(format);
        int slot = (int) (h ^ (h >>> 32) ^ (h >>> 17)) & (CACHE_SIZE - 1);
        RenderType c = CACHE[slot];
        if (c != null && c.materialKey == materialKey && c.format == format && c.texture == texture && c.lightmapTexture == lightmapTexture) {
            last = c;
            return c;
        }
        RenderType t = slowOf(materialKey, format, texture, lightmapTexture);
        CACHE[slot] = t;
        last = t;
        return t;
    }

    private static RenderType slowOf(long materialKey, VertexFormat format, int texture, int lightmapTexture) {
        Key key = new Key(materialKey, format, texture, lightmapTexture);
        RenderType type = INTERNED.get(key);
        if (type == null) {
            type = new RenderType(materialKey, format, texture, lightmapTexture, BY_ID.size());
            INTERNED.put(key, type);
            BY_ID.add(type);
        }
        return type;
    }

    public long materialKey() {
        return materialKey;
    }

    public VertexFormat format() {
        return format;
    }

    public int texture() {
        return texture;
    }

    public int lightmapTexture() {
        return lightmapTexture;
    }

    /** blend, no depth write, depth func other than LESS/LEQUAL, no depth test, colour mask other than RGB, or logic op. */
    public boolean orderSensitive() {
        return orderSensitive;
    }

    /** Dense id in interning order. */
    public int id() {
        return id;
    }

    @Override
    public String toString() {
        return "RenderType#" + id + "{key=0x" + Long.toHexString(materialKey) + ", " + format + ", tex=" + texture + ", light=" + lightmapTexture + '}';
    }
}

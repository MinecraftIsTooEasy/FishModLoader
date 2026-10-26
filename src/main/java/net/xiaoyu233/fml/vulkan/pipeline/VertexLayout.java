package net.xiaoyu233.fml.vulkan.pipeline;

import org.lwjgl.vulkan.VK10;

/**
 * Immutable, interned description of the vertex input of a draw (../docs/ARCHITECTURE.md (outside the repo) section 5.5): equal layouts are
 * the same instance (compare with {@code ==}), {@link #id()} is below {@link #MAX_LAYOUTS}.
 *
 * <p>Five attributes (position, color, tex0, tex1, normal) map to the shader locations of {@link ShaderInterface}. An
 * attribute whose format is 0 is absent and is fed from the dummy binding {@link #DUMMY_BINDING} by the pipeline: for absent
 * attributes {@link #binding(int)} returns {@link #DUMMY_BINDING} and {@link #offset(int)} returns 0, so layouts that differ
 * only in unused slots intern to the same instance. Strides of bindings that no present attribute uses are normalized to 0.
 */
public final class VertexLayout {
    public static final int ATTR_POSITION = 0;
    public static final int ATTR_COLOR = 1;
    public static final int ATTR_TEX0 = 2;
    public static final int ATTR_TEX1 = 3;
    public static final int ATTR_NORMAL = 4;
    public static final int ATTR_COUNT = 5;

    /** Binding of the 16-byte zero buffer that feeds absent attributes (input rate INSTANCE, stride 16). */
    public static final int DUMMY_BINDING = ShaderInterface.DUMMY_BINDING;
    /** Number of real bindings (0..6). */
    public static final int MAX_BINDINGS = 7;
    /** Exclusive upper bound of {@link #id()}. */
    public static final int MAX_LAYOUTS = 4096;

    private static final Object LOCK = new Object();
    /** Intern table: open addressing with linear probing, power-of-two size, guarded by LOCK. */
    private static VertexLayout[] table = new VertexLayout[64];
    private static int count;

    /**
     * D2's canonical immediate-mode vertex, single binding 0, stride 56: position f32x4 @0, tex0 f32x4 @16, tex1 f32x2 @32,
     * normal f32x3 @40, color u8x4 UNORM @52.
     */
    public static final VertexLayout IMMEDIATE = of(
            new int[]{VK10.VK_FORMAT_R32G32B32A32_SFLOAT, VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
                    VK10.VK_FORMAT_R32G32_SFLOAT, VK10.VK_FORMAT_R32G32B32_SFLOAT},
            new int[]{0, 0, 0, 0, 0},
            new int[]{0, 52, 16, 32, 40},
            new int[]{56, 0, 0, 0, 0, 0, 0});

    private final int id;
    private final int hash;
    private final int[] format = new int[ATTR_COUNT];
    private final int[] binding = new int[ATTR_COUNT];
    private final int[] offset = new int[ATTR_COUNT];
    private final int[] stride = new int[MAX_BINDINGS];
    private final int bindingMask;
    private final int attribMask;

    private VertexLayout(int id, int hash, int[] format, int[] binding, int[] offset, int[] bindingStride, int bindingMask) {
        this.id = id;
        this.hash = hash;
        this.bindingMask = bindingMask;
        for (int a = 0; a < ATTR_COUNT; a++) {
            if (format[a] != 0) {
                this.format[a] = format[a];
                this.binding[a] = binding[a];
                this.offset[a] = offset[a];
            } else {
                this.binding[a] = DUMMY_BINDING;
            }
        }
        for (int b = 0; b < MAX_BINDINGS; b++) {
            if ((bindingMask & (1 << b)) != 0) {
                this.stride[b] = bindingStride[b];
            }
        }
        int mask = 0;
        if (this.format[ATTR_COLOR] != 0) {
            mask |= ShaderInterface.ATTR_COLOR_BIT;
        }
        if (this.format[ATTR_TEX0] != 0) {
            mask |= ShaderInterface.ATTR_TEX0_BIT;
            if (isSignedShortNormalized(this.format[ATTR_TEX0])) {
                mask |= ShaderInterface.ATTR_TEX0_SHORT_BIT;
            }
        }
        if (this.format[ATTR_TEX1] != 0) {
            mask |= ShaderInterface.ATTR_TEX1_BIT;
            if (isSignedShortNormalized(this.format[ATTR_TEX1])) {
                mask |= ShaderInterface.ATTR_TEX1_SHORT_BIT;
            }
        }
        if (this.format[ATTR_NORMAL] != 0) {
            mask |= ShaderInterface.ATTR_NORMAL_BIT;
        }
        this.attribMask = mask;
    }

    /**
     * Returns the interned layout.
     *
     * @param format        VkFormat per attribute, 0 = absent (length {@link #ATTR_COUNT})
     * @param binding       vertex binding 0..6 per present attribute (length {@link #ATTR_COUNT})
     * @param offset        byte offset within the binding's vertex per present attribute (length {@link #ATTR_COUNT})
     * @param bindingStride byte stride per binding 0..6 (length {@link #MAX_BINDINGS})
     */
    public static VertexLayout of(int[] format, int[] binding, int[] offset, int[] bindingStride) {
        if (format.length != ATTR_COUNT || binding.length != ATTR_COUNT || offset.length != ATTR_COUNT
                || bindingStride.length != MAX_BINDINGS) {
            throw new IllegalArgumentException("VertexLayout.of expects arrays of length " + ATTR_COUNT + "/" + ATTR_COUNT
                    + "/" + ATTR_COUNT + "/" + MAX_BINDINGS + " but got " + format.length + "/" + binding.length + "/"
                    + offset.length + "/" + bindingStride.length);
        }
        int used = 0;
        for (int a = 0; a < ATTR_COUNT; a++) {
            if (format[a] == 0) {
                continue;
            }
            if (binding[a] < 0 || binding[a] >= MAX_BINDINGS) {
                throw new IllegalArgumentException("attribute " + a + ": binding " + binding[a] + " is not in 0.." + (MAX_BINDINGS - 1));
            }
            if (offset[a] < 0) {
                throw new IllegalArgumentException("attribute " + a + ": negative offset " + offset[a]);
            }
            used |= 1 << binding[a];
        }
        for (int b = 0; b < MAX_BINDINGS; b++) {
            if ((used & (1 << b)) != 0 && bindingStride[b] < 0) {
                throw new IllegalArgumentException("binding " + b + ": negative stride " + bindingStride[b]);
            }
        }
        int hash = hash(format, binding, offset, bindingStride, used);
        synchronized (LOCK) {
            VertexLayout[] t = table;
            int mask = t.length - 1;
            for (int i = hash & mask; t[i] != null; i = (i + 1) & mask) {
                VertexLayout candidate = t[i];
                if (candidate.hash == hash && candidate.matches(format, binding, offset, bindingStride, used)) {
                    return candidate;
                }
            }
            if (count >= MAX_LAYOUTS) {
                throw new IllegalStateException("Too many distinct vertex layouts (limit " + MAX_LAYOUTS + ")");
            }
            VertexLayout layout = new VertexLayout(count, hash, format, binding, offset, bindingStride, used);
            count++;
            if (count * 2 > t.length) {
                t = grow(t);
            }
            insert(t, layout);
            table = t;
            return layout;
        }
    }

    /** Dense id, unique per interned layout and below {@link #MAX_LAYOUTS}. */
    public int id() {
        return id;
    }

    /** VkFormat of the attribute, 0 when absent. */
    public int format(int attr) {
        return format[attr];
    }

    /** Vertex buffer binding (0..6) of the attribute; {@link #DUMMY_BINDING} when the attribute is absent. */
    public int binding(int attr) {
        return binding[attr];
    }

    /** Byte offset of the attribute within its binding's vertex, 0 when absent. */
    public int offset(int attr) {
        return offset[attr];
    }

    /** Stride of a real binding (0..6; 0 when no present attribute uses it); the dummy binding reports its fixed stride 16. */
    public int bindingStride(int binding) {
        return binding == DUMMY_BINDING ? ShaderInterface.DUMMY_BINDING_STRIDE : stride[binding];
    }

    /** Bit per real binding (bit b = binding b) used by a present attribute. */
    public int bindingMask() {
        return bindingMask;
    }

    /**
     * Push-constant {@code attribMask} of this layout ({@link ShaderInterface} ATTR_*_BIT): presence bits for color, tex0, tex1
     * and normal (the position is always present and has no bit), plus ATTR_TEX0_SHORT_BIT / ATTR_TEX1_SHORT_BIT when the
     * texcoord is a 16-bit SNORM format that the shader has to scale back by 32767.
     */
    public int attribMask() {
        return attribMask;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        String[] names = {"pos", "color", "tex0", "tex1", "normal"};
        StringBuilder sb = new StringBuilder("VertexLayout#").append(id).append('{');
        for (int a = 0; a < ATTR_COUNT; a++) {
            if (format[a] != 0) {
                sb.append(names[a]).append("=fmt").append(format[a]).append('@').append(binding[a]).append(':').append(offset[a]).append(' ');
            }
        }
        sb.append("strides=[");
        for (int b = 0; b < MAX_BINDINGS; b++) {
            sb.append(stride[b]).append(b + 1 < MAX_BINDINGS ? "," : "");
        }
        return sb.append("]}").toString();
    }

    private boolean matches(int[] f, int[] b, int[] o, int[] s, int used) {
        if (bindingMask != used) {
            return false;
        }
        for (int a = 0; a < ATTR_COUNT; a++) {
            if (format[a] != f[a]) {
                return false;
            }
            if (f[a] != 0 && (binding[a] != b[a] || offset[a] != o[a])) {
                return false;
            }
        }
        for (int i = 0; i < MAX_BINDINGS; i++) {
            if ((used & (1 << i)) != 0 && stride[i] != s[i]) {
                return false;
            }
        }
        return true;
    }

    /** Hash over the normalized content (absent attributes and unused bindings do not contribute). */
    private static int hash(int[] f, int[] b, int[] o, int[] s, int used) {
        int h = used;
        for (int a = 0; a < ATTR_COUNT; a++) {
            h = h * 31 + f[a];
            if (f[a] != 0) {
                h = h * 31 + b[a];
                h = h * 31 + o[a];
            }
        }
        for (int i = 0; i < MAX_BINDINGS; i++) {
            if ((used & (1 << i)) != 0) {
                h = h * 31 + s[i];
            }
        }
        return h ^ (h >>> 16);
    }

    private static VertexLayout[] grow(VertexLayout[] old) {
        VertexLayout[] grown = new VertexLayout[old.length * 2];
        for (VertexLayout l : old) {
            if (l != null) {
                insert(grown, l);
            }
        }
        return grown;
    }

    private static void insert(VertexLayout[] t, VertexLayout layout) {
        int mask = t.length - 1;
        int i = layout.hash & mask;
        while (t[i] != null) {
            i = (i + 1) & mask;
        }
        t[i] = layout;
    }

    private static boolean isSignedShortNormalized(int vkFormat) {
        return vkFormat == VK10.VK_FORMAT_R16_SNORM || vkFormat == VK10.VK_FORMAT_R16G16_SNORM
                || vkFormat == VK10.VK_FORMAT_R16G16B16_SNORM || vkFormat == VK10.VK_FORMAT_R16G16B16A16_SNORM;
    }
}

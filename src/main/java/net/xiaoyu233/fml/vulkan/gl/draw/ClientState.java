package net.xiaoyu233.fml.vulkan.gl.draw;

import net.xiaoyu233.fml.vulkan.gl.GLErrors;
import net.xiaoyu233.fml.vulkan.gl.GLUnsupported;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Client-side GL state owned by module D2: the vertex arrays (enable bit, size/type/stride and the pointer or buffer offset), the
 * ARRAY/ELEMENT_ARRAY buffer bindings, the client attribute stack, vertex array objects and the generic vertex attribute bookkeeping.
 * <p>
 * An array stores <i>what the application passed</i>: the client memory address (the buffer is kept alive by {@code ref}) or, for a
 * buffer-object array, the buffer <i>name</i> bound at the pointer call plus the byte offset. Nothing is resolved before a draw is
 * issued, exactly like GL. Client state functions are never compiled into display lists. Render thread only.
 */
public final class ClientState {
    /** Pointer kinds for {@link #pointer} / {@link #pointerVbo}. */
    public static final int ARRAY_VERTEX = 0;
    public static final int ARRAY_COLOR = 1;
    public static final int ARRAY_NORMAL = 2;
    public static final int ARRAY_TEXCOORD = 3;

    static final int MAX_TEX_UNITS = 8;
    static final int MAX_GENERIC_ATTRIBS = 16;
    private static final int MAX_ATTRIB_STACK = 16;

    // bits of VaoState.ignoredEnableMask: arrays GL has but the fixed-function shaders of this port do not use (enable state only)
    private static final int IGNORED_INDEX = 1;
    private static final int IGNORED_EDGE_FLAG = 2;
    private static final int IGNORED_SECONDARY_COLOR = 4;
    private static final int IGNORED_FOG_COORD = 8;

    /** One vertex array. */
    static final class ArrayState {
        boolean enabled;
        int size;
        int type = GL11.GL_FLOAT;
        int stride;
        /** Client memory address, or the byte offset into {@link #buffer} when {@code buffer != 0}. */
        long pointer;
        /** Name of the ARRAY_BUFFER bound at the pointer call (0 = client memory). */
        int buffer;
        /** Keeps client memory alive (the NIO buffer the address was taken from). */
        Object ref;
        /** First byte after the client buffer's backing storage (0 = unknown): reads beyond it are refused instead of crashing the JVM. */
        long limit;

        ArrayState(int defaultSize) {
            size = defaultSize;
        }

        void set(int size, int type, int stride, long pointer, int buffer, Object ref, long limit) {
            this.size = size;
            this.type = type;
            this.stride = stride;
            this.pointer = pointer;
            this.buffer = buffer;
            this.ref = ref;
            this.limit = limit;
        }

        void copyFrom(ArrayState o) {
            enabled = o.enabled;
            size = o.size;
            type = o.type;
            stride = o.stride;
            pointer = o.pointer;
            buffer = o.buffer;
            ref = o.ref;
            limit = o.limit;
        }

        void reset(int defaultSize) {
            enabled = false;
            size = defaultSize;
            type = GL11.GL_FLOAT;
            stride = 0;
            pointer = 0L;
            buffer = 0;
            ref = null;
            limit = 0L;
        }
    }

    /** Everything a vertex array object (GL 3.0 / ARB_vertex_array_object) owns; vertex array 0 is the default state. */
    static final class VaoState {
        final ArrayState vertex = new ArrayState(4);
        final ArrayState color = new ArrayState(4);
        final ArrayState normal = new ArrayState(3);
        final ArrayState[] tex = new ArrayState[MAX_TEX_UNITS];
        int elementBuffer;
        int ignoredEnableMask;
        int genericEnableMask;
        /** The name was bound at least once (glIsVertexArray). */
        boolean exists;

        VaoState() {
            for (int i = 0; i < MAX_TEX_UNITS; i++) {
                tex[i] = new ArrayState(4);
            }
        }

        void copyFrom(VaoState o) {
            vertex.copyFrom(o.vertex);
            color.copyFrom(o.color);
            normal.copyFrom(o.normal);
            for (int i = 0; i < MAX_TEX_UNITS; i++) {
                tex[i].copyFrom(o.tex[i]);
            }
            elementBuffer = o.elementBuffer;
            ignoredEnableMask = o.ignoredEnableMask;
            genericEnableMask = o.genericEnableMask;
        }

        void reset() {
            vertex.reset(4);
            color.reset(4);
            normal.reset(3);
            for (int i = 0; i < MAX_TEX_UNITS; i++) {
                tex[i].reset(4);
            }
            elementBuffer = 0;
            ignoredEnableMask = 0;
            genericEnableMask = 0;
        }
    }

    private static final VaoState defaultVao = new VaoState();
    private static VaoState vao = defaultVao;
    private static int vaoName;
    private static final IdRanges vaoNames = new IdRanges();
    private static VaoState[] vaos = new VaoState[8];

    private static int clientActiveTexture;

    // client attribute stack (glPushClientAttrib / glPopClientAttrib)
    private static final VaoState[] attribStack = new VaoState[MAX_ATTRIB_STACK];
    private static final int[] attribStackMask = new int[MAX_ATTRIB_STACK];
    private static final int[] attribStackActiveTexture = new int[MAX_ATTRIB_STACK];
    private static final int[] attribStackArrayBuffer = new int[MAX_ATTRIB_STACK];
    private static int attribDepth;

    private static boolean ignoredArrayWarned;

    private ClientState() {
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // glGet / glIsEnabled
    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Answers the client-state glGetInteger names (array/element buffer bindings, CLIENT_ACTIVE_TEXTURE, the vertex array
     * size/type/stride/binding queries, list base/index/mode/nesting, client attribute stack depth, vertex array binding and the client
     * array enable caps). Returns false when {@code pname} is not owned here. The value goes to {@code out.put(out.position(), v)}
     * (LWJGL 2 never advances the position); with {@code out == null} only ownership is reported.
     */
    public static boolean getIntegerv(int pname, IntBuffer out) {
        int v;
        switch (pname) {
            case GL15.GL_ARRAY_BUFFER_BINDING:
                v = BufferObjects.arrayBinding();
                break;
            case GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING:
                v = vao.elementBuffer;
                break;
            case GL13.GL_CLIENT_ACTIVE_TEXTURE:
                v = GL13.GL_TEXTURE0 + clientActiveTexture;
                break;
            case GL11.GL_LIST_BASE:
                v = DisplayLists.listBase();
                break;
            case GL11.GL_LIST_INDEX:
                v = DisplayLists.currentListName();
                break;
            case GL11.GL_LIST_MODE:
                v = DisplayLists.currentListMode();
                break;
            case GL11.GL_MAX_LIST_NESTING:
                v = DisplayLists.MAX_LIST_NESTING;
                break;
            case GL11.GL_VERTEX_ARRAY_SIZE:
                v = vao.vertex.size;
                break;
            case GL11.GL_VERTEX_ARRAY_TYPE:
                v = vao.vertex.type;
                break;
            case GL11.GL_VERTEX_ARRAY_STRIDE:
                v = vao.vertex.stride;
                break;
            case GL15.GL_VERTEX_ARRAY_BUFFER_BINDING:
                v = vao.vertex.buffer;
                break;
            case GL11.GL_NORMAL_ARRAY_TYPE:
                v = vao.normal.type;
                break;
            case GL11.GL_NORMAL_ARRAY_STRIDE:
                v = vao.normal.stride;
                break;
            case GL15.GL_NORMAL_ARRAY_BUFFER_BINDING:
                v = vao.normal.buffer;
                break;
            case GL11.GL_COLOR_ARRAY_SIZE:
                v = vao.color.size;
                break;
            case GL11.GL_COLOR_ARRAY_TYPE:
                v = vao.color.type;
                break;
            case GL11.GL_COLOR_ARRAY_STRIDE:
                v = vao.color.stride;
                break;
            case GL15.GL_COLOR_ARRAY_BUFFER_BINDING:
                v = vao.color.buffer;
                break;
            case GL11.GL_TEXTURE_COORD_ARRAY_SIZE:
                v = vao.tex[clientActiveTexture].size;
                break;
            case GL11.GL_TEXTURE_COORD_ARRAY_TYPE:
                v = vao.tex[clientActiveTexture].type;
                break;
            case GL11.GL_TEXTURE_COORD_ARRAY_STRIDE:
                v = vao.tex[clientActiveTexture].stride;
                break;
            case GL15.GL_TEXTURE_COORD_ARRAY_BUFFER_BINDING:
                v = vao.tex[clientActiveTexture].buffer;
                break;
            case GL11.GL_CLIENT_ATTRIB_STACK_DEPTH:
                v = attribDepth;
                break;
            case GL11.GL_MAX_CLIENT_ATTRIB_STACK_DEPTH:
                v = MAX_ATTRIB_STACK;
                break;
            case GL30.GL_VERTEX_ARRAY_BINDING:
                v = vaoName;
                break;
            default:
                int[] binding = BINDING_SCRATCH;
                if (BufferObjects.getBinding(pname, binding)) {
                    v = binding[0];
                    break;
                }
                Boolean enabled = isEnabled(pname);
                if (enabled == null) {
                    return false;
                }
                v = enabled ? 1 : 0;
        }
        if (out != null && out.hasRemaining()) {
            out.put(out.position(), v);
        }
        return true;
    }

    private static final int[] BINDING_SCRATCH = new int[1];

    /** The enable state of a client array capability, or null when {@code cap} is not one (GL_TEXTURE_COORD_ARRAY follows the client active unit). */
    public static Boolean isEnabled(int cap) {
        switch (cap) {
            case GL11.GL_VERTEX_ARRAY:
                return vao.vertex.enabled;
            case GL11.GL_NORMAL_ARRAY:
                return vao.normal.enabled;
            case GL11.GL_COLOR_ARRAY:
                return vao.color.enabled;
            case GL11.GL_TEXTURE_COORD_ARRAY:
                return vao.tex[clientActiveTexture].enabled;
            case GL11.GL_INDEX_ARRAY:
                return (vao.ignoredEnableMask & IGNORED_INDEX) != 0;
            case GL11.GL_EDGE_FLAG_ARRAY:
                return (vao.ignoredEnableMask & IGNORED_EDGE_FLAG) != 0;
            case GL14.GL_SECONDARY_COLOR_ARRAY:
                return (vao.ignoredEnableMask & IGNORED_SECONDARY_COLOR) != 0;
            case GL14.GL_FOG_COORDINATE_ARRAY:
                return (vao.ignoredEnableMask & IGNORED_FOG_COORD) != 0;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // enable / client active texture
    // ------------------------------------------------------------------------------------------------------------------------

    public static void enableClientState(int cap) {
        setEnabled(cap, true);
    }

    public static void disableClientState(int cap) {
        setEnabled(cap, false);
    }

    private static void setEnabled(int cap, boolean on) {
        switch (cap) {
            case GL11.GL_VERTEX_ARRAY:
                vao.vertex.enabled = on;
                return;
            case GL11.GL_NORMAL_ARRAY:
                vao.normal.enabled = on;
                return;
            case GL11.GL_COLOR_ARRAY:
                vao.color.enabled = on;
                return;
            case GL11.GL_TEXTURE_COORD_ARRAY:
                vao.tex[clientActiveTexture].enabled = on;
                return;
            case GL11.GL_INDEX_ARRAY:
                setIgnored(IGNORED_INDEX, on, "GL_INDEX_ARRAY");
                return;
            case GL11.GL_EDGE_FLAG_ARRAY:
                setIgnored(IGNORED_EDGE_FLAG, on, "GL_EDGE_FLAG_ARRAY");
                return;
            case GL14.GL_SECONDARY_COLOR_ARRAY:
                setIgnored(IGNORED_SECONDARY_COLOR, on, "GL_SECONDARY_COLOR_ARRAY");
                return;
            case GL14.GL_FOG_COORDINATE_ARRAY:
                setIgnored(IGNORED_FOG_COORD, on, "GL_FOG_COORD_ARRAY");
                return;
            default:
                GLErrors.set(GL11.GL_INVALID_ENUM);
        }
    }

    private static void setIgnored(int bit, boolean on, String name) {
        if (on) {
            vao.ignoredEnableMask |= bit;
            if (!ignoredArrayWarned) {
                ignoredArrayWarned = true;
                GLUnsupported.call("glEnableClientState(" + name + ")");
            }
        } else {
            vao.ignoredEnableMask &= ~bit;
        }
    }

    /** glClientActiveTexture: GL_TEXTURE0 + unit; units beyond the supported range raise GL_INVALID_ENUM. */
    public static void setClientActiveTexture(int texture) {
        int unit = texture - GL13.GL_TEXTURE0;
        if (unit < 0 || unit >= MAX_TEX_UNITS) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        clientActiveTexture = unit;
    }

    public static int getClientActiveTexture() {
        return clientActiveTexture;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // buffer bindings
    // ------------------------------------------------------------------------------------------------------------------------

    public static int getArrayBufferBinding() {
        return BufferObjects.arrayBinding();
    }

    public static int getElementArrayBufferBinding() {
        return vao.elementBuffer;
    }

    /** Only {@link BufferObjects#bind} calls this. */
    static void setElementArrayBufferBinding(int name) {
        vao.elementBuffer = name;
    }

    /** A buffer object was deleted: the current vertex array's element binding is reset (array pointers keep the stale name and are refused at draw time). */
    static void onBufferDeleted(int name) {
        if (vao.elementBuffer == name) {
            vao.elementBuffer = 0;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // array pointers
    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Client-memory array pointer (the LWJGL 2 NIO buffer forms): the address is taken now (buffer address plus position, like LWJGL 2
     * does), the buffer is kept alive and its storage end remembered for bounds checks. {@code size} is ignored for normals.
     */
    public static void pointer(int which, int size, int type, int stride, ByteBuffer b) {
        set(which, size, type, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    public static void pointer(int which, int size, int type, int stride, ShortBuffer b) {
        set(which, size, type, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    public static void pointer(int which, int size, int type, int stride, IntBuffer b) {
        set(which, size, type, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    public static void pointer(int which, int size, int type, int stride, FloatBuffer b) {
        set(which, size, type, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    public static void pointer(int which, int size, int type, int stride, DoubleBuffer b) {
        set(which, size, type, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    /** Buffer-object array pointer: {@code offset} into the buffer currently bound to GL_ARRAY_BUFFER. */
    public static void pointerVbo(int which, int size, int type, int stride, long offset) {
        int buffer = BufferObjects.arrayBinding();
        if (buffer == 0 || offset < 0) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        set(which, size, type, stride, offset, buffer, null, 0L);
    }

    private static void set(int which, int size, int type, int stride, long pointer, int buffer, Object ref, long limit) {
        if (stride < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        ArrayState a;
        switch (which) {
            case ARRAY_VERTEX:
                if (size < 2 || size > 4) {
                    GLErrors.set(GL11.GL_INVALID_VALUE);
                    return;
                }
                if (type != GL11.GL_SHORT && type != GL11.GL_INT && type != GL11.GL_FLOAT && type != GL11.GL_DOUBLE) {
                    GLErrors.set(GL11.GL_INVALID_ENUM);
                    return;
                }
                a = vao.vertex;
                break;
            case ARRAY_COLOR:
                if (size != 3 && size != 4) {
                    GLErrors.set(GL11.GL_INVALID_VALUE);
                    return;
                }
                if (typeSize(type) == 0) {
                    GLErrors.set(GL11.GL_INVALID_ENUM);
                    return;
                }
                a = vao.color;
                break;
            case ARRAY_NORMAL:
                size = 3;
                if (type != GL11.GL_BYTE && type != GL11.GL_SHORT && type != GL11.GL_INT && type != GL11.GL_FLOAT
                        && type != GL11.GL_DOUBLE) {
                    GLErrors.set(GL11.GL_INVALID_ENUM);
                    return;
                }
                a = vao.normal;
                break;
            case ARRAY_TEXCOORD:
                if (size < 1 || size > 4) {
                    GLErrors.set(GL11.GL_INVALID_VALUE);
                    return;
                }
                if (type != GL11.GL_SHORT && type != GL11.GL_INT && type != GL11.GL_FLOAT && type != GL11.GL_DOUBLE) {
                    GLErrors.set(GL11.GL_INVALID_ENUM);
                    return;
                }
                a = vao.tex[clientActiveTexture];
                break;
            default:
                return;
        }
        a.set(size, type, stride, pointer, buffer, ref, limit);
    }

    /** Byte size of a GL component type for arrays (0 = not a valid array component type). */
    static int typeSize(int type) {
        switch (type) {
            case GL11.GL_BYTE:
            case GL11.GL_UNSIGNED_BYTE:
                return 1;
            case GL11.GL_SHORT:
            case GL11.GL_UNSIGNED_SHORT:
                return 2;
            case GL11.GL_INT:
            case GL11.GL_UNSIGNED_INT:
            case GL11.GL_FLOAT:
                return 4;
            case GL11.GL_DOUBLE:
                return 8;
            default:
                return 0;
        }
    }

    // current vertex array state, read by the draw code

    static ArrayState vertex() {
        return vao.vertex;
    }

    static ArrayState color() {
        return vao.color;
    }

    static ArrayState normal() {
        return vao.normal;
    }

    static ArrayState tex(int unit) {
        return vao.tex[unit];
    }

    /** True when some texture unit other than 0 and 1 has its array enabled (those arrays are not used by the fixed-function shaders). */
    static boolean unsupportedTexUnitEnabled() {
        for (int i = 2; i < MAX_TEX_UNITS; i++) {
            if (vao.tex[i].enabled) {
                return true;
            }
        }
        return false;
    }

    static int genericEnableMask() {
        return vao.genericEnableMask;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // glInterleavedArrays
    // ------------------------------------------------------------------------------------------------------------------------

    public static void interleavedArrays(int format, int stride, ByteBuffer b) {
        interleavedArrays(format, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    public static void interleavedArrays(int format, int stride, ShortBuffer b) {
        interleavedArrays(format, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    public static void interleavedArrays(int format, int stride, IntBuffer b) {
        interleavedArrays(format, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    public static void interleavedArrays(int format, int stride, FloatBuffer b) {
        interleavedArrays(format, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    public static void interleavedArrays(int format, int stride, DoubleBuffer b) {
        interleavedArrays(format, stride, Mem.addr(b), 0, b, Mem.endOfStorage(b));
    }

    /** glInterleavedArrays with an offset into the buffer bound to GL_ARRAY_BUFFER. */
    public static void interleavedArraysVbo(int format, int stride, long offset) {
        int buffer = BufferObjects.arrayBinding();
        if (buffer == 0 || offset < 0) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        interleavedArrays(format, stride, offset, buffer, null, 0L);
    }

    /**
     * glInterleavedArrays: sets up (and enables/disables) the position/color/normal/texcoord arrays described by the 14 GL formats. A
     * {@code stride} of 0 selects the tightly packed stride of the format. {@code buffer == 0} means client memory.
     */
    private static void interleavedArrays(int format, int stride, long pointer, int buffer, Object ref, long limit) {
        // per format: et ec en | texcoord size, color size/type, vertex size | pc pn pv offsets | total size (all in bytes)
        boolean et;
        boolean ec;
        boolean en;
        int st = 0;
        int sc = 0;
        int sv;
        int pc = 0;
        int pn = 0;
        int pv;
        int s;
        boolean colorUbyte = false;
        switch (format) {
            case GL11.GL_V2F:
                et = false; ec = false; en = false; sv = 2; pv = 0; s = 8;
                break;
            case GL11.GL_V3F:
                et = false; ec = false; en = false; sv = 3; pv = 0; s = 12;
                break;
            case GL11.GL_C4UB_V2F:
                et = false; ec = true; en = false; sc = 4; colorUbyte = true; sv = 2; pc = 0; pv = 4; s = 12;
                break;
            case GL11.GL_C4UB_V3F:
                et = false; ec = true; en = false; sc = 4; colorUbyte = true; sv = 3; pc = 0; pv = 4; s = 16;
                break;
            case GL11.GL_C3F_V3F:
                et = false; ec = true; en = false; sc = 3; sv = 3; pc = 0; pv = 12; s = 24;
                break;
            case GL11.GL_N3F_V3F:
                et = false; ec = false; en = true; sv = 3; pn = 0; pv = 12; s = 24;
                break;
            case GL11.GL_C4F_N3F_V3F:
                et = false; ec = true; en = true; sc = 4; sv = 3; pc = 0; pn = 16; pv = 28; s = 40;
                break;
            case GL11.GL_T2F_V3F:
                et = true; ec = false; en = false; st = 2; sv = 3; pv = 8; s = 20;
                break;
            case GL11.GL_T4F_V4F:
                et = true; ec = false; en = false; st = 4; sv = 4; pv = 16; s = 32;
                break;
            case GL11.GL_T2F_C4UB_V3F:
                et = true; ec = true; en = false; st = 2; sc = 4; colorUbyte = true; sv = 3; pc = 8; pv = 12; s = 24;
                break;
            case GL11.GL_T2F_C3F_V3F:
                et = true; ec = true; en = false; st = 2; sc = 3; sv = 3; pc = 8; pv = 20; s = 32;
                break;
            case GL11.GL_T2F_N3F_V3F:
                et = true; ec = false; en = true; st = 2; sv = 3; pn = 8; pv = 20; s = 32;
                break;
            case GL11.GL_T2F_C4F_N3F_V3F:
                et = true; ec = true; en = true; st = 2; sc = 4; sv = 3; pc = 8; pn = 24; pv = 36; s = 48;
                break;
            case GL11.GL_T4F_C4F_N3F_V4F:
                et = true; ec = true; en = true; st = 4; sc = 4; sv = 4; pc = 16; pn = 32; pv = 44; s = 60;
                break;
            default:
                GLErrors.set(GL11.GL_INVALID_ENUM);
                return;
        }
        if (stride < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (stride == 0) {
            stride = s;
        }
        // glInterleavedArrays also disables the arrays this port ignores (index, edge flag, secondary color, fog coord)
        vao.ignoredEnableMask = 0;
        ArrayState tex = vao.tex[clientActiveTexture];
        tex.enabled = et;
        vao.color.enabled = ec;
        vao.normal.enabled = en;
        vao.vertex.enabled = true;
        if (et) {
            tex.set(st, GL11.GL_FLOAT, stride, pointer, buffer, ref, limit);
        }
        if (ec) {
            vao.color.set(sc, colorUbyte ? GL11.GL_UNSIGNED_BYTE : GL11.GL_FLOAT, stride, pointer + pc, buffer, ref, limit);
        }
        if (en) {
            vao.normal.set(3, GL11.GL_FLOAT, stride, pointer + pn, buffer, ref, limit);
        }
        vao.vertex.set(sv, GL11.GL_FLOAT, stride, pointer + pv, buffer, ref, limit);
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // generic vertex attributes (only bookkeeping: shader programs are not supported)
    // ------------------------------------------------------------------------------------------------------------------------

    public static void enableGenericAttrib(int index, boolean on) {
        if (index < 0 || index >= MAX_GENERIC_ATTRIBS) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (on) {
            vao.genericEnableMask |= 1 << index;
        } else {
            vao.genericEnableMask &= ~(1 << index);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // vertex array objects
    // ------------------------------------------------------------------------------------------------------------------------

    public static int genVertexArray() {
        int id = vaoNames.allocate(1);
        if (id == 0) {
            GLErrors.set(GL11.GL_OUT_OF_MEMORY);
        }
        return id;
    }

    public static void genVertexArrays(IntBuffer arrays) {
        int p = arrays.position();
        for (int i = 0, n = arrays.remaining(); i < n; i++) {
            arrays.put(p + i, genVertexArray());
        }
    }

    public static void bindVertexArray(int name) {
        if (name == 0) {
            vao = defaultVao;
            vaoName = 0;
            return;
        }
        if (name < 0 || name > (1 << 22)) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        VaoState v = name < vaos.length ? vaos[name] : null;
        if (v == null) {
            if (name >= vaos.length) {
                vaos = java.util.Arrays.copyOf(vaos, Math.max(name + 1, vaos.length * 2));
            }
            v = new VaoState();
            vaos[name] = v;
            vaoNames.reserve(name);
        }
        v.exists = true;
        vao = v;
        vaoName = name;
    }

    public static void deleteVertexArray(int name) {
        if (name <= 0 || name >= vaos.length || vaos[name] == null) {
            if (name > 0) {
                vaoNames.free(name, 1);
            }
            return;
        }
        if (vao == vaos[name]) {
            vao = defaultVao;
            vaoName = 0;
        }
        vaos[name] = null;
        vaoNames.free(name, 1);
    }

    public static boolean isVertexArray(int name) {
        return name > 0 && name < vaos.length && vaos[name] != null && vaos[name].exists;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // glPushClientAttrib / glPopClientAttrib
    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Saves the groups selected by {@code mask}: GL_CLIENT_VERTEX_ARRAY_BIT covers the vertex array state (pointers, enables, element
     * binding, client active texture and the array buffer binding); GL_CLIENT_PIXEL_STORE_BIT belongs to the texture module and is
     * not saved here.
     */
    public static void pushClientAttrib(int mask) {
        if (attribDepth >= MAX_ATTRIB_STACK) {
            GLErrors.set(GL11.GL_STACK_OVERFLOW);
            return;
        }
        int i = attribDepth++;
        attribStackMask[i] = mask;
        if ((mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0) {
            VaoState slot = attribStack[i];
            if (slot == null) {
                slot = new VaoState();
                attribStack[i] = slot;
            }
            slot.copyFrom(vao);
            attribStackActiveTexture[i] = clientActiveTexture;
            attribStackArrayBuffer[i] = BufferObjects.arrayBinding();
        }
    }

    public static void popClientAttrib() {
        if (attribDepth == 0) {
            GLErrors.set(GL11.GL_STACK_UNDERFLOW);
            return;
        }
        int i = --attribDepth;
        if ((attribStackMask[i] & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0) {
            vao.copyFrom(attribStack[i]);
            clientActiveTexture = attribStackActiveTexture[i];
            BufferObjects.restoreArrayBinding(attribStackArrayBuffer[i]);
        }
    }

    /** Back to the GL defaults (init / shutdown). */
    static void reset() {
        defaultVao.reset();
        vao = defaultVao;
        vaoName = 0;
        java.util.Arrays.fill(vaos, null);
        vaoNames.clear();
        clientActiveTexture = 0;
        attribDepth = 0;
        for (int i = 0; i < MAX_ATTRIB_STACK; i++) {
            attribStack[i] = null;
        }
        ignoredArrayWarned = false;
    }
}

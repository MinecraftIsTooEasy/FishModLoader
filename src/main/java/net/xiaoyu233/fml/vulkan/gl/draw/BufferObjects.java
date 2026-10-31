package net.xiaoyu233.fml.vulkan.gl.draw;

import net.xiaoyu233.fml.vulkan.gl.GLErrors;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * GL 1.5 / ARB_vertex_buffer_object emulation: buffer objects live in CPU memory (malloc'ed, native byte order = what the GPU reads on
 * every supported host). Array and element pointers refer to a buffer by <i>name</i> and offset and are resolved when a draw is issued
 * (see {@link ClientState}), so re-specifying the storage with glBufferData between the pointer call and the draw behaves like GL.
 * The vertex/index bytes are copied into the per-frame stream when a draw is recorded.
 * <p>
 * Storage that was exposed through glMapBuffer is different: the application may keep (and keep using) the ByteBuffer view, so freeing
 * the memory behind it would crash the JVM. Such a buffer's storage is a direct ByteBuffer owned by the garbage collector instead: the
 * views keep it alive, glBufferData with another size and glDeleteBuffers just drop the buffer object's reference, and the memory goes
 * when no view is left (bounded by the JVM's direct memory limit; a mapped buffer re-specified with the same size reuses its storage).
 * Render thread only.
 */
public final class BufferObjects {
    /** One buffer object. */
    static final class BufferObject {
        /** Native storage (0 when empty). */
        long address;
        /** The buffer's size as GL sees it (GL_BUFFER_SIZE); the storage behind {@link #address} is {@link #capacity} bytes. */
        int size;
        int capacity;
        int usage = GL15.GL_STATIC_DRAW;
        int access = GL15.GL_READ_WRITE;
        /** The name has been bound at least once (glIsBuffer). */
        boolean exists;
        boolean mapped;
        /** The buffer was mapped at least once: from then on its storage is {@link #managed} memory. */
        boolean everMapped;
        /** Garbage collected storage of a mapped buffer ({@link #address} points into it); null for malloc'ed storage. */
        ByteBuffer managed;
    }

    // target slots
    private static final int SLOT_ARRAY = 0;
    private static final int SLOT_ELEMENT = 1;
    private static final int SLOT_PIXEL_PACK = 2;
    private static final int SLOT_PIXEL_UNPACK = 3;
    private static final int SLOT_UNIFORM = 4;
    private static final int SLOT_TEXTURE = 5;
    private static final int SLOT_XFB = 6;
    private static final int SLOT_COPY_READ = 7;
    private static final int SLOT_COPY_WRITE = 8;
    private static final int SLOTS = 9;

    /** Names above this are rejected (the table is a plain array indexed by name; GL applications use generated, small names). */
    private static final int MAX_NAME = 1 << 22;

    private static final IdRanges names = new IdRanges();
    private static BufferObject[] table = new BufferObject[64];
    /** Per-target bindings (the element array binding lives in the current vertex array state of {@link ClientState}). */
    private static final int[] binding = new int[SLOTS];

    private BufferObjects() {
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // names
    // ------------------------------------------------------------------------------------------------------------------------

    public static int genBuffer() {
        int id = names.allocate(1);
        if (id == 0 || id > MAX_NAME) {
            if (id != 0) {
                names.free(id, 1);
            }
            GLErrors.set(GL11.GL_OUT_OF_MEMORY);
            return 0;
        }
        return id;
    }

    /** Fills {@code remaining()} names starting at the position; like LWJGL 2 the position is not advanced. */
    public static void genBuffers(IntBuffer buffers) {
        int p = buffers.position();
        for (int i = 0, n = buffers.remaining(); i < n; i++) {
            buffers.put(p + i, genBuffer());
        }
    }

    public static boolean isBuffer(int name) {
        BufferObject b = get(name);
        return b != null && b.exists;
    }

    public static void deleteBuffer(int name) {
        if (name <= 0 || name > MAX_NAME) {
            return;
        }
        BufferObject b = name < table.length ? table[name] : null;
        if (b != null) {
            release(b);
            table[name] = null;
        }
        for (int s = 0; s < SLOTS; s++) {
            if (s != SLOT_ELEMENT && binding[s] == name) {
                binding[s] = 0;
            }
        }
        ClientState.onBufferDeleted(name);
        names.free(name, 1);
    }

    public static void deleteBuffers(IntBuffer buffers) {
        for (int i = buffers.position(), end = buffers.limit(); i < end; i++) {
            deleteBuffer(buffers.get(i));
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // bindings
    // ------------------------------------------------------------------------------------------------------------------------

    public static void bind(int target, int name) {
        int slot = slot(target);
        if (slot < 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (name < 0 || name > MAX_NAME) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (name != 0) {
            obtain(name).exists = true;
        }
        if (slot == SLOT_ELEMENT) {
            ClientState.setElementArrayBufferBinding(name);
        } else {
            binding[slot] = name;
        }
    }

    /** GL_ARRAY_BUFFER_BINDING. */
    public static int arrayBinding() {
        return binding[SLOT_ARRAY];
    }

    /** glPopClientAttrib: restores the ARRAY_BUFFER binding without re-validating the name. */
    static void restoreArrayBinding(int name) {
        binding[SLOT_ARRAY] = name;
    }

    /** The binding of a buffer target enum (0 when unbound or not a buffer target). */
    static int boundName(int target) {
        int slot = slot(target);
        return slot < 0 ? 0 : boundSlot(slot);
    }

    /** True if {@code pname} is a GL_*_BUFFER_BINDING of a buffer target owned here; the value goes to {@code value[0]}. */
    static boolean getBinding(int pname, int[] value) {
        switch (pname) {
            case GL15.GL_ARRAY_BUFFER_BINDING:
                value[0] = binding[SLOT_ARRAY];
                return true;
            case GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING:
                value[0] = ClientState.getElementArrayBufferBinding();
                return true;
            case GL21.GL_PIXEL_PACK_BUFFER_BINDING:
                value[0] = binding[SLOT_PIXEL_PACK];
                return true;
            case GL21.GL_PIXEL_UNPACK_BUFFER_BINDING:
                value[0] = binding[SLOT_PIXEL_UNPACK];
                return true;
            case GL31.GL_COPY_READ_BUFFER_BINDING:
                value[0] = binding[SLOT_COPY_READ];
                return true;
            case GL31.GL_COPY_WRITE_BUFFER_BINDING:
                value[0] = binding[SLOT_COPY_WRITE];
                return true;
            default:
                return false;
        }
    }

    private static int boundSlot(int slot) {
        return slot == SLOT_ELEMENT ? ClientState.getElementArrayBufferBinding() : binding[slot];
    }

    private static int slot(int target) {
        switch (target) {
            case GL15.GL_ARRAY_BUFFER:
                return SLOT_ARRAY;
            case GL15.GL_ELEMENT_ARRAY_BUFFER:
                return SLOT_ELEMENT;
            case GL21.GL_PIXEL_PACK_BUFFER:
                return SLOT_PIXEL_PACK;
            case GL21.GL_PIXEL_UNPACK_BUFFER:
                return SLOT_PIXEL_UNPACK;
            case GL31.GL_UNIFORM_BUFFER:
                return SLOT_UNIFORM;
            case GL31.GL_TEXTURE_BUFFER:
                return SLOT_TEXTURE;
            case GL30.GL_TRANSFORM_FEEDBACK_BUFFER:
                return SLOT_XFB;
            case GL31.GL_COPY_READ_BUFFER:
                return SLOT_COPY_READ;
            case GL31.GL_COPY_WRITE_BUFFER:
                return SLOT_COPY_WRITE;
            default:
                return -1;
        }
    }

    /** The object of a name, or null when the name has no object (never bound / deleted / 0). */
    static BufferObject get(int name) {
        return name > 0 && name < table.length ? table[name] : null;
    }

    private static BufferObject obtain(int name) {
        if (name >= table.length) {
            table = java.util.Arrays.copyOf(table, Math.max(name + 1, table.length * 2));
        }
        BufferObject b = table[name];
        if (b == null) {
            b = new BufferObject();
            table[name] = b;
            names.reserve(name); // a name chosen by the application must not be handed out again by glGenBuffers
        }
        return b;
    }

    /** The object bound to a target, or null after raising GL_INVALID_OPERATION (nothing bound) / GL_INVALID_ENUM (bad target). */
    private static BufferObject boundObject(int target) {
        int slot = slot(target);
        if (slot < 0) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return null;
        }
        int name = boundSlot(slot);
        BufferObject b = name == 0 ? null : get(name);
        if (b == null) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // storage
    // ------------------------------------------------------------------------------------------------------------------------

    private static boolean validUsage(int usage) {
        switch (usage) {
            case GL15.GL_STREAM_DRAW:
            case GL15.GL_STREAM_READ:
            case GL15.GL_STREAM_COPY:
            case GL15.GL_STATIC_DRAW:
            case GL15.GL_STATIC_READ:
            case GL15.GL_STATIC_COPY:
            case GL15.GL_DYNAMIC_DRAW:
            case GL15.GL_DYNAMIC_READ:
            case GL15.GL_DYNAMIC_COPY:
                return true;
            default:
                return false;
        }
    }

    private static void release(BufferObject b) {
        if (b.managed != null) {
            b.managed = null; // mapped views keep the memory alive until the collector finds none left
        } else if (b.address != 0L) {
            MemoryUtil.nmemFree(b.address);
        }
        b.address = 0L;
        b.size = 0;
        b.capacity = 0;
        b.mapped = false;
    }

    /** New zero filled storage of {@code n} bytes for {@code b} (managed once the buffer was mapped); false when out of memory. */
    private static boolean allocate(BufferObject b, int n) {
        if (b.everMapped) {
            try {
                ByteBuffer m = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
                b.managed = m;
                b.address = MemoryUtil.memAddress0(m);
            } catch (OutOfMemoryError e) {
                return false;
            }
            b.capacity = n;
            return true;
        }
        long a = MemoryUtil.nmemAlloc(n);
        if (a == 0L) {
            return false;
        }
        b.address = a;
        b.capacity = n;
        return true;
    }

    /** glBufferData with the data at {@code src} (0 = no data, the new storage is zero filled). */
    private static void data(int target, long src, long bytes, int usage) {
        BufferObject b = boundObject(target);
        if (b == null) {
            return;
        }
        if (bytes < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (!validUsage(usage)) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (bytes > Integer.MAX_VALUE - 16) {
            GLErrors.set(GL11.GL_OUT_OF_MEMORY);
            return;
        }
        int n = (int) bytes;
        b.mapped = false; // re-specifying the storage implicitly unmaps
        if (n == 0) {
            release(b);
        } else if (b.address == 0L || n > b.capacity || n < (b.capacity >> 2)) {
            release(b);
            if (!allocate(b, n)) {
                GLErrors.set(GL11.GL_OUT_OF_MEMORY);
                return;
            }
            b.size = n;
        } else {
            // the storage is big enough and not wastefully so: a buffer that is re-specified with changing sizes every frame (orphaning)
            // keeps its allocation instead of producing a new one per call
            b.size = n;
        }
        if (n > 0) {
            if (src != 0L) {
                MemoryUtil.memCopy(src, b.address, n);
            } else {
                MemoryUtil.memSet(b.address, 0, n);
            }
        }
        b.usage = usage;
    }

    public static void data(int target, long size, int usage) {
        data(target, 0L, size, usage);
    }

    public static void data(int target, ByteBuffer d, int usage) {
        data(target, Mem.addr(d), d.remaining(), usage);
    }

    public static void data(int target, ShortBuffer d, int usage) {
        data(target, Mem.addr(d), (long) d.remaining() << 1, usage);
    }

    public static void data(int target, IntBuffer d, int usage) {
        data(target, Mem.addr(d), (long) d.remaining() << 2, usage);
    }

    public static void data(int target, FloatBuffer d, int usage) {
        data(target, Mem.addr(d), (long) d.remaining() << 2, usage);
    }

    public static void data(int target, DoubleBuffer d, int usage) {
        data(target, Mem.addr(d), (long) d.remaining() << 3, usage);
    }

    private static void subData(int target, long offset, long src, long bytes) {
        BufferObject b = boundObject(target);
        if (b == null) {
            return;
        }
        if (offset < 0 || bytes < 0 || offset + bytes > b.size) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (b.mapped) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (bytes > 0) {
            MemoryUtil.memCopy(src, b.address + offset, bytes);
        }
    }

    public static void subData(int target, long offset, ByteBuffer d) {
        subData(target, offset, Mem.addr(d), d.remaining());
    }

    public static void subData(int target, long offset, ShortBuffer d) {
        subData(target, offset, Mem.addr(d), (long) d.remaining() << 1);
    }

    public static void subData(int target, long offset, IntBuffer d) {
        subData(target, offset, Mem.addr(d), (long) d.remaining() << 2);
    }

    public static void subData(int target, long offset, FloatBuffer d) {
        subData(target, offset, Mem.addr(d), (long) d.remaining() << 2);
    }

    public static void subData(int target, long offset, DoubleBuffer d) {
        subData(target, offset, Mem.addr(d), (long) d.remaining() << 3);
    }

    private static void getSubData(int target, long offset, long dst, long bytes) {
        BufferObject b = boundObject(target);
        if (b == null) {
            return;
        }
        if (offset < 0 || bytes < 0 || offset + bytes > b.size) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (b.mapped) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (bytes > 0) {
            MemoryUtil.memCopy(b.address + offset, dst, bytes);
        }
    }

    public static void getSubData(int target, long offset, ByteBuffer d) {
        getSubData(target, offset, Mem.addr(d), d.remaining());
    }

    public static void getSubData(int target, long offset, ShortBuffer d) {
        getSubData(target, offset, Mem.addr(d), (long) d.remaining() << 1);
    }

    public static void getSubData(int target, long offset, IntBuffer d) {
        getSubData(target, offset, Mem.addr(d), (long) d.remaining() << 2);
    }

    public static void getSubData(int target, long offset, FloatBuffer d) {
        getSubData(target, offset, Mem.addr(d), (long) d.remaining() << 2);
    }

    public static void getSubData(int target, long offset, DoubleBuffer d) {
        getSubData(target, offset, Mem.addr(d), (long) d.remaining() << 3);
    }

    /**
     * glMapBuffer: a ByteBuffer view of the storage (native byte order). Like LWJGL 2, {@code old} is returned instead of a new view
     * when it already describes exactly the same memory; {@code length < 0} means the whole buffer. The first map moves the storage to
     * garbage collected memory (see the class comment), so the view can never dangle.
     */
    public static ByteBuffer map(int target, int access, long length, ByteBuffer old) {
        BufferObject b = boundObject(target);
        if (b == null) {
            return null;
        }
        if (access != GL15.GL_READ_ONLY && access != GL15.GL_WRITE_ONLY && access != GL15.GL_READ_WRITE) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return null;
        }
        if (b.mapped) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return null;
        }
        if (b.size == 0 || b.address == 0L) {
            return null;
        }
        if (b.managed == null) {
            b.everMapped = true;
            long malloced = b.address;
            int mallocedCapacity = b.capacity;
            if (!allocate(b, b.size)) { // a failed allocate() leaves the malloc'ed storage in place
                b.address = malloced;
                b.capacity = mallocedCapacity;
                GLErrors.set(GL11.GL_OUT_OF_MEMORY);
                return null;
            }
            MemoryUtil.memCopy(malloced, b.address, b.size);
            MemoryUtil.nmemFree(malloced);
        }
        int len = length >= 0 ? (int) Math.min(length, (long) b.size) : b.size;
        b.mapped = true;
        b.access = access;
        if (old != null && old.isDirect() && MemoryUtil.memAddress0(old) == b.address && old.capacity() == len) {
            return old;
        }
        return (len == b.capacity ? b.managed.duplicate() : b.managed.slice(0, len)).order(ByteOrder.nativeOrder());
    }

    public static boolean unmap(int target) {
        BufferObject b = boundObject(target);
        if (b == null) {
            return false;
        }
        if (!b.mapped) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return false;
        }
        b.mapped = false;
        return true;
    }

    public static int getParameter(int target, int pname) {
        BufferObject b = boundObject(target);
        if (b == null) {
            return 0;
        }
        switch (pname) {
            case GL15.GL_BUFFER_SIZE:
                return b.size;
            case GL15.GL_BUFFER_USAGE:
                return b.usage;
            case GL15.GL_BUFFER_ACCESS:
                return b.access;
            case GL15.GL_BUFFER_MAPPED:
                return b.mapped ? 1 : 0;
            default:
                GLErrors.set(GL11.GL_INVALID_ENUM);
                return 0;
        }
    }

    public static void copySubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        BufferObject src = boundObject(readTarget);
        BufferObject dst = src == null ? null : boundObject(writeTarget);
        if (src == null || dst == null) {
            return;
        }
        if (readOffset < 0 || writeOffset < 0 || size < 0 || readOffset + size > src.size || writeOffset + size > dst.size) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (src.mapped || dst.mapped) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (src == dst && readOffset < writeOffset + size && writeOffset < readOffset + size) {
            GLErrors.set(GL11.GL_INVALID_VALUE); // overlapping ranges of one buffer
            return;
        }
        if (size > 0) {
            MemoryUtil.memCopy(src.address + readOffset, dst.address + writeOffset, size);
        }
    }

    /** Frees all storage (shutdown). */
    static void shutdown() {
        for (int i = 0; i < table.length; i++) {
            BufferObject b = table[i];
            if (b != null) {
                release(b);
                table[i] = null;
            }
        }
        java.util.Arrays.fill(binding, 0);
        names.clear();
    }
}

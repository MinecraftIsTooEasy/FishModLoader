package net.xiaoyu233.fml.vulkan.gl.draw;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.core.StaticBuffer;
import net.xiaoyu233.fml.vulkan.gl.GLErrors;
import net.xiaoyu233.fml.vulkan.pipeline.VertexLayout;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Display list compilation and replay (module D2); the public methods {@link #isCompiling}, {@link #record}, {@link #compileAttrib}
 * and {@link #invalidateAttribs} are the contract of ../docs/ARCHITECTURE.md (outside the repo) section 5.10.
 * <p>
 * A list is an ordered array of ops. State changes are the {@code Runnable}s the owner facades record (replay runs them through the
 * state machine); geometry is captured when the draw / glBegin..glEnd is <i>compiled</i> (the client arrays and the immediate-mode
 * vertices are read at that moment, as GL does) into one CPU buffer per list, converted exactly like a live draw. The captured
 * geometry stays in a compact CPU copy until the list's first replay, when it is uploaded once with {@code RenderCore.createStatic}
 * (so lists that are never called, like the occlusion query boxes MITE compiles for every repositioned chunk renderer, cost no GPU
 * buffer). A geometry op replays by issuing the draw from that static buffer through the normal draw path (state, pipeline, push
 * constants, draw merging), in order with the state ops around it.
 * <p>
 * What the replay-time state decides, the replay decides: the shade model (the indexed triangle draws of a list carry the indices of
 * both provoking vertex conventions, quads pick their shared index buffer when they are drawn) and every attribute a glBegin..glEnd
 * batch does not set itself.
 * <p>
 * Immediate-mode vertices compiled into a list carry an attribute (color, normal, texcoords) only when the batch sets it between
 * glBegin and glEnd (see {@link #compileAttrib}), so that every vertex has its own value. An attribute the batch leaves alone is absent
 * from the captured vertex layout and comes from the current state when the list is replayed: the recorded attribute ops run before
 * the draw, so that value is exactly what GL would use, whatever ran in between (nested lists, glPopAttrib, ...), which is exact GL
 * behavior. Two rare shapes remain approximations, both for an attribute whose value before the batch is not known to the list (never
 * set in it, or {@link #invalidateAttribs invalidated}): if the batch sets it after some vertices, those vertices get the first value
 * (GL would use the replay-time current value); if it sets it only after the last glVertex, the draw sees the new value (GL: the old
 * one), because the batch's attribute ops replay before its draw. Render thread only.
 */
public final class DisplayLists {
    /** GL_MAX_LIST_NESTING: calls nested deeper than this are ignored. */
    public static final int MAX_LIST_NESTING = 64;

    private static final int PAGE_BITS = 12;
    private static final int PAGE_SIZE = 1 << PAGE_BITS;
    private static final int PAGE_MASK = PAGE_SIZE - 1;

    /** A compiled list. */
    private static final class ListData {
        Runnable[] ops;
        int opCount;
        /** Static vertex (+ index) storage of the captured geometry: null until the first replay uploaded it (or when the list has none). */
        StaticBuffer buffer;
        /** The captured geometry waiting for its first replay, {@code [vertices][pad to 16][indices]} in malloc'ed memory (0 = none). */
        long pending;
        int pendingSize;
        /** Byte offset of the index region inside the pending copy / the static buffer. */
        int indexStart;
        /** The draws of the list while the geometry is pending (patched with the buffer addresses on upload). */
        DrawItem[] items;

        /**
         * Uploads the pending geometry as one static buffer and points every draw of the list at it. False while the backend is not
         * running (the geometry stays pending), true when the draws are ready.
         */
        boolean upload() {
            if (pending == 0L) {
                return buffer != null;
            }
            if (!DrawSystem.isInitialized()) {
                return false;
            }
            StaticBuffer sb = RenderCore.createStatic(MemoryUtil.memByteBuffer(pending, pendingSize),
                    VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            MemoryUtil.nmemFree(pending);
            pending = 0L;
            pendingSize = 0;
            buffer = sb;
            long base = sb.offset();
            DrawItem[] list = items;
            items = null;
            for (DrawItem it : list) {
                it.vbuf = sb.buffer();
                it.voff = base + it.vByteOffset;
                if (it.kind == Primitives.K_INDEXED) {
                    it.ioff = base + indexStart + it.iByteOffset;
                    if (it.iByteOffsetFlat >= 0) {
                        it.ioffFlat = base + indexStart + it.iByteOffsetFlat;
                    }
                }
            }
            return true;
        }

        /** Releases the geometry (a pending copy or the static buffer); the list is no longer reachable by name. */
        void dispose() {
            if (pending != 0L) {
                MemoryUtil.nmemFree(pending);
                pending = 0L;
                pendingSize = 0;
            }
            items = null;
            if (buffer != null) {
                buffer.free();
                buffer = null;
            }
        }
    }

    /** One captured draw; it is its own replay op. */
    static final class DrawItem implements Runnable {
        VertexLayout layout;
        int kind;
        int topology;
        int vertexCount;
        int stride;
        int indexCount;
        /** Byte offsets inside the list's vertex region / index regions ({@code iByteOffsetFlat < 0}: one index list serves both shade models). */
        int vByteOffset;
        int iByteOffset;
        int iByteOffsetFlat = -1;
        /** The list that owns the geometry (uploaded on the first replay). */
        ListData list;
        /** Filled when the list is uploaded: VkBuffer and absolute byte offsets (0 buffer = not uploaded, nothing is drawn). */
        long vbuf;
        long voff;
        long ioff;
        /** Absolute offset of the index list for flat shading (0 = {@link #ioff} serves both). */
        long ioffFlat;

        /** Makes sure the geometry is on the GPU; false when it can not be drawn (backend not running, list deleted). */
        boolean ready() {
            return vbuf != 0L || (list != null && list.upload() && vbuf != 0L);
        }

        @Override
        public void run() {
            DrawSystem.issueStatic(this);
        }
    }

    /** Compile-time state, reused for every list (compilation never nests). */
    private static final class CompileState {
        int listId;
        int mode;
        boolean execute;
        Runnable[] ops = new Runnable[32];
        int opCount;
        final NativeBuf vbuf = new NativeBuf();
        final NativeBuf ibuf = new NativeBuf();
        final ArrayList<DrawItem> items = new ArrayList<>();
        DrawItem lastItem;
        int lastItemOpCount;

        // immediate-mode batch (glBegin .. glEnd) being compiled
        boolean inBatch;
        int batchMode;
        int batchCount;
        final NativeBuf batch = new NativeBuf();
        // current attribute values set inside the list (index = VertexLayout.ATTR_*): attrSet = the list set it and nothing that could
        // have changed it behind the list's back ran since (see invalidateAttribs), attr = that value
        final boolean[] attrSet = new boolean[VertexLayout.ATTR_COUNT];
        final float[][] attr = new float[VertexLayout.ATTR_COUNT][4];
        /** attrSet when the current batch began. */
        final boolean[] setAtBatchStart = new boolean[VertexLayout.ATTR_COUNT];
        /** Number of vertices the current batch had when the attribute was first set inside it, -1 = not set inside the batch. */
        final int[] firstVertex = new int[VertexLayout.ATTR_COUNT];

        void reset(int list, int mode) {
            listId = list;
            this.mode = mode;
            execute = mode == GL11.GL_COMPILE_AND_EXECUTE;
            Arrays.fill(ops, 0, opCount, null);
            opCount = 0;
            vbuf.clear();
            ibuf.clear();
            items.clear();
            lastItem = null;
            lastItemOpCount = -1;
            inBatch = false;
            batchCount = 0;
            batch.clear();
            Arrays.fill(attrSet, false);
            resetAttributeDefaults();
        }

        void resetAttributeDefaults() {
            set(attr[VertexLayout.ATTR_COLOR], 1f, 1f, 1f, 1f);
            set(attr[VertexLayout.ATTR_TEX0], 0f, 0f, 0f, 1f);
            set(attr[VertexLayout.ATTR_TEX1], 0f, 0f, 0f, 1f);
            set(attr[VertexLayout.ATTR_NORMAL], 0f, 0f, 1f, 1f);
        }

        void addOp(Runnable op) {
            if (opCount == ops.length) {
                ops = Arrays.copyOf(ops, opCount * 2);
            }
            ops[opCount++] = op;
        }
    }

    private static void set(float[] a, float x, float y, float z, float w) {
        a[0] = x;
        a[1] = y;
        a[2] = z;
        a[3] = w;
    }

    private static final CompileState cs = new CompileState();
    /** True while a glNewList..glEndList is open (a plain field: it is read by every GL call of every owner facade). */
    private static boolean compiling;

    private static ListData[][] pages = new ListData[16][];
    private static final IdRanges ids = new IdRanges();
    private static int listBase;
    private static int callDepth;

    /** Presence mask (bit = 1 << ATTR_*, positions excluded) to captured immediate layout. */
    private static final VertexLayout[] prunedLayouts = new VertexLayout[16];
    private static final NativeBuf indexScratch = new NativeBuf();

    private DisplayLists() {
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // contract (5.10)
    // ------------------------------------------------------------------------------------------------------------------------

    /** True between glNewList and glEndList. */
    public static boolean isCompiling() {
        return compiling;
    }

    /**
     * Records an op into the list being compiled; returns true if the caller must also execute it now (GL_COMPILE_AND_EXECUTE).
     * Returns false (and records nothing) when no list is being compiled.
     */
    public static boolean record(Runnable op) {
        if (!compiling) {
            return false;
        }
        cs.addOp(op);
        return cs.execute;
    }

    /**
     * D1 reports every compiled glColor, glNormal, glTexCoord and glMultiTexCoord call here ({@code attr} = VertexLayout.ATTR_COLOR,
     * ATTR_NORMAL, ATTR_TEX0 or ATTR_TEX1) in addition to recording it. The values set inside a glBegin..glEnd batch are baked into
     * the vertices of that batch (the values before the batch are what the list tracked).
     */
    public static void compileAttrib(int attr, float x, float y, float z, float w) {
        if (!compiling || attr <= VertexLayout.ATTR_POSITION || attr >= VertexLayout.ATTR_COUNT) {
            return;
        }
        CompileState c = cs;
        set(c.attr[attr], x, y, z, w);
        c.attrSet[attr] = true;
        if (c.inBatch && c.firstVertex[attr] < 0) {
            c.firstVertex[attr] = c.batchCount;
        }
    }

    /**
     * An op that was just recorded (or is about to be) can change the current color/normal/texcoords when the list is replayed without
     * reporting it through {@link #compileAttrib}: a nested glCallList/glCallLists (the called list may set attributes, and may even be
     * redefined later), glPopAttrib restoring GL_CURRENT_BIT. D2 calls it for the nested calls; D1 may call it from a compiled
     * glPopAttrib. Afterwards the attributes the list tracked count as not known. Geometry compiled later in the same list never
     * depends on them unless a batch sets the attribute itself after some vertices: an attribute the batch does not set comes from
     * the state at replay anyway (see the class comment). Does nothing when no list is being compiled.
     */
    public static void invalidateAttribs() {
        if (compiling) {
            Arrays.fill(cs.attrSet, false);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // queries used by the other D2 classes and the facade
    // ------------------------------------------------------------------------------------------------------------------------

    /** True when the list being compiled is GL_COMPILE_AND_EXECUTE (what is compiled must also run now). */
    static boolean executing() {
        return compiling && cs.execute;
    }

    /** The list under construction, 0 when none (GL_LIST_INDEX). */
    static int currentListName() {
        return compiling ? cs.listId : 0;
    }

    /** GL_COMPILE / GL_COMPILE_AND_EXECUTE while compiling, 0 otherwise (GL_LIST_MODE). */
    static int currentListMode() {
        return compiling ? cs.mode : 0;
    }

    static int listBase() {
        return listBase;
    }

    public static void setListBase(int base) {
        listBase = base;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // glNewList / glEndList
    // ------------------------------------------------------------------------------------------------------------------------

    public static void newList(int list, int mode) {
        if (list <= 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        if (mode != GL11.GL_COMPILE && mode != GL11.GL_COMPILE_AND_EXECUTE) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        if (compiling || DrawSystem.immediateActive()) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (!ids.isAllocated(list)) {
            ids.reserve(list); // glNewList on a name nobody generated is legal GL
        }
        cs.reset(list, mode);
        compiling = true;
    }

    public static void endList() {
        if (!compiling) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        CompileState c = cs;
        compiling = false;
        c.inBatch = false;
        ListData d = new ListData();
        d.ops = c.opCount == 0 ? NO_OPS : Arrays.copyOf(c.ops, c.opCount);
        d.opCount = c.opCount;
        try {
            if (!c.items.isEmpty()) {
                keepGeometry(d, c);
            }
        } finally {
            ListData old = replace(c.listId, d);
            if (old != null) {
                old.dispose();
            }
            // do not pin big scratch blocks
            trim(c.vbuf);
            trim(c.ibuf);
            trim(c.batch);
            trim(indexScratch);
            Arrays.fill(c.ops, 0, c.opCount, null);
            c.opCount = 0;
            c.items.clear();
            c.lastItem = null;
        }
    }

    private static final Runnable[] NO_OPS = new Runnable[0];
    private static final DrawItem[] NO_ITEMS = new DrawItem[0];

    private static void trim(NativeBuf b) {
        if (b.capacity > (32 << 20)) {
            b.free();
        }
    }

    /**
     * Keeps the captured geometry of the list as a compact CPU copy {@code [vertices][pad to 16][indices]} until the first replay of one
     * of its draws uploads it ({@link ListData#upload}); a list that is never called never gets a GPU buffer.
     */
    private static void keepGeometry(ListData d, CompileState c) {
        int vb = c.vbuf.size;
        int ib = c.ibuf.size;
        if (vb == 0) {
            return;
        }
        int indexStart = (vb + 15) & ~15;
        long total = ib == 0 ? vb : (long) indexStart + ib;
        if (total > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("display list geometry of " + total + " bytes");
        }
        long mem = MemoryUtil.nmemAlloc(total);
        if (mem == 0L) {
            throw new OutOfMemoryError("display list geometry of " + total + " bytes");
        }
        MemoryUtil.memCopy(c.vbuf.address, mem, vb);
        if (ib != 0) {
            if (indexStart > vb) {
                MemoryUtil.memSet(mem + vb, 0, indexStart - vb);
            }
            MemoryUtil.memCopy(c.ibuf.address, mem + indexStart, ib);
        }
        d.pending = mem;
        d.pendingSize = (int) total;
        d.indexStart = indexStart;
        DrawItem[] items = c.items.toArray(NO_ITEMS);
        for (DrawItem it : items) {
            it.list = d;
        }
        d.items = items;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // geometry capture (called by DrawSystem while a list is being compiled)
    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Appends one captured draw: the converted vertices ({@code vcount * stride} bytes, {@code vsrcBytes} of them carry data) and for
     * K_INDEXED the uint32 indices at {@code isrc} (smooth shading) and, when the generated indices depend on the shade model
     * ({@link Primitives#flatSensitive}), at {@code isrcFlat} (flat shading; 0 = {@code isrc} serves both): the replay picks the list for
     * the shade model current then. A K_DIRECT/K_QUADS draw directly following another one of the same layout and topology (no state
     * op in between, e.g. the 400 vertex groups of the Tessellator) extends that item instead of becoming a new one.
     */
    static void appendItem(VertexLayout layout, int stride, int kind, int topology, int vcount, long vsrc, int vsrcBytes,
                           long isrc, long isrcFlat, int icount) {
        CompileState c = cs;
        int vbytes = vcount * stride;
        DrawItem last = c.lastItem;
        if (kind != Primitives.K_INDEXED && last != null && c.lastItemOpCount == c.opCount && last.kind == kind
                && last.layout == layout && last.topology == topology) {
            copyVertices(c, vsrc, vsrcBytes, vbytes);
            last.vertexCount += vcount;
            return;
        }
        DrawItem it = new DrawItem();
        it.layout = layout;
        it.stride = stride;
        it.kind = kind;
        it.topology = topology;
        it.vertexCount = vcount;
        it.vByteOffset = c.vbuf.size;
        copyVertices(c, vsrc, vsrcBytes, vbytes);
        if (kind == Primitives.K_INDEXED) {
            it.indexCount = icount;
            it.iByteOffset = c.ibuf.size;
            MemoryUtil.memCopy(isrc, c.ibuf.append(icount * 4), (long) icount * 4);
            if (isrcFlat != 0L) {
                it.iByteOffsetFlat = c.ibuf.size;
                MemoryUtil.memCopy(isrcFlat, c.ibuf.append(icount * 4), (long) icount * 4);
            }
        }
        c.addOp(it);
        c.items.add(it);
        c.lastItem = it;
        c.lastItemOpCount = c.opCount;
    }

    private static void copyVertices(CompileState c, long vsrc, int vsrcBytes, int vbytes) {
        long dst = c.vbuf.append(vbytes);
        MemoryUtil.memCopy(vsrc, dst, vsrcBytes);
        if (vbytes > vsrcBytes) {
            MemoryUtil.memSet(dst + vsrcBytes, 0, vbytes - vsrcBytes);
        }
    }

    /** True while a glBegin..glEnd batch is being compiled. */
    static boolean batchActive() {
        return compiling && cs.inBatch;
    }

    static void captureBegin(int mode) {
        CompileState c = cs;
        if (c.inBatch) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        if (!Primitives.isMode(mode)) {
            GLErrors.set(GL11.GL_INVALID_ENUM);
            return;
        }
        c.inBatch = true;
        c.batchMode = mode;
        c.batchCount = 0;
        c.batch.clear();
        for (int a = VertexLayout.ATTR_COLOR; a < VertexLayout.ATTR_COUNT; a++) {
            c.setAtBatchStart[a] = c.attrSet[a];
            c.firstVertex[a] = -1;
        }
    }

    static void captureVertex(float x, float y, float z, float w) {
        CompileState c = cs;
        if (!c.inBatch) {
            return;
        }
        long p = c.batch.append(DrawSystem.IMMEDIATE_STRIDE);
        DrawSystem.putVertex(p, x, y, z, w, c.attr[VertexLayout.ATTR_COLOR], c.attr[VertexLayout.ATTR_NORMAL],
                c.attr[VertexLayout.ATTR_TEX0], c.attr[VertexLayout.ATTR_TEX1]);
        c.batchCount++;
    }

    static void captureEnd() {
        CompileState c = cs;
        if (!c.inBatch) {
            GLErrors.set(GL11.GL_INVALID_OPERATION);
            return;
        }
        c.inBatch = false;
        int n = c.batchCount;
        if (n == 0) {
            return;
        }
        // the shade model of the replay decides, not the current one: smooth triangles are classified as indexed too (they need a
        // rotated index list for flat shading), see appendItem
        if (!Primitives.classifyArrays(c.batchMode, n, true)) {
            return;
        }
        int kind = Primitives.kind;
        int topology = Primitives.topology;
        int v = Primitives.vertices;
        int ic = Primitives.indexCount;
        long base = c.batch.address;

        // The captured vertices carry an attribute only when the batch itself sets it (glColor.. between glBegin and glEnd): then every
        // vertex has the value GL gave it. An attribute the batch leaves alone is not baked: its value is the one current when the batch
        // starts, which is whatever the ops before it (and nested calls, attribute stack restores...) made it at replay, and the
        // replayed ops precede the draw, so the push constants hold exactly that value.
        int mask = 0;
        for (int a = VertexLayout.ATTR_COLOR; a < VertexLayout.ATTR_COUNT; a++) {
            int fv = c.firstVertex[a];
            if (fv < 0 || !c.attrSet[a]) {
                continue;
            }
            if (c.setAtBatchStart[a]) {
                mask |= 1 << a; // the value before the first set is known (tracked by the list): vertices [0, fv) already have it
            } else if (fv < n) {
                mask |= 1 << a;
                // the value before the first set is not known: vertices emitted before it get the first value (GL would use the value
                // current at replay time). A first set after the last vertex leaves the attribute to the replay-time state.
                for (int i = 0; i < fv; i++) {
                    DrawSystem.copyAttribute(a, base + (long) fv * DrawSystem.IMMEDIATE_STRIDE, base + (long) i * DrawSystem.IMMEDIATE_STRIDE);
                }
            }
        }
        VertexLayout layout = prunedLayout(mask);
        long isrc = 0L;
        long isrcFlat = 0L;
        if (kind == Primitives.K_INDEXED) {
            boolean both = Primitives.flatSensitive(c.batchMode);
            long bytes = (long) ic * 4;
            if (bytes * (both ? 2 : 1) > Integer.MAX_VALUE) {
                GLErrors.set(GL11.GL_OUT_OF_MEMORY);
                return;
            }
            isrc = indexScratch.ensure((int) bytes * (both ? 2 : 1));
            Primitives.writeArrayIndices(c.batchMode, v, false, 0, isrc);
            if (both) {
                isrcFlat = isrc + bytes;
                Primitives.writeArrayIndices(c.batchMode, v, true, 0, isrcFlat);
            }
        }
        appendItem(layout, DrawSystem.IMMEDIATE_STRIDE, kind, topology, v, base, v * DrawSystem.IMMEDIATE_STRIDE, isrc, isrcFlat, ic);
    }

    private static VertexLayout prunedLayout(int mask) {
        int idx = (mask >> 1) & 15;
        VertexLayout l = prunedLayouts[idx];
        if (l == null) {
            VertexLayout imm = VertexLayout.IMMEDIATE;
            int[] fmt = new int[VertexLayout.ATTR_COUNT];
            int[] bind = new int[VertexLayout.ATTR_COUNT];
            int[] off = new int[VertexLayout.ATTR_COUNT];
            int[] str = new int[VertexLayout.MAX_BINDINGS];
            for (int a = 0; a < VertexLayout.ATTR_COUNT; a++) {
                if (a == VertexLayout.ATTR_POSITION || (mask & (1 << a)) != 0) {
                    fmt[a] = imm.format(a);
                    off[a] = imm.offset(a);
                }
            }
            str[0] = imm.bindingStride(0);
            l = VertexLayout.of(fmt, bind, off, str);
            prunedLayouts[idx] = l;
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // glCallList / glCallLists
    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * Executes a list (nothing happens for a name without a list). Never records: when called while compiling, the facade has
     * already recorded the call. Calls nested deeper than {@link #MAX_LIST_NESTING} are ignored.
     */
    public static void callList(int id) {
        ListData d = lookup(id);
        if (d == null || callDepth >= MAX_LIST_NESTING) {
            return;
        }
        if (DrawSystem.tracing()) {
            net.xiaoyu233.fml.vulkan.VkLog.LOGGER.info("[trace f{}] callList {} ops={} depth={}", RenderCore.frameNumber(), id, d.opCount,
                    callDepth);
        }
        callDepth++;
        try {
            Runnable[] ops = d.ops;
            for (int i = 0, n = d.opCount; i < n; i++) {
                ops[i].run();
            }
        } finally {
            callDepth--;
        }
    }

    /** glCallLists(ByteBuffer): GL_UNSIGNED_BYTE names from position to limit, offset by the list base; the position is not changed. */
    public static void callLists(ByteBuffer lists) {
        int base = listBase;
        for (int i = lists.position(), end = lists.limit(); i < end; i++) {
            callList(base + (lists.get(i) & 0xFF));
        }
    }

    /** glCallLists(ShortBuffer): GL_UNSIGNED_SHORT names. */
    public static void callLists(ShortBuffer lists) {
        int base = listBase;
        for (int i = lists.position(), end = lists.limit(); i < end; i++) {
            callList(base + (lists.get(i) & 0xFFFF));
        }
    }

    /** glCallLists(IntBuffer): GL_UNSIGNED_INT names. */
    public static void callLists(IntBuffer lists) {
        int base = listBase;
        for (int i = lists.position(), end = lists.limit(); i < end; i++) {
            callList(base + lists.get(i));
        }
    }

    /** Replay of a compiled glCallLists: the names were copied (already widened) when the call was compiled, the list base is the current one. */
    public static void callLists(int[] names) {
        int base = listBase;
        for (int i = 0; i < names.length; i++) {
            callList(base + names[i]);
        }
    }

    public static int[] copyNames(ByteBuffer lists) {
        int[] r = new int[lists.remaining()];
        for (int i = 0, p = lists.position(); i < r.length; i++) {
            r[i] = lists.get(p + i) & 0xFF;
        }
        return r;
    }

    public static int[] copyNames(ShortBuffer lists) {
        int[] r = new int[lists.remaining()];
        for (int i = 0, p = lists.position(); i < r.length; i++) {
            r[i] = lists.get(p + i) & 0xFFFF;
        }
        return r;
    }

    public static int[] copyNames(IntBuffer lists) {
        int[] r = new int[lists.remaining()];
        for (int i = 0, p = lists.position(); i < r.length; i++) {
            r[i] = lists.get(p + i);
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // names
    // ------------------------------------------------------------------------------------------------------------------------

    /** glGenLists: the first of {@code range} consecutive new (empty) lists; 0 for a range of 0 or on failure (GL error set). */
    public static int genLists(int range) {
        if (range < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return 0;
        }
        if (range == 0) {
            return 0;
        }
        int first = ids.allocate(range);
        if (first == 0) {
            GLErrors.set(GL11.GL_OUT_OF_MEMORY);
        }
        return first;
    }

    public static void deleteLists(int list, int range) {
        if (range < 0) {
            GLErrors.set(GL11.GL_INVALID_VALUE);
            return;
        }
        long lo = Math.max((long) list, 1L); // names <= 0 do not exist
        long hi = Math.min((long) list + range, (long) Integer.MAX_VALUE);
        if (lo >= hi) {
            return;
        }
        for (long id = lo; id < hi; ) {
            int pageIndex = (int) (id >> PAGE_BITS);
            ListData[] page = pageIndex < pages.length ? pages[pageIndex] : null;
            long pageEnd = Math.min(hi, ((long) pageIndex + 1) << PAGE_BITS);
            if (page != null) {
                for (long i = id; i < pageEnd; i++) {
                    int slot = (int) (i & PAGE_MASK);
                    ListData d = page[slot];
                    if (d != null) {
                        page[slot] = null;
                        d.dispose();
                    }
                }
            }
            id = pageEnd;
        }
        ids.free((int) lo, (int) (hi - lo));
    }

    /** True for a name that was returned by glGenLists (empty list) or compiled with glNewList. */
    public static boolean isList(int id) {
        return id > 0 && (lookup(id) != null || ids.isAllocated(id));
    }

    private static ListData lookup(int id) {
        if (id <= 0) {
            return null;
        }
        int pageIndex = id >> PAGE_BITS;
        if (pageIndex >= pages.length) {
            return null;
        }
        ListData[] page = pages[pageIndex];
        return page == null ? null : page[id & PAGE_MASK];
    }

    private static ListData replace(int id, ListData d) {
        int pageIndex = id >> PAGE_BITS;
        if (pageIndex >= pages.length) {
            pages = Arrays.copyOf(pages, Math.max(pageIndex + 1, pages.length * 2));
        }
        ListData[] page = pages[pageIndex];
        if (page == null) {
            page = new ListData[PAGE_SIZE];
            pages[pageIndex] = page;
        }
        ListData old = page[id & PAGE_MASK];
        page[id & PAGE_MASK] = d;
        return old;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // shutdown
    // ------------------------------------------------------------------------------------------------------------------------

    /** Frees every list's static buffer and all scratch memory; the list table starts empty again. */
    static void shutdown() {
        compiling = false;
        for (int p = 0; p < pages.length; p++) {
            ListData[] page = pages[p];
            if (page == null) {
                continue;
            }
            for (int i = 0; i < PAGE_SIZE; i++) {
                ListData d = page[i];
                if (d != null) {
                    d.dispose();
                }
            }
            pages[p] = null;
        }
        ids.clear();
        listBase = 0;
        callDepth = 0;
        cs.vbuf.free();
        cs.ibuf.free();
        cs.batch.free();
        cs.items.clear();
        Arrays.fill(cs.ops, 0, cs.opCount, null);
        cs.opCount = 0;
        cs.lastItem = null;
        cs.inBatch = false;
        indexScratch.free();
        Arrays.fill(prunedLayouts, null);
    }
}

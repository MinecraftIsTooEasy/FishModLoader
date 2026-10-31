package net.xiaoyu233.fml.vulkan.gl;

import net.xiaoyu233.fml.vulkan.render.legacy.LegacyInterop;
import net.xiaoyu233.fml.vulkan.gl.draw.BufferObjects;
import net.xiaoyu233.fml.vulkan.gl.draw.ClientState;
import net.xiaoyu233.fml.vulkan.gl.draw.DisplayLists;
import net.xiaoyu233.fml.vulkan.gl.draw.DrawSystem;
import net.xiaoyu233.fml.vulkan.gl.draw.Queries;
import net.xiaoyu233.fml.vulkan.gl.state.GLState;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

/**
 * GL entry points owned by module D2 (see ../docs/ARCHITECTURE.md, outside the repo): immediate mode, client arrays, draws, display lists, buffer
 * objects, queries, vertex arrays and the client attribute stack. The LWJGL 2 compat classes in org.lwjgl.opengl delegate here with the
 * exact LWJGL 2 descriptors (vendor suffixes stripped). Parameter names follow LWJGL 2.9.0 (checked with javap): the pointer functions
 * of the form {@code (size, stride, XBuffer)} take the component type from the buffer class, {@code glNormalPointer(stride, buffer)}
 * has no size, and {@code glColorPointer(size, boolean unsigned, ...)} selects GL_UNSIGNED_BYTE / GL_BYTE.
 *
 * <h2>Display-list rule</h2>
 * <ul>
 * <li><b>Never compiled</b> (executed immediately even while a list is compiled, like GL): glGen*, glDelete*, glIs*, glGet*,
 * glNewList/glEndList, the client-state functions (gl*Pointer, glEnable/DisableClientState, glClientActiveTexture,
 * glInterleavedArrays, glPush/PopClientAttrib), all buffer-object, query and vertex-array functions.</li>
 * <li><b>Geometry</b> (glBegin, glVertex*, glEnd, glDrawArrays, glDrawElements, glDrawRangeElements, glMultiDrawArrays, glArrayElement): compiling
 * captures the geometry (arrays and immediate vertices are read at that moment) into the list, see {@link DisplayLists}; it is also
 * executed for GL_COMPILE_AND_EXECUTE.</li>
 * <li><b>Everything else</b> (glRect*, glCallList(s), glListBase): recorded as an op that calls the internal implementation, which
 * never records again; executed too for GL_COMPILE_AND_EXECUTE. A compiled glCallList(s) also tells {@link DisplayLists} that the
 * called list may change the current attributes ({@link DisplayLists#invalidateAttribs()}).</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class GLDrawImpl {
    private GLDrawImpl() {
    }

    // ==================== IMMEDIATE MODE ====================

    public static void glBegin(int mode) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.beginImmediate(mode);
    }

    public static void glEnd() {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.endImmediate();
    }

    public static void glVertex2f(float x, float y) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.vertex(x, y, 0f, 1f);
    }

    public static void glVertex2d(double x, double y) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.vertex((float) x, (float) y, 0f, 1f);
    }

    public static void glVertex2i(int x, int y) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.vertex(x, y, 0f, 1f);
    }

    public static void glVertex3f(float x, float y, float z) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.vertex(x, y, z, 1f);
    }

    public static void glVertex3d(double x, double y, double z) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.vertex((float) x, (float) y, (float) z, 1f);
    }

    public static void glVertex3i(int x, int y, int z) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.vertex(x, y, z, 1f);
    }

    public static void glVertex4f(float x, float y, float z, float w) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.vertex(x, y, z, w);
    }

    public static void glVertex4d(double x, double y, double z, double w) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.vertex((float) x, (float) y, (float) z, (float) w);
    }

    public static void glVertex4i(int x, int y, int z, int w) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.vertex(x, y, z, w);
    }

    public static void glRectf(float x1, float y1, float x2, float y2) {
        if (!GLState.checkRenderThread()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> DrawSystem.rect(x1, y1, x2, y2))) return;
        // executing now: pending native batches and unpulled native state go first, as for glBegin
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.rect(x1, y1, x2, y2);
    }

    public static void glRecti(int x1, int y1, int x2, int y2) {
        if (!GLState.checkRenderThread()) return;
        glRectf(x1, y1, x2, y2);
    }

    public static void glRectd(double x1, double y1, double x2, double y2) {
        if (!GLState.checkRenderThread()) return;
        glRectf((float) x1, (float) y1, (float) x2, (float) y2);
    }

    // ==================== DISPLAY LISTS ====================

    public static void glNewList(int list, int mode) {
        if (!GLState.checkRenderThread()) return;
        DisplayLists.newList(list, mode);
    }

    public static void glEndList() {
        if (!GLState.checkRenderThread()) return;
        DisplayLists.endList();
    }

    public static void glCallList(int list) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        if (DisplayLists.isCompiling()) {
            // the called list may set attributes when it replays: what this list set before is no longer known afterwards
            DisplayLists.invalidateAttribs();
            if (!DisplayLists.record(() -> DisplayLists.callList(list))) return;
        }
        DisplayLists.callList(list);
    }

    public static void glCallLists(ByteBuffer lists) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        if (DisplayLists.isCompiling()) {
            // the names are taken when the call is compiled, the caller reuses its buffer (RenderList clears and refills it)
            int[] names = DisplayLists.copyNames(lists);
            DisplayLists.invalidateAttribs();
            if (!DisplayLists.record(() -> DisplayLists.callLists(names))) return;
            DisplayLists.callLists(names);
            return;
        }
        DisplayLists.callLists(lists);
    }

    public static void glCallLists(ShortBuffer lists) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        if (DisplayLists.isCompiling()) {
            int[] names = DisplayLists.copyNames(lists);
            DisplayLists.invalidateAttribs();
            if (!DisplayLists.record(() -> DisplayLists.callLists(names))) return;
            DisplayLists.callLists(names);
            return;
        }
        DisplayLists.callLists(lists);
    }

    public static void glCallLists(IntBuffer lists) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        if (DisplayLists.isCompiling()) {
            int[] names = DisplayLists.copyNames(lists);
            DisplayLists.invalidateAttribs();
            if (!DisplayLists.record(() -> DisplayLists.callLists(names))) return;
            DisplayLists.callLists(names);
            return;
        }
        DisplayLists.callLists(lists);
    }

    public static int glGenLists(int range) {
        if (!GLState.checkRenderThread()) return 0;
        return DisplayLists.genLists(range);
    }

    public static void glDeleteLists(int list, int range) {
        if (!GLState.checkRenderThread()) return;
        DisplayLists.deleteLists(list, range);
    }

    public static boolean glIsList(int list) {
        if (!GLState.checkRenderThread()) return false;
        return DisplayLists.isList(list);
    }

    public static void glListBase(int base) {
        if (!GLState.checkRenderThread()) return;
        if (DisplayLists.isCompiling() && !DisplayLists.record(() -> DisplayLists.setListBase(base))) return;
        DisplayLists.setListBase(base);
    }

    // ==================== CLIENT ARRAYS (never compiled into display lists) ====================

    public static void glVertexPointer(int size, int type, int stride, ByteBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_VERTEX, size, type, stride, pointer);
    }

    public static void glVertexPointer(int size, int type, int stride, long pointer_buffer_offset) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointerVbo(ClientState.ARRAY_VERTEX, size, type, stride, pointer_buffer_offset);
    }

    public static void glVertexPointer(int size, int stride, DoubleBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_VERTEX, size, GL11.GL_DOUBLE, stride, pointer);
    }

    public static void glVertexPointer(int size, int stride, FloatBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_VERTEX, size, GL11.GL_FLOAT, stride, pointer);
    }

    public static void glVertexPointer(int size, int stride, IntBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_VERTEX, size, GL11.GL_INT, stride, pointer);
    }

    public static void glVertexPointer(int size, int stride, ShortBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_VERTEX, size, GL11.GL_SHORT, stride, pointer);
    }

    public static void glColorPointer(int size, int type, int stride, ByteBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_COLOR, size, type, stride, pointer);
    }

    public static void glColorPointer(int size, int type, int stride, long pointer_buffer_offset) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointerVbo(ClientState.ARRAY_COLOR, size, type, stride, pointer_buffer_offset);
    }

    public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_COLOR, size, unsigned ? GL11.GL_UNSIGNED_BYTE : GL11.GL_BYTE, stride, pointer);
    }

    public static void glColorPointer(int size, int stride, DoubleBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_COLOR, size, GL11.GL_DOUBLE, stride, pointer);
    }

    public static void glColorPointer(int size, int stride, FloatBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_COLOR, size, GL11.GL_FLOAT, stride, pointer);
    }

    public static void glNormalPointer(int type, int stride, ByteBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_NORMAL, 3, type, stride, pointer);
    }

    public static void glNormalPointer(int type, int stride, long pointer_buffer_offset) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointerVbo(ClientState.ARRAY_NORMAL, 3, type, stride, pointer_buffer_offset);
    }

    public static void glNormalPointer(int stride, ByteBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_NORMAL, 3, GL11.GL_BYTE, stride, pointer);
    }

    public static void glNormalPointer(int stride, DoubleBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_NORMAL, 3, GL11.GL_DOUBLE, stride, pointer);
    }

    public static void glNormalPointer(int stride, FloatBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_NORMAL, 3, GL11.GL_FLOAT, stride, pointer);
    }

    public static void glNormalPointer(int stride, IntBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_NORMAL, 3, GL11.GL_INT, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int type, int stride, ByteBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_TEXCOORD, size, type, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int type, int stride, long pointer_buffer_offset) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointerVbo(ClientState.ARRAY_TEXCOORD, size, type, stride, pointer_buffer_offset);
    }

    public static void glTexCoordPointer(int size, int stride, DoubleBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_TEXCOORD, size, GL11.GL_DOUBLE, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int stride, FloatBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_TEXCOORD, size, GL11.GL_FLOAT, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int stride, IntBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_TEXCOORD, size, GL11.GL_INT, stride, pointer);
    }

    public static void glTexCoordPointer(int size, int stride, ShortBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pointer(ClientState.ARRAY_TEXCOORD, size, GL11.GL_SHORT, stride, pointer);
    }

    public static void glClientActiveTexture(int texture) {
        if (!GLState.checkRenderThread()) return;
        ClientState.setClientActiveTexture(texture);
    }

    public static void glEnableClientState(int array) {
        if (!GLState.checkRenderThread()) return;
        ClientState.enableClientState(array);
    }

    public static void glDisableClientState(int array) {
        if (!GLState.checkRenderThread()) return;
        ClientState.disableClientState(array);
    }

    public static void glInterleavedArrays(int format, int stride, ByteBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.interleavedArrays(format, stride, pointer);
    }

    public static void glInterleavedArrays(int format, int stride, long pointer_buffer_offset) {
        if (!GLState.checkRenderThread()) return;
        ClientState.interleavedArraysVbo(format, stride, pointer_buffer_offset);
    }

    public static void glInterleavedArrays(int format, int stride, DoubleBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.interleavedArrays(format, stride, pointer);
    }

    public static void glInterleavedArrays(int format, int stride, FloatBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.interleavedArrays(format, stride, pointer);
    }

    public static void glInterleavedArrays(int format, int stride, IntBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.interleavedArrays(format, stride, pointer);
    }

    public static void glInterleavedArrays(int format, int stride, ShortBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        ClientState.interleavedArrays(format, stride, pointer);
    }

    public static void glArrayElement(int i) {
        if (!GLState.checkRenderThread()) return;
        DrawSystem.arrayElement(i);
    }

    // ==================== DRAWS (compiled as captured geometry, see DisplayLists) ====================

    public static void glDrawArrays(int mode, int first, int count) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.drawArrays(mode, first, count);
    }

    public static void glDrawElements(int mode, ByteBuffer indices) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.drawElements(mode, indices);
    }

    public static void glDrawElements(int mode, ShortBuffer indices) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.drawElements(mode, indices);
    }

    public static void glDrawElements(int mode, IntBuffer indices) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.drawElements(mode, indices);
    }

    public static void glDrawElements(int mode, int count, int type, long indices_buffer_offset) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.drawElementsVbo(mode, count, type, indices_buffer_offset);
    }

    public static void glDrawRangeElements(int mode, int start, int end, ByteBuffer indices) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.drawRangeElements(mode, start, end, indices);
    }

    public static void glDrawRangeElements(int mode, int start, int end, ShortBuffer indices) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.drawRangeElements(mode, start, end, indices);
    }

    public static void glDrawRangeElements(int mode, int start, int end, IntBuffer indices) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.drawRangeElements(mode, start, end, indices);
    }

    public static void glDrawRangeElements(int mode, int start, int end, int count, int type, long indices_buffer_offset) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.drawRangeElementsVbo(mode, start, end, count, type, indices_buffer_offset);
    }

    public static void glMultiDrawArrays(int mode, IntBuffer first, IntBuffer drawcount) {
        if (!GLState.checkRenderThread()) return;
        LegacyInterop.beforeEmulatedDraw();
        DrawSystem.multiDrawArrays(mode, first, drawcount);
    }

    // ==================== BUFFER OBJECTS (GL15 / ARB_vertex_buffer_object, never compiled) ====================

    public static void glBindBuffer(int target, int buffer) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.bind(target, buffer);
    }

    public static int glGenBuffers() {
        if (!GLState.checkRenderThread()) return 0;
        return BufferObjects.genBuffer();
    }

    public static void glGenBuffers(IntBuffer buffers) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.genBuffers(buffers);
    }

    public static void glDeleteBuffers(int buffer) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.deleteBuffer(buffer);
    }

    public static void glDeleteBuffers(IntBuffer buffers) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.deleteBuffers(buffers);
    }

    public static boolean glIsBuffer(int buffer) {
        if (!GLState.checkRenderThread()) return false;
        return BufferObjects.isBuffer(buffer);
    }

    public static void glBufferData(int target, long size, int usage) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.data(target, size, usage);
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.data(target, data, usage);
    }

    public static void glBufferData(int target, FloatBuffer data, int usage) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.data(target, data, usage);
    }

    public static void glBufferData(int target, IntBuffer data, int usage) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.data(target, data, usage);
    }

    public static void glBufferData(int target, ShortBuffer data, int usage) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.data(target, data, usage);
    }

    public static void glBufferData(int target, DoubleBuffer data, int usage) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.data(target, data, usage);
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.subData(target, offset, data);
    }

    public static void glBufferSubData(int target, long offset, FloatBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.subData(target, offset, data);
    }

    public static void glBufferSubData(int target, long offset, IntBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.subData(target, offset, data);
    }

    public static void glBufferSubData(int target, long offset, ShortBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.subData(target, offset, data);
    }

    public static void glBufferSubData(int target, long offset, DoubleBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.subData(target, offset, data);
    }

    public static void glGetBufferSubData(int target, long offset, ByteBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.getSubData(target, offset, data);
    }

    public static void glGetBufferSubData(int target, long offset, FloatBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.getSubData(target, offset, data);
    }

    public static void glGetBufferSubData(int target, long offset, IntBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.getSubData(target, offset, data);
    }

    public static void glGetBufferSubData(int target, long offset, ShortBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.getSubData(target, offset, data);
    }

    public static void glGetBufferSubData(int target, long offset, DoubleBuffer data) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.getSubData(target, offset, data);
    }

    public static ByteBuffer glMapBuffer(int target, int access, ByteBuffer old_buffer) {
        if (!GLState.checkRenderThread()) return null;
        return BufferObjects.map(target, access, -1L, old_buffer);
    }

    public static ByteBuffer glMapBuffer(int target, int access, long size, ByteBuffer old_buffer) {
        if (!GLState.checkRenderThread()) return null;
        return BufferObjects.map(target, access, size, old_buffer);
    }

    public static boolean glUnmapBuffer(int target) {
        if (!GLState.checkRenderThread()) return false;
        return BufferObjects.unmap(target);
    }

    public static int glGetBufferParameter(int target, int pname) {
        if (!GLState.checkRenderThread()) return 0;
        return BufferObjects.getParameter(target, pname);
    }

    public static int glGetBufferParameteri(int target, int pname) {
        if (!GLState.checkRenderThread()) return 0;
        return BufferObjects.getParameter(target, pname);
    }

    public static void glGetBufferParameter(int target, int pname, IntBuffer params) {
        if (!GLState.checkRenderThread()) return;
        if (params.hasRemaining()) {
            params.put(params.position(), BufferObjects.getParameter(target, pname));
        }
    }

    public static void glGetBufferParameter(int target, int pname, LongBuffer params) {
        if (!GLState.checkRenderThread()) return;
        if (params.hasRemaining()) {
            params.put(params.position(), BufferObjects.getParameter(target, pname));
        }
    }

    public static void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        if (!GLState.checkRenderThread()) return;
        BufferObjects.copySubData(readTarget, writeTarget, readOffset, writeOffset, size);
    }

    // ==================== QUERIES (accepted; every result is "available" and "visible", never compiled) ====================

    public static int glGenQueries() {
        if (!GLState.checkRenderThread()) return 0;
        return Queries.genQuery();
    }

    public static void glGenQueries(IntBuffer ids) {
        if (!GLState.checkRenderThread()) return;
        Queries.genQueries(ids);
    }

    public static void glDeleteQueries(int id) {
        if (!GLState.checkRenderThread()) return;
        Queries.deleteQuery(id);
    }

    public static void glDeleteQueries(IntBuffer ids) {
        if (!GLState.checkRenderThread()) return;
        Queries.deleteQueries(ids);
    }

    public static boolean glIsQuery(int id) {
        if (!GLState.checkRenderThread()) return false;
        return Queries.isQuery(id);
    }

    public static void glBeginQuery(int target, int id) {
        if (!GLState.checkRenderThread()) return;
        Queries.begin(target, id);
    }

    public static void glEndQuery(int target) {
        if (!GLState.checkRenderThread()) return;
        Queries.end(target);
    }

    public static int glGetQuery(int target, int pname) {
        if (!GLState.checkRenderThread()) return 0;
        return Queries.getQuery(target, pname);
    }

    public static void glGetQuery(int target, int pname, IntBuffer params) {
        if (!GLState.checkRenderThread()) return;
        if (params.hasRemaining()) {
            params.put(params.position(), Queries.getQuery(target, pname));
        }
    }

    public static int glGetQueryi(int target, int pname) {
        if (!GLState.checkRenderThread()) return 0;
        return Queries.getQuery(target, pname);
    }

    public static long glGetQueryObject(int id, int pname) {
        if (!GLState.checkRenderThread()) return 0L;
        return Queries.getObject(id, pname);
    }

    public static int glGetQueryObjecti(int id, int pname) {
        if (!GLState.checkRenderThread()) return 0;
        return (int) Queries.getObject(id, pname);
    }

    public static long glGetQueryObjectu(int id, int pname) {
        if (!GLState.checkRenderThread()) return 0L;
        return Queries.getObject(id, pname);
    }

    public static int glGetQueryObjectui(int id, int pname) {
        if (!GLState.checkRenderThread()) return 0;
        return (int) Queries.getObject(id, pname);
    }

    public static void glGetQueryObject(int id, int pname, IntBuffer params) {
        if (!GLState.checkRenderThread()) return;
        if (params.hasRemaining()) {
            params.put(params.position(), (int) Queries.getObject(id, pname));
        }
    }

    public static void glGetQueryObject(int id, int pname, LongBuffer params) {
        if (!GLState.checkRenderThread()) return;
        if (params.hasRemaining()) {
            params.put(params.position(), Queries.getObject(id, pname));
        }
    }

    public static void glGetQueryObjectu(int id, int pname, IntBuffer params) {
        if (!GLState.checkRenderThread()) return;
        if (params.hasRemaining()) {
            params.put(params.position(), (int) Queries.getObject(id, pname));
        }
    }

    public static void glGetQueryObjectu(int id, int pname, LongBuffer params) {
        if (!GLState.checkRenderThread()) return;
        if (params.hasRemaining()) {
            params.put(params.position(), Queries.getObject(id, pname));
        }
    }

    // ==================== VERTEX ARRAY OBJECTS (each one owns a set of client arrays) ====================

    public static int glGenVertexArrays() {
        if (!GLState.checkRenderThread()) return 0;
        return ClientState.genVertexArray();
    }

    public static void glGenVertexArrays(IntBuffer arrays) {
        if (!GLState.checkRenderThread()) return;
        ClientState.genVertexArrays(arrays);
    }

    public static void glBindVertexArray(int array) {
        if (!GLState.checkRenderThread()) return;
        ClientState.bindVertexArray(array);
    }

    public static void glDeleteVertexArrays(int array) {
        if (!GLState.checkRenderThread()) return;
        ClientState.deleteVertexArray(array);
    }

    public static void glDeleteVertexArrays(IntBuffer arrays) {
        if (!GLState.checkRenderThread()) return;
        for (int i = arrays.position(), end = arrays.limit(); i < end; i++) {
            ClientState.deleteVertexArray(arrays.get(i));
        }
    }

    public static boolean glIsVertexArray(int array) {
        if (!GLState.checkRenderThread()) return false;
        return ClientState.isVertexArray(array);
    }

    // ==================== GENERIC VERTEX ATTRIBUTES (shader programs are not supported: bookkeeping only) ====================

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer_buffer_offset) {
        if (!GLState.checkRenderThread()) return;
        GLUnsupported.call("glVertexAttribPointer");
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        GLUnsupported.call("glVertexAttribPointer");
    }

    public static void glVertexAttribPointer(int index, int size, boolean normalized, int stride, DoubleBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        GLUnsupported.call("glVertexAttribPointer");
    }

    public static void glVertexAttribPointer(int index, int size, boolean normalized, int stride, FloatBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        GLUnsupported.call("glVertexAttribPointer");
    }

    public static void glVertexAttribPointer(int index, int size, boolean unsigned, boolean normalized, int stride, ByteBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        GLUnsupported.call("glVertexAttribPointer");
    }

    public static void glVertexAttribPointer(int index, int size, boolean unsigned, boolean normalized, int stride, IntBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        GLUnsupported.call("glVertexAttribPointer");
    }

    public static void glVertexAttribPointer(int index, int size, boolean unsigned, boolean normalized, int stride, ShortBuffer pointer) {
        if (!GLState.checkRenderThread()) return;
        GLUnsupported.call("glVertexAttribPointer");
    }

    public static void glEnableVertexAttribArray(int index) {
        if (!GLState.checkRenderThread()) return;
        GLUnsupported.call("glEnableVertexAttribArray");
        ClientState.enableGenericAttrib(index, true);
    }

    public static void glDisableVertexAttribArray(int index) {
        if (!GLState.checkRenderThread()) return;
        ClientState.enableGenericAttrib(index, false);
    }

    // ==================== CLIENT ATTRIB STACK ====================

    public static void glPushClientAttrib(int mask) {
        if (!GLState.checkRenderThread()) return;
        ClientState.pushClientAttrib(mask);
    }

    public static void glPopClientAttrib() {
        if (!GLState.checkRenderThread()) return;
        ClientState.popClientAttrib();
    }
}

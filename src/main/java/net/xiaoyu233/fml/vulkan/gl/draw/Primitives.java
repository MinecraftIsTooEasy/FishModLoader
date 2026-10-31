package net.xiaoyu233.fml.vulkan.gl.draw;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

/**
 * GL primitive modes to Vulkan list topologies. Only POINT_LIST, LINE_LIST and TRIANGLE_LIST are ever used (no strips or fans:
 * MoltenVK/Metal have no triangle fans, and list topologies are the ones consecutive draws can be merged for):
 * <ul>
 * <li>POINTS, LINES and (smooth) TRIANGLES are drawn directly;</li>
 * <li>QUADS use the shared static quad index buffer: smooth {@code (0,1,2)(0,2,3)}, flat {@code (3,0,1)(3,1,2)};</li>
 * <li>LINE_STRIP, LINE_LOOP, TRIANGLE_STRIP, TRIANGLE_FAN, QUAD_STRIP, POLYGON (and flat TRIANGLES) become generated uint32 index
 * lists. With flat shading the triangles are rotated so that the vertex that provokes the flat color in GL (the last vertex of a
 * triangle/quad, vertex {@code i+2} of a strip/fan triangle, {@code 2i+3} of a quad strip quad) comes first, which is the Vulkan
 * provoking vertex.</li>
 * </ul>
 * The classification result is left in static fields (no allocation); render thread only.
 */
final class Primitives {
    /** Non-indexed draw of the vertices. */
    static final int K_DIRECT = 0;
    /** Indexed with the shared static quad index buffer. */
    static final int K_QUADS = 1;
    /** Indexed with generated uint32 indices (see the write* methods). */
    static final int K_INDEXED = 2;

    // classification result
    static int kind;
    static int topology;
    /** Vertices that take part (trailing vertices that do not complete a primitive are dropped). */
    static int vertices;
    /** Index count of the draw: K_QUADS = 6 per quad, K_INDEXED = the generated indices, K_DIRECT unused. */
    static int indexCount;

    private Primitives() {
    }

    static boolean isMode(int mode) {
        return mode >= GL11.GL_POINTS && mode <= GL11.GL_POLYGON;
    }

    /**
     * True for the modes whose generated triangle indices depend on the shade model (the rotation that moves the provoking vertex
     * first): display lists bake both variants and pick one at replay, because the shade model of the replay decides, not the one that
     * was current when the list was compiled. The other modes generate the same indices either way (LINES and POINTS have no rotation
     * here; GL_POLYGON's provoking vertex is already the first one of every fan triangle).
     */
    static boolean flatSensitive(int mode) {
        switch (mode) {
            case GL11.GL_TRIANGLES:
            case GL11.GL_TRIANGLE_STRIP:
            case GL11.GL_TRIANGLE_FAN:
            case GL11.GL_QUADS:
            case GL11.GL_QUAD_STRIP:
                return true;
            default:
                return false;
        }
    }

    /**
     * Classifies a draw of {@code n} consecutive vertices (glDrawArrays, glBegin/glEnd). Returns false when the draw produces no
     * primitive; the mode must have been validated with {@link #isMode}.
     */
    static boolean classifyArrays(int mode, int n, boolean flat) {
        switch (mode) {
            case GL11.GL_POINTS:
                return direct(VK10.VK_PRIMITIVE_TOPOLOGY_POINT_LIST, n);
            case GL11.GL_LINES:
                return direct(VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST, n & ~1);
            case GL11.GL_LINE_STRIP:
                return indexed(VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST, n, n >= 2 ? 2 * (n - 1) : 0);
            case GL11.GL_LINE_LOOP:
                return indexed(VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST, n, n >= 2 ? 2 * n : 0);
            case GL11.GL_TRIANGLES: {
                int v = n - n % 3;
                return flat ? indexed(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, v, v)
                        : direct(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, v);
            }
            case GL11.GL_TRIANGLE_STRIP:
            case GL11.GL_TRIANGLE_FAN:
            case GL11.GL_POLYGON:
                return indexed(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, n, n >= 3 ? 3 * (n - 2) : 0);
            case GL11.GL_QUADS: {
                int v = n & ~3;
                if (v < 4) {
                    return false;
                }
                kind = K_QUADS;
                topology = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                vertices = v;
                indexCount = (v >> 2) * 6;
                return true;
            }
            case GL11.GL_QUAD_STRIP: {
                int v = n & ~1;
                return indexed(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, v, v >= 4 ? 6 * (v / 2 - 1) : 0);
            }
            default:
                return false;
        }
    }

    /** Classifies an element draw of {@code count} indices: always K_INDEXED (the mapped, generated index list). */
    static boolean classifyElements(int mode, int count) {
        int ic;
        int top;
        switch (mode) {
            case GL11.GL_POINTS:
                top = VK10.VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
                ic = count;
                break;
            case GL11.GL_LINES:
                top = VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
                ic = count & ~1;
                break;
            case GL11.GL_LINE_STRIP:
                top = VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
                ic = count >= 2 ? 2 * (count - 1) : 0;
                break;
            case GL11.GL_LINE_LOOP:
                top = VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
                ic = count >= 2 ? 2 * count : 0;
                break;
            case GL11.GL_TRIANGLES:
                top = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                ic = count - count % 3;
                break;
            case GL11.GL_TRIANGLE_STRIP:
            case GL11.GL_TRIANGLE_FAN:
            case GL11.GL_POLYGON:
                top = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                ic = count >= 3 ? 3 * (count - 2) : 0;
                break;
            case GL11.GL_QUADS:
                top = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                ic = (count >> 2) * 6;
                break;
            case GL11.GL_QUAD_STRIP:
                top = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                ic = count >= 4 ? 6 * ((count & ~1) / 2 - 1) : 0;
                break;
            default:
                return false;
        }
        if (ic <= 0) {
            return false;
        }
        kind = K_INDEXED;
        topology = top;
        vertices = count;
        indexCount = ic;
        return true;
    }

    private static boolean direct(int top, int v) {
        if (v <= 0) {
            return false;
        }
        kind = K_DIRECT;
        topology = top;
        vertices = v;
        indexCount = 0;
        return true;
    }

    private static boolean indexed(int top, int v, int ic) {
        if (ic <= 0) {
            return false;
        }
        kind = K_INDEXED;
        topology = top;
        vertices = v;
        indexCount = ic;
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------------------
    // index generation
    // ------------------------------------------------------------------------------------------------------------------------

    /** Source of the vertex numbers the pattern positions map to: identity for arrays, the application's index array for elements. */
    private static long elemAddr;
    private static int elemType;
    private static int elemMin;
    private static int bias;

    /**
     * Writes the {@link #indexCount} uint32 indices of a K_INDEXED array draw of {@code n} vertices (the value of {@link #vertices})
     * to {@code dst}; {@code vertexBias} is added to every index (the draw's first vertex number inside a merged draw).
     */
    static void writeArrayIndices(int mode, int n, boolean flat, int vertexBias, long dst) {
        elemAddr = 0L;
        bias = vertexBias;
        pattern(mode, n, flat, dst);
    }

    /**
     * Element draw: the pattern positions are mapped through the application's index array ({@code idxType} UBYTE/USHORT/UINT at
     * {@code idxAddr}) and rebased by {@code -min + vertexBias}.
     */
    static void writeElementIndices(int mode, int count, boolean flat, long idxAddr, int idxType, int min, int vertexBias, long dst) {
        elemAddr = idxAddr;
        elemType = idxType;
        elemMin = min;
        bias = vertexBias;
        pattern(mode, count, flat, dst);
    }

    private static void pattern(int mode, int n, boolean flat, long dst) {
        long p = dst;
        switch (mode) {
            case GL11.GL_POINTS:
                for (int i = 0; i < n; i++, p += 4) {
                    put(p, i);
                }
                break;
            case GL11.GL_LINES: {
                int v = n & ~1;
                for (int i = 0; i < v; i++, p += 4) {
                    put(p, i);
                }
                break;
            }
            case GL11.GL_LINE_STRIP:
                for (int i = 0; i + 1 < n; i++, p += 8) {
                    put(p, i);
                    put(p + 4, i + 1);
                }
                break;
            case GL11.GL_LINE_LOOP:
                for (int i = 0; i + 1 < n; i++, p += 8) {
                    put(p, i);
                    put(p + 4, i + 1);
                }
                put(p, n - 1);
                put(p + 4, 0);
                break;
            case GL11.GL_TRIANGLES: {
                int v = n - n % 3;
                if (flat) {
                    for (int i = 0; i < v; i += 3, p += 12) {
                        put(p, i + 2);
                        put(p + 4, i);
                        put(p + 8, i + 1);
                    }
                } else {
                    for (int i = 0; i < v; i++, p += 4) {
                        put(p, i);
                    }
                }
                break;
            }
            case GL11.GL_TRIANGLE_STRIP:
                for (int i = 0; i + 2 < n; i++, p += 12) {
                    boolean even = (i & 1) == 0;
                    if (!flat) {
                        put(p, even ? i : i + 1);
                        put(p + 4, even ? i + 1 : i);
                        put(p + 8, i + 2);
                    } else {
                        put(p, i + 2);
                        put(p + 4, even ? i : i + 1);
                        put(p + 8, even ? i + 1 : i);
                    }
                }
                break;
            case GL11.GL_TRIANGLE_FAN:
                for (int t = 1; t + 1 < n; t++, p += 12) {
                    if (!flat) {
                        put(p, 0);
                        put(p + 4, t);
                        put(p + 8, t + 1);
                    } else {
                        put(p, t + 1);
                        put(p + 4, 0);
                        put(p + 8, t);
                    }
                }
                break;
            case GL11.GL_POLYGON:
                // the first vertex provokes a polygon, which is also the first vertex of every fan triangle
                for (int t = 1; t + 1 < n; t++, p += 12) {
                    put(p, 0);
                    put(p + 4, t);
                    put(p + 8, t + 1);
                }
                break;
            case GL11.GL_QUADS: {
                int v = n & ~3;
                for (int q = 0; q < v; q += 4, p += 24) {
                    if (!flat) {
                        put(p, q);
                        put(p + 4, q + 1);
                        put(p + 8, q + 2);
                        put(p + 12, q);
                        put(p + 16, q + 2);
                        put(p + 20, q + 3);
                    } else {
                        put(p, q + 3);
                        put(p + 4, q);
                        put(p + 8, q + 1);
                        put(p + 12, q + 3);
                        put(p + 16, q + 1);
                        put(p + 20, q + 2);
                    }
                }
                break;
            }
            case GL11.GL_QUAD_STRIP: {
                int v = n & ~1;
                for (int k = 0; k + 3 < v; k += 2, p += 24) {
                    // quad vertices in drawing order: a = k, b = k+1, c = k+3, d = k+2; c provokes the flat color
                    int a = k;
                    int b = k + 1;
                    int c = k + 3;
                    int d = k + 2;
                    if (!flat) {
                        put(p, a);
                        put(p + 4, b);
                        put(p + 8, c);
                        put(p + 12, a);
                        put(p + 16, c);
                        put(p + 20, d);
                    } else {
                        put(p, c);
                        put(p + 4, a);
                        put(p + 8, b);
                        put(p + 12, c);
                        put(p + 16, d);
                        put(p + 20, a);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private static void put(long address, int position) {
        int v;
        if (elemAddr == 0L) {
            v = position;
        } else {
            switch (elemType) {
                case GL11.GL_UNSIGNED_BYTE:
                    v = MemoryUtil.memGetByte(elemAddr + position) & 0xFF;
                    break;
                case GL11.GL_UNSIGNED_SHORT:
                    v = MemoryUtil.memGetShort(elemAddr + ((long) position << 1)) & 0xFFFF;
                    break;
                default:
                    v = MemoryUtil.memGetInt(elemAddr + ((long) position << 2));
                    break;
            }
            v -= elemMin;
        }
        MemoryUtil.memPutInt(address, v + bias);
    }
}

package net.xiaoyu233.fml.vulkan.render.tess;

import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.VkLog;
import net.xiaoyu233.fml.vulkan.gl.draw.DisplayLists;
import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import net.xiaoyu233.fml.vulkan.render.buffer.StaticMeshBuilder;
import net.xiaoyu233.fml.vulkan.render.buffer.VertexConsumer;
import net.xiaoyu233.fml.vulkan.render.legacy.LegacyTessSubmit;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Lighting;
import net.xiaoyu233.fml.vulkan.render.state.PoseStack;
import net.xiaoyu233.fml.vulkan.render.state.RenderType;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;

/**
 * Per-thread front-end of {@code Tessellator}/{@code TessellatorMITE} with MITE scheme-1 semantics (1.6, 5.5): sticky colour, brightness,
 * normal and uv state, {@code hasColor/hasTexture/hasBrightness/hasNormals}, {@code disableColor}, translation, MITE's unclamped colour
 * packing ({@link #setColorRGBA_MITE}) and the clamped packing of the vanilla class ({@link #setColorRGBA}), {@code add4VerticesWithUV(AO)}.
 * Vertices are buffered in the 32-byte MITE layout (8 ints: x, y, z, u, v, ABGR colour, normal bytes, brightness) until {@link #draw()}.
 * <p>
 * Unlike MITE's raw buffer every field of every vertex is written with the current value or a neutral default (colour white, uv 0, normal
 * 0, brightness 0) when the matching {@code has*} flag is off, so the buffer content is deterministic. Colour ints are always R-lowest
 * (little endian), which is also what the {@code UNIVERSAL} vertex format expects.
 * <p>
 * {@link #draw()} hands the vertices to the instance's {@link TessTarget}:
 * <ul>
 * <li>{@code DRAW} (render thread): vertices are finalised with the draw state at draw time like GL did at {@code glDrawArrays}
 * (position x pose, current colour/normal/light when the attribute is absent, uv x uv matrix, CPU lighting, overlay), converted from
 * the GL primitive mode to quads or line segments and appended to {@code Batches.consumer(renderType)}. While the safety net compiles a
 * display list the raw vertices go to {@code LegacyTessSubmit} instead (the {@code LEGACY_LIST} behaviour).</li>
 * <li>{@code MESH} (mesh worker): the raw quads go to the {@link MeshSink}, no state applied.</li>
 * <li>{@code CAPTURE} (render): object-space quads (white when the colour is absent) go to a {@code StaticMeshBuilder}.</li>
 * <li>{@code LEGACY_LIST}: raw vertices to the display-list emulation.</li>
 * </ul>
 * An instance is used by one thread only. The hot calls do not allocate; the buffers grow geometrically and are reused.
 */
public final class NativeTess {
    private static final int VERTEX_INTS = 8;
    /** MITE flushes a full buffer at {@code rawBufferIndex >= 2097152 - 32} (a multiple of the primitive size). */
    private static final int FLUSH_INTS = 2097152 - 32;
    private static final int INITIAL_INTS = 8 * 512;
    private static final int DEFAULT_COLOR = 0xFFFFFFFF;

    private static final int GL_POINTS = 0, GL_LINES = 1, GL_LINE_LOOP = 2, GL_LINE_STRIP = 3, GL_TRIANGLES = 4, GL_TRIANGLE_STRIP = 5,
            GL_TRIANGLE_FAN = 6, GL_QUADS = 7, GL_QUAD_STRIP = 8, GL_POLYGON = 9;

    /** {@code RenderingScheme.current}: 0 vanilla, 1 MITE, 101 / 102 MITE experimental (102 uses the vanilla colour packing). */
    private static int scheme = 1;

    // ---- raw buffer and Tessellator state
    private int[] raw = new int[INITIAL_INTS];
    private int rawIndex;
    private int vertexCount;
    private int addedVertices;
    private boolean drawing;
    private int drawMode;
    private int flushUnit = 4;
    private boolean hasColor, hasTexture, hasBrightness, hasNormals, colorDisabled;
    private double textureU, textureV;
    private int brightness;
    private int color = DEFAULT_COLOR;
    private int normal;
    private double xOffset, yOffset, zOffset;

    // ---- targets
    private TessTarget target = TessTarget.DRAW;
    private MeshSink meshSink;
    private StaticMeshBuilder captureBuilder;
    private TessTarget targetBeforeCapture;
    /** The render-thread instance mirrors its scalar state into the public fields of {@code Tessellator.instance} (5.5). */
    private boolean mirrorFields;

    // ---- finalisation scratch (grown, reused)
    private float[] fx = new float[0], fy = new float[0], fz = new float[0], fu = new float[0], fv = new float[0];
    private int[] fcolor = new int[0], flight = new int[0], foverlay = new int[0];
    private int[] quadScratch = new int[0];

    private static boolean nativeBatchesUnavailable;
    private static boolean warnedPoints, warnedLinesInMesh, warnedLegacyFallback;

    public NativeTess() {
    }

    /** Sets the rendering scheme that selects the colour packing of the shared Tessellator methods ({@code RenderingScheme.setCurrent}). */
    public static void setScheme(int rendererScheme) {
        scheme = rendererScheme;
    }

    public static int scheme() {
        return scheme;
    }

    /** Vanilla colour packing (clamped): scheme 0 and the experimental scheme 102 (which calls the vanilla super methods). */
    private static boolean vanillaColors() {
        int s = scheme;
        return s == 0 || s == 102;
    }

    // ================================================================================================ begin / reset / end

    public void reset() {
        vertexCount = 0;
        rawIndex = 0;
        addedVertices = 0;
    }

    public void startDrawingQuads() {
        startDrawing(GL_QUADS);
    }

    public void startDrawing(int glMode) {
        if (drawing) {
            throw new IllegalStateException("Already tesselating!");
        }
        drawing = true;
        reset();
        drawMode = glMode;
        hasNormals = false;
        hasColor = false;
        hasTexture = false;
        hasBrightness = false;
        colorDisabled = false;
        flushUnit = switch (glMode) {
            case GL_QUADS -> 4;
            case GL_TRIANGLES -> 3;
            case GL_LINES -> 2;
            case GL_POINTS -> 1;
            default -> 0; // strips, fans, loops and polygons cannot be split: the buffer grows instead
        };
    }

    /** Draws and clears the buffered vertices; returns the buffer size in bytes like the original. */
    public int draw() {
        if (!drawing) {
            throw new IllegalStateException("Not tesselating!");
        }
        drawing = false;
        if (vertexCount > 0) {
            submit();
        }
        int bytes = rawIndex * 4;
        reset();
        return bytes;
    }

    // ================================================================================================ attribute state

    public void setTextureUV(double u, double v) {
        hasTexture = true;
        textureU = u;
        textureV = v;
    }

    public void setBrightness(int brightness) {
        hasBrightness = true;
        this.brightness = brightness;
    }

    public void setColorOpaque_F(float r, float g, float b) {
        if (vanillaColors()) {
            setColorRGBA((int) (r * 255.0F), (int) (g * 255.0F), (int) (b * 255.0F), 255);
        } else if (!colorDisabled) {
            hasColor = true;
            color = 0xFF000000 | (int) (b * 255.0F) << 16 | (int) (g * 255.0F) << 8 | (int) (r * 255.0F);
        }
    }

    public void setColorRGBA_F(float r, float g, float b, float a) {
        if (vanillaColors()) {
            setColorRGBA((int) (r * 255.0F), (int) (g * 255.0F), (int) (b * 255.0F), (int) (a * 255.0F));
        } else if (!colorDisabled) {
            hasColor = true;
            color = (int) (a * 255.0F) << 24 | (int) (b * 255.0F) << 16 | (int) (g * 255.0F) << 8 | (int) (r * 255.0F);
        }
    }

    public void setColorOpaque(int r, int g, int b) {
        if (vanillaColors()) {
            setColorRGBA(r, g, b, 255);
        } else {
            setColorRGBA_MITE(r, g, b, 255);
        }
    }

    /** {@code Tessellator.setColorRGBA}: every channel clamped to 0..255. */
    public void setColorRGBA(int r, int g, int b, int a) {
        if (!colorDisabled) {
            r = r > 255 ? 255 : Math.max(r, 0);
            g = g > 255 ? 255 : Math.max(g, 0);
            b = b > 255 ? 255 : Math.max(b, 0);
            a = a > 255 ? 255 : Math.max(a, 0);
            hasColor = true;
            color = a << 24 | b << 16 | g << 8 | r;
        }
    }

    /** {@code TessellatorMITE.setColorRGBA}: no clamping (channels are or-ed as they are); scheme 0 / 102 use the clamped form. */
    public void setColorRGBA_MITE(int r, int g, int b, int a) {
        if (vanillaColors()) {
            setColorRGBA(r, g, b, a);
        } else if (!colorDisabled) {
            hasColor = true;
            color = a << 24 | b << 16 | g << 8 | r;
        }
    }

    public void setColorOpaque_I(int rgb) {
        setColorOpaque(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF);
    }

    public void setColorRGBA_I(int rgb, int a) {
        if (vanillaColors()) {
            setColorRGBA(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, a);
        } else {
            setColorRGBA_MITE(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, a);
        }
    }

    public void disableColor() {
        colorDisabled = true;
    }

    public void setNormal(float x, float y, float z) {
        hasNormals = true;
        byte nx = (byte) (x * 127.0F);
        byte ny = (byte) (y * 127.0F);
        byte nz = (byte) (z * 127.0F);
        normal = nx & 255 | (ny & 255) << 8 | (nz & 255) << 16;
    }

    public void setTranslation(double x, double y, double z) {
        xOffset = x;
        yOffset = y;
        zOffset = z;
    }

    public void addTranslation(float x, float y, float z) {
        xOffset += x;
        yOffset += y;
        zOffset += z;
    }

    // ================================================================================================ vertices

    public void addVertexWithUV(double x, double y, double z, double u, double v) {
        setTextureUV(u, v);
        addVertex(x, y, z);
    }

    public void addVertex(double x, double y, double z) {
        addedVertices++;
        int i = rawIndex;
        if (i + VERTEX_INTS > raw.length) {
            growRaw(i + VERTEX_INTS);
        }
        final int[] r = raw;
        r[i] = Float.floatToRawIntBits((float) (x + xOffset));
        r[i + 1] = Float.floatToRawIntBits((float) (y + yOffset));
        r[i + 2] = Float.floatToRawIntBits((float) (z + zOffset));
        if (hasTexture) {
            r[i + 3] = Float.floatToRawIntBits((float) textureU);
            r[i + 4] = Float.floatToRawIntBits((float) textureV);
        } else {
            r[i + 3] = 0;
            r[i + 4] = 0;
        }
        r[i + 5] = hasColor ? color : DEFAULT_COLOR;
        r[i + 6] = hasNormals ? normal : 0;
        r[i + 7] = hasBrightness ? brightness : 0;
        rawIndex = i + VERTEX_INTS;
        vertexCount++;
        if (rawIndex >= FLUSH_INTS && flushUnit != 0 && vertexCount % flushUnit == 0) {
            flushFull();
        }
    }

    /** MITE's {@code add4VerticesWithUV}: four quad corners with the sticky colour, brightness and normal. */
    public void add4VerticesWithUV(double[] x, double[] y, double[] z, double[] u, double[] v) {
        hasTexture = true;
        int i = rawIndex;
        if (i + 4 * VERTEX_INTS > raw.length) {
            growRaw(i + 4 * VERTEX_INTS);
        }
        final int[] r = raw;
        final int c = hasColor ? color : DEFAULT_COLOR;
        final int n = hasNormals ? normal : 0;
        final int b = hasBrightness ? brightness : 0;
        for (int k = 0; k < 4; k++, i += VERTEX_INTS) {
            r[i] = Float.floatToRawIntBits((float) (x[k] + xOffset));
            r[i + 1] = Float.floatToRawIntBits((float) (y[k] + yOffset));
            r[i + 2] = Float.floatToRawIntBits((float) (z[k] + zOffset));
            r[i + 3] = Float.floatToRawIntBits((float) u[k]);
            r[i + 4] = Float.floatToRawIntBits((float) v[k]);
            r[i + 5] = c;
            r[i + 6] = n;
            r[i + 7] = b;
        }
        rawIndex = i;
        addedVertices += 4;
        vertexCount += 4;
        if (rawIndex >= FLUSH_INTS) {
            flushFull();
        }
    }

    /** MITE's {@code add4VerticesWithUVandAO}: per corner colour (unclamped float packing, alpha 255) and brightness. */
    public void add4VerticesWithUVandAO(double[] x, double[] y, double[] z, double[] u, double[] v, float[] r, float[] g, float[] b, int[] brightness) {
        hasTexture = true;
        hasBrightness = true;
        if (!colorDisabled) {
            hasColor = true;
        }
        int i = rawIndex;
        if (i + 4 * VERTEX_INTS > raw.length) {
            growRaw(i + 4 * VERTEX_INTS);
        }
        final int[] a = raw;
        final int n = hasNormals ? normal : 0;
        for (int k = 0; k < 4; k++, i += VERTEX_INTS) {
            a[i] = Float.floatToRawIntBits((float) (x[k] + xOffset));
            a[i + 1] = Float.floatToRawIntBits((float) (y[k] + yOffset));
            a[i + 2] = Float.floatToRawIntBits((float) (z[k] + zOffset));
            a[i + 3] = Float.floatToRawIntBits((float) u[k]);
            a[i + 4] = Float.floatToRawIntBits((float) v[k]);
            a[i + 5] = colorDisabled ? (hasColor ? color : DEFAULT_COLOR)
                    : 0xFF000000 | (int) (b[k] * 255.0F) << 16 | (int) (g[k] * 255.0F) << 8 | (int) (r[k] * 255.0F);
            a[i + 6] = n;
            a[i + 7] = brightness[k];
        }
        rawIndex = i;
        addedVertices += 4;
        vertexCount += 4;
        if (rawIndex >= FLUSH_INTS) {
            flushFull();
        }
    }

    private void growRaw(int needed) {
        int cap = raw.length;
        while (cap < needed) {
            cap <<= 1;
        }
        raw = java.util.Arrays.copyOf(raw, cap);
    }

    /** Buffer full: draw what is there and keep tesselating (the flags stay, like MITE's {@code draw(); isDrawing = true}). */
    private void flushFull() {
        draw();
        drawing = true;
    }

    // ================================================================================================ queries

    public boolean isDrawing() {
        return drawing;
    }

    public int drawMode() {
        return drawMode;
    }

    public double xOffset() {
        return xOffset;
    }

    public double yOffset() {
        return yOffset;
    }

    public double zOffset() {
        return zOffset;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int addedVertices() {
        return addedVertices;
    }

    public int rawBufferIndex() {
        return rawIndex;
    }

    public boolean hasColor() {
        return hasColor;
    }

    public boolean hasTexture() {
        return hasTexture;
    }

    public boolean hasBrightness() {
        return hasBrightness;
    }

    public boolean hasNormals() {
        return hasNormals;
    }

    public boolean isColorDisabled() {
        return colorDisabled;
    }

    /** The buffered vertices, 8 ints each; valid up to {@link #rawBufferIndex()}. Read-only, do not keep. */
    public int[] rawBuffer() {
        return raw;
    }

    // ================================================================================================ targets

    public void setTarget(TessTarget t) {
        this.target = t;
    }

    public TessTarget target() {
        return target;
    }

    public void setMeshSink(MeshSink sink) {
        this.meshSink = sink;
    }

    /** The sink given to {@link #setMeshSink}; used by the MESH target. */
    public MeshSink meshSink() {
        return meshSink;
    }

    /** Switches to the CAPTURE target; the quads of every {@link #draw()} until {@link #endCapture()} go to {@code b}. */
    public void beginCapture(StaticMeshBuilder b) {
        if (b == null) {
            throw new IllegalArgumentException("builder");
        }
        if (captureBuilder != null) {
            throw new IllegalStateException("Already capturing");
        }
        captureBuilder = b;
        targetBeforeCapture = target;
        target = TessTarget.CAPTURE;
    }

    public void endCapture() {
        if (captureBuilder == null) {
            throw new IllegalStateException("Not capturing");
        }
        captureBuilder = null;
        target = targetBeforeCapture;
        targetBeforeCapture = null;
    }

    /** Package-private: marks the render-thread instance, which keeps the scalar fields of {@code Tessellator.instance} up to date. */
    void setMirrorFields(boolean on) {
        mirrorFields = on;
    }

    /** Copies {@code isDrawing}, {@code drawMode} and the offsets into the public fields of {@code t} (render-thread instance only). */
    public void mirrorTo(Tessellator t) {
        if (mirrorFields) {
            t.isDrawing = drawing;
            t.drawMode = drawMode;
            t.xOffset = xOffset;
            t.yOffset = yOffset;
            t.zOffset = zOffset;
            t.vertexCount = vertexCount;
            t.rawBufferIndex = rawIndex;
            t.addedVertices = addedVertices;
        }
    }

    // ================================================================================================ submit

    private void submit() {
        switch (target) {
            case MESH -> submitMesh();
            case CAPTURE -> submitCapture();
            case LEGACY_LIST -> submitLegacy();
            default -> {
                if (DisplayLists.isCompiling()) {
                    submitLegacy();
                } else {
                    submitDraw();
                }
            }
        }
    }

    private void submitLegacy() {
        try {
            LegacyTessSubmit.submit(raw, rawIndex, vertexCount, drawMode, hasTexture, hasColor, hasNormals, hasBrightness);
        } catch (UnsupportedOperationException e) {
            legacyReplay();
        }
    }

    private void legacyReplay() {
        if (!warnedLegacyFallback) {
            warnedLegacyFallback = true;
            VkLog.LOGGER.info("NativeTess: replaying tessellator vertices through the GL safety net (batching layer / list submission not available)");
        }
        LegacyTessFallback.draw(raw, rawIndex, vertexCount, drawMode, hasTexture, hasColor, hasNormals, hasBrightness);
    }

    private void submitMesh() {
        final MeshSink sink = meshSink;
        if (sink == null) {
            throw new IllegalStateException("MESH target without a MeshSink");
        }
        if (drawMode == GL_QUADS) {
            int whole = vertexCount - (vertexCount & 3);
            sink.quads(raw, whole * VERTEX_INTS, whole);
            return;
        }
        if (isLineMode(drawMode) || drawMode == GL_POINTS) {
            if (!warnedLinesInMesh) {
                warnedLinesInMesh = true;
                VkLog.LOGGER.warn("NativeTess: line/point primitives (mode {}) are not meshed", drawMode);
            }
            return;
        }
        int quads = quadCount(drawMode, vertexCount);
        if (quads == 0) {
            return;
        }
        if (quadScratch.length < quads * 4 * VERTEX_INTS) {
            quadScratch = new int[quads * 4 * VERTEX_INTS];
        }
        final int[] q = quadScratch;
        final int[] src = raw;
        int out = 0;
        for (int k = 0; k < quads; k++) {
            for (int c = 0; c < 4; c++) {
                System.arraycopy(src, quadVertex(drawMode, k, c) * VERTEX_INTS, q, out, VERTEX_INTS);
                out += VERTEX_INTS;
            }
        }
        sink.quads(q, out, quads * 4);
    }

    private void submitCapture() {
        final StaticMeshBuilder b = captureBuilder;
        if (b == null) {
            throw new IllegalStateException("CAPTURE target without a builder");
        }
        if (isLineMode(drawMode) || drawMode == GL_POINTS) {
            if (!warnedLinesInMesh) {
                warnedLinesInMesh = true;
                VkLog.LOGGER.warn("NativeTess: line/point primitives (mode {}) cannot be captured into a static mesh", drawMode);
            }
            return;
        }
        final int[] r = raw;
        final int n = vertexCount;
        final int quads = quadCount(drawMode, n);
        for (int k = 0; k < quads; k++) {
            for (int c = 0; c < 4; c++) {
                int i = quadVertex(drawMode, k, c) * VERTEX_INTS;
                b.vertex(Float.intBitsToFloat(r[i]), Float.intBitsToFloat(r[i + 1]), Float.intBitsToFloat(r[i + 2]), r[i + 5],
                        Float.intBitsToFloat(r[i + 3]), Float.intBitsToFloat(r[i + 4]), r[i + 7], 0);
            }
        }
    }

    // ================================================================================================ DRAW: finalisation

    private void submitDraw() {
        final boolean lines = isLineMode(drawMode);
        if (drawMode == GL_POINTS) {
            if (!warnedPoints) {
                warnedPoints = true;
                VkLog.LOGGER.warn("NativeTess: GL_POINTS geometry is dropped");
            }
            return;
        }
        if (nativeBatchesUnavailable && !Batches.isTestCapturing()) {
            legacyReplay();
            return;
        }
        final RenderType type = Draw.currentRenderType(lines ? VertexFormat.LINE : VertexFormat.UNIVERSAL);
        final VertexConsumer consumer;
        try {
            consumer = Batches.consumer(type);
        } catch (UnsupportedOperationException e) {
            nativeBatchesUnavailable = true;
            legacyReplay();
            return;
        }
        final int n = vertexCount;
        ensureScratch(n);
        finalizeVertices(n);
        if (lines) {
            emitLines(consumer, n);
            return;
        }
        final int quads = quadCount(drawMode, n);
        for (int k = 0; k < quads; k++) {
            put(consumer, quadVertex(drawMode, k, 0));
            put(consumer, quadVertex(drawMode, k, 1));
            put(consumer, quadVertex(drawMode, k, 2));
            put(consumer, quadVertex(drawMode, k, 3));
        }
    }

    private void ensureScratch(int n) {
        if (fx.length < n) {
            int cap = Math.max(n, fx.length * 2);
            fx = new float[cap];
            fy = new float[cap];
            fz = new float[cap];
            fu = new float[cap];
            fv = new float[cap];
            fcolor = new int[cap];
            flight = new int[cap];
            foverlay = new int[cap];
        }
    }

    private void put(VertexConsumer c, int i) {
        c.vertex(fx[i], fy[i], fz[i], fcolor[i], fu[i], fv[i], flight[i], foverlay[i]);
    }

    private void emitLines(VertexConsumer c, int n) {
        switch (drawMode) {
            case GL_LINES -> {
                for (int i = 0; i + 1 < n; i += 2) {
                    line(c, i, i + 1);
                }
            }
            case GL_LINE_STRIP -> {
                for (int i = 0; i + 1 < n; i++) {
                    line(c, i, i + 1);
                }
            }
            default -> { // GL_LINE_LOOP
                for (int i = 0; i + 1 < n; i++) {
                    line(c, i, i + 1);
                }
                if (n > 2) {
                    line(c, n - 1, 0);
                }
            }
        }
    }

    private void line(VertexConsumer c, int a, int b) {
        c.line(fx[a], fy[a], fz[a], fx[b], fy[b], fz[b], fcolor[a]);
    }

    /** Eye-space position, uv, lit colour, light and overlay of the first {@code n} raw vertices (GL state at draw time). */
    private void finalizeVertices(int n) {
        final int[] r = raw;
        final PoseStack pose = Draw.pose();
        final float[] m = pose.top();
        final boolean tex = hasTexture;
        final PoseStack uvStack = Draw.uvMatrix();
        final boolean uvIdentity = uvStack.isIdentity();
        final float[] um = uvStack.top();
        final boolean lit = Draw.lightingEnabled();
        final boolean overlay = Draw.overlayActive();
        final int overlayPlain = Draw.overlayArgb();
        final int overlayAbgr = overlay ? Draw.overlayAbgr() : 0;
        final int overlayAlpha = overlayPlain >>> 24;
        final int curColor = Draw.colorAbgr();
        final int curLight = Draw.lightPacked();
        final float cnx = Draw.normalX(), cny = Draw.normalY(), cnz = Draw.normalZ();
        final boolean normalize = Draw.normalizeEnabled();
        final boolean rescale = Draw.rescaleNormalEnabled();
        final float[] nm = lit ? pose.normalMatrix() : null;
        final float rescaleF = lit && !normalize && rescale ? pose.rescaleFactor() : 1f;
        final boolean useVertexColor = hasColor;
        final boolean useVertexNormal = hasNormals;
        final boolean useVertexLight = hasBrightness;

        int cacheNormal = 0;
        int cacheColor = 0;
        int cacheLit = 0;
        int cacheOverlay = 0;
        boolean cacheValid = false;

        for (int v = 0, i = 0; v < n; v++, i += VERTEX_INTS) {
            final float x = Float.intBitsToFloat(r[i]);
            final float y = Float.intBitsToFloat(r[i + 1]);
            final float z = Float.intBitsToFloat(r[i + 2]);
            fx[v] = m[0] * x + m[4] * y + m[8] * z + m[12];
            fy[v] = m[1] * x + m[5] * y + m[9] * z + m[13];
            fz[v] = m[2] * x + m[6] * y + m[10] * z + m[14];

            if (tex) {
                final float u = Float.intBitsToFloat(r[i + 3]);
                final float w = Float.intBitsToFloat(r[i + 4]);
                if (uvIdentity) {
                    fu[v] = u;
                    fv[v] = w;
                } else {
                    fu[v] = um[0] * u + um[4] * w + um[12];
                    fv[v] = um[1] * u + um[5] * w + um[13];
                }
            } else if (uvIdentity) {
                fu[v] = 0f;
                fv[v] = 0f;
            } else {
                fu[v] = um[12];
                fv[v] = um[13];
            }

            flight[v] = useVertexLight ? r[i + 7] : curLight;

            int col = useVertexColor ? r[i + 5] : curColor;
            int ov = overlayPlain;
            if (lit) {
                final int nint = useVertexNormal ? r[i + 6] : 0;
                if (cacheValid && cacheColor == col && cacheNormal == (useVertexNormal ? nint : -1)) {
                    col = cacheLit;
                    ov = cacheOverlay;
                } else {
                    float ox, oy, oz;
                    if (useVertexNormal) {
                        ox = (2f * (byte) nint + 1f) * (1f / 255f);
                        oy = (2f * (byte) (nint >> 8) + 1f) * (1f / 255f);
                        oz = (2f * (byte) (nint >> 16) + 1f) * (1f / 255f);
                    } else {
                        ox = cnx;
                        oy = cny;
                        oz = cnz;
                    }
                    float nx = nm[0] * ox + nm[3] * oy + nm[6] * oz;
                    float ny = nm[1] * ox + nm[4] * oy + nm[7] * oz;
                    float nz = nm[2] * ox + nm[5] * oy + nm[8] * oz;
                    if (normalize) {
                        final double len = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
                        if (len > 1e-30) {
                            final float inv = (float) (1.0 / len);
                            nx *= inv;
                            ny *= inv;
                            nz *= inv;
                        }
                    } else if (rescale) {
                        nx *= rescaleF;
                        ny *= rescaleF;
                        nz *= rescaleF;
                    }
                    final int litColor = Lighting.lit(col, nx, ny, nz);
                    int litOverlay = overlayPlain;
                    if (overlay) {
                        final int lo = Lighting.lit(overlayAbgr, nx, ny, nz);
                        litOverlay = overlayAlpha << 24 | (lo & 0xFF) << 16 | (lo >>> 8 & 0xFF) << 8 | (lo >>> 16 & 0xFF);
                    }
                    cacheValid = true;
                    cacheColor = col;
                    cacheNormal = useVertexNormal ? nint : -1;
                    cacheLit = litColor;
                    cacheOverlay = litOverlay;
                    col = litColor;
                    ov = litOverlay;
                }
            }
            fcolor[v] = col;
            foverlay[v] = ov;
        }
    }

    // ================================================================================================ primitive conversion

    private static boolean isLineMode(int mode) {
        return mode == GL_LINES || mode == GL_LINE_STRIP || mode == GL_LINE_LOOP;
    }

    /** Number of output quads of a filled primitive mode with {@code n} vertices (triangles become degenerate quads). */
    static int quadCount(int mode, int n) {
        return switch (mode) {
            case GL_QUADS -> n >> 2;
            case GL_TRIANGLES -> n / 3;
            case GL_TRIANGLE_STRIP, GL_TRIANGLE_FAN, GL_POLYGON -> Math.max(n - 2, 0);
            case GL_QUAD_STRIP -> Math.max((n >> 1) - 1, 0);
            default -> 0;
        };
    }

    /**
     * Source vertex index of corner {@code corner} (0..3) of output quad {@code k}: quads as they are; triangles {@code (a, b, c, c)};
     * strips with GL's winding ({@code (i, i+1, i+2)} for even and {@code (i+1, i, i+2)} for odd triangles); fans and polygons
     * {@code (0, k+1, k+2)}; quad strips {@code (2k, 2k+1, 2k+3, 2k+2)}.
     */
    static int quadVertex(int mode, int k, int corner) {
        switch (mode) {
            case GL_QUADS:
                return k * 4 + corner;
            case GL_TRIANGLES:
                return k * 3 + (corner == 3 ? 2 : corner);
            case GL_TRIANGLE_STRIP: {
                int c = corner == 3 ? 2 : corner;
                if ((k & 1) == 0) {
                    return k + c;
                }
                return c == 0 ? k + 1 : c == 1 ? k : k + 2;
            }
            case GL_QUAD_STRIP:
                return switch (corner) {
                    case 0 -> 2 * k;
                    case 1 -> 2 * k + 1;
                    case 2 -> 2 * k + 3;
                    default -> 2 * k + 2;
                };
            default: { // GL_TRIANGLE_FAN, GL_POLYGON
                int c = corner == 3 ? 2 : corner;
                return c == 0 ? 0 : k + c;
            }
        }
    }
}

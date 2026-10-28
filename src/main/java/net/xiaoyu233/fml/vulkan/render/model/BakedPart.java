package net.xiaoyu233.fml.vulkan.render.model;

import net.minecraft.ModelBox;
import net.minecraft.ModelRenderer;
import net.minecraft.PositionTextureVertex;
import net.minecraft.TexturedQuad;
import net.minecraft.Vec3;
import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import net.xiaoyu233.fml.vulkan.render.buffer.VertexConsumer;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.state.Lighting;
import net.xiaoyu233.fml.vulkan.render.state.PoseStack;
import net.xiaoyu233.fml.vulkan.render.state.VertexFormat;

/**
 * Baked model part (3.7): the quads of all {@code ModelBox}es of one {@code ModelRenderer} at one scale, stored once as
 * floats ({@link #FLOATS_PER_VERTEX} per vertex: x y z u v, then per quad one object-space normal nx ny nz, 4 vertices + normal =
 * {@link #FLOATS_PER_QUAD} floats). Owner: NR-13 (BakedPartImpl logic lives in this class).
 */
public final class BakedPart {
    public static final int FLOATS_PER_VERTEX = 5;
    public static final int FLOATS_PER_QUAD = 4 * FLOATS_PER_VERTEX + 3;

    /** Quad data, see class doc; length = quadCount * FLOATS_PER_QUAD. */
    public float[] data = new float[0];
    public int quadCount;
    /** Scale the part was baked with and the {@code cubeList.size()} at that time (staleness check). */
    public float scale;
    public int cubeCount;

    // Fields are widened at runtime by the access widener but private for javac; bake is cold, so reflect (cached).
    private static java.lang.reflect.Field quadListField;
    private static java.lang.reflect.Field invertField;

    private static TexturedQuad[] quadsOf(ModelBox box) {
        try {
            if (quadListField == null) {
                java.lang.reflect.Field f = ModelBox.class.getDeclaredField("quadList");
                f.setAccessible(true);
                quadListField = f;
            }
            return (TexturedQuad[]) quadListField.get(box);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ModelBox.quadList", e);
        }
    }

    private static boolean invertOf(TexturedQuad quad) {
        try {
            if (invertField == null) {
                java.lang.reflect.Field f = TexturedQuad.class.getDeclaredField("invertNormal");
                f.setAccessible(true);
                invertField = f;
            }
            return invertField.getBoolean(quad);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TexturedQuad.invertNormal", e);
        }
    }

    private BakedPart() {
    }

    /** Quantises a normal component like {@code Tessellator.setNormal} and decodes it like {@code NativeTess}. */
    private static float decodeNormal(float n) {
        final byte q = (byte) (n * 127.0F);
        return (2f * q + 1f) * (1f / 255f);
    }

    /** Bakes {@code part.cubeList} at {@code scale}: positions x scale, uvs, TexturedQuad.draw normal (invertNormal honoured). */
    public static BakedPart bake(ModelRenderer part, float scale) {
        final java.util.List<?> cubes = part.cubeList;
        int total = 0;
        for (int i = 0; i < cubes.size(); i++) {
            final TexturedQuad[] q = quadsOf((ModelBox) cubes.get(i));
            if (q != null) {
                total += q.length;
            }
        }
        final float[] d = new float[total * FLOATS_PER_QUAD];
        int o = 0;
        int count = 0;
        for (int i = 0; i < cubes.size(); i++) {
            final TexturedQuad[] quads = quadsOf((ModelBox) cubes.get(i));
            if (quads == null) {
                continue;
            }
            for (TexturedQuad quad : quads) {
                final PositionTextureVertex[] vp = quad.vertexPositions;
                final Vec3 p0 = vp[0].vector3D, p1 = vp[1].vector3D, p2 = vp[2].vector3D;
                final double ax = p1.xCoord - p0.xCoord, ay = p1.yCoord - p0.yCoord, az = p1.zCoord - p0.zCoord; // var3
                final double bx = p1.xCoord - p2.xCoord, by = p1.yCoord - p2.yCoord, bz = p1.zCoord - p2.zCoord; // var4
                final double cx = by * az - bz * ay;
                final double cy = bz * ax - bx * az;
                final double cz = bx * ay - by * ax;
                final double len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                float nx = 0f, ny = 0f, nz = 0f;
                if (!(len < 1.0E-4)) {
                    nx = (float) (cx / len);
                    ny = (float) (cy / len);
                    nz = (float) (cz / len);
                }
                if (invertOf(quad)) {
                    nx = -nx;
                    ny = -ny;
                    nz = -nz;
                }
                for (int v = 0; v < 4; v++) {
                    final PositionTextureVertex pv = vp[v];
                    d[o++] = (float) pv.vector3D.xCoord * scale;
                    d[o++] = (float) pv.vector3D.yCoord * scale;
                    d[o++] = (float) pv.vector3D.zCoord * scale;
                    d[o++] = pv.texturePositionX;
                    d[o++] = pv.texturePositionY;
                }
                // Same quantisation as Tessellator.setNormal ((byte)(n*127)), decoded as NativeTess.finalizeVertices does,
                // so CPU lighting is byte-exact with the MITE path (§1.10).
                d[o++] = decodeNormal(nx);
                d[o++] = decodeNormal(ny);
                d[o++] = decodeNormal(nz);
                count++;
            }
        }
        final BakedPart b = new BakedPart();
        b.data = count * FLOATS_PER_QUAD == d.length ? d : java.util.Arrays.copyOf(d, count * FLOATS_PER_QUAD);
        b.quadCount = count;
        b.scale = scale;
        b.cubeCount = cubes.size();
        return b;
    }

    /** Same as {@link #bake}; kept for the NR-00 skeleton signature. */
    public static BakedPart of(ModelRenderer part, float scale) {
        return bake(part, scale);
    }

    /**
     * True when {@code part}'s cube list changed size since baking. The scale is deliberately not checked: MITE keeps the
     * display list compiled at the first scale and reuses it for later scales (section 3.7).
     */
    public boolean isStale(ModelRenderer part) {
        return this.cubeCount != part.cubeList.size();
    }

    /**
     * Emits the quads through {@code Batches.consumer(Draw.currentRenderType(VertexFormat.UNIVERSAL))} with the current
     * {@code Draw.pose()}, uv matrix, colour, light, overlay and CPU lighting (same arithmetic as NativeTess.finalizeVertices).
     */
    public void emit() {
        if (quadCount == 0) {
            return;
        }
        final VertexConsumer c = Batches.consumer(Draw.currentRenderType(VertexFormat.UNIVERSAL));
        final PoseStack pose = Draw.pose();
        final float[] m = pose.top();
        final PoseStack uvStack = Draw.uvMatrix();
        final boolean uvIdentity = uvStack.isIdentity();
        final float[] um = uvStack.top();
        final boolean lit = Draw.lightingEnabled();
        final boolean overlay = Draw.overlayActive();
        final int overlayPlain = Draw.overlayArgb();
        final int overlayAbgr = overlay ? Draw.overlayAbgr() : 0;
        final int overlayAlpha = overlayPlain >>> 24;
        final int curColor = Draw.colorAbgr();
        final int light = Draw.lightPacked();
        final boolean normalize = Draw.normalizeEnabled();
        final boolean rescale = Draw.rescaleNormalEnabled();
        final float[] nm = lit ? pose.normalMatrix() : null;
        // Same value as PoseStack.rescaleFactor() without a second 3x3 inverse: the third row of the inverse is the normal matrix's
        // elements 6..8 (both come from the same top matrix), and a singular matrix yields the identity in both paths (factor 1).
        float rescaleF = 1f;
        if (lit && !normalize && rescale) {
            final double rl = Math.sqrt((double) nm[6] * nm[6] + (double) nm[7] * nm[7] + (double) nm[8] * nm[8]);
            rescaleF = rl > 1e-30 ? (float) (1.0 / rl) : 1f;
        }
        final float[] d = data;
        for (int q = 0, base = 0; q < quadCount; q++, base += FLOATS_PER_QUAD) {
            int col = curColor;
            int ov = overlayPlain;
            if (lit) {
                final float ox = d[base + 20], oy = d[base + 21], oz = d[base + 22];
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
                col = Lighting.lit(curColor, nx, ny, nz);
                if (overlay) {
                    final int lo = Lighting.lit(overlayAbgr, nx, ny, nz);
                    ov = overlayAlpha << 24 | (lo & 0xFF) << 16 | (lo >>> 8 & 0xFF) << 8 | (lo >>> 16 & 0xFF);
                }
            }
            for (int v = 0, i = base; v < 4; v++, i += FLOATS_PER_VERTEX) {
                final float x = d[i], y = d[i + 1], z = d[i + 2];
                final float u = d[i + 3], w = d[i + 4];
                final float fu, fv;
                if (uvIdentity) {
                    fu = u;
                    fv = w;
                } else {
                    fu = um[0] * u + um[4] * w + um[12];
                    fv = um[1] * u + um[5] * w + um[13];
                }
                c.vertex(m[0] * x + m[4] * y + m[8] * z + m[12],
                        m[1] * x + m[5] * y + m[9] * z + m[13],
                        m[2] * x + m[6] * y + m[10] * z + m[14],
                        col, fu, fv, light, ov);
            }
        }
    }

    public void invalidate() {
        data = new float[0];
        quadCount = 0;
        cubeCount = -1;
    }
}

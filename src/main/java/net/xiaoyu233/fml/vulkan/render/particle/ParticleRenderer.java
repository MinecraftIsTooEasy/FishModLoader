package net.xiaoyu233.fml.vulkan.render.particle;

import java.util.List;
import net.minecraft.Entity;
import net.minecraft.ActiveRenderInfo;
import net.minecraft.EntityFX;
import net.minecraft.MathHelper;
import net.minecraft.ResourceLocation;
import net.minecraft.TextureManager;
import net.minecraft.TextureMap;
import net.minecraft.Tessellator;
import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.render.state.BlendFactor;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

/**
 * Native {@code EffectRenderer.renderParticles / renderLitParticles} (3.10). Called from {@code EffectRendererParticleMixin}; render thread
 * only. Particles keep writing through {@code Tessellator.instance} (NativeTess), so the FX classes' {@code renderParticle} are untouched.
 */
public final class ParticleRenderer {
    private static final ResourceLocation PARTICLE_TEXTURES = new ResourceLocation("textures/particle/particles.png");
    /** Sort scratch, grown on demand and reused across frames so the sorted path allocates nothing in steady state. */
    private static EntityFX[] scratch = new EntityFX[256];
    private static EntityFX[] sortOut = new EntityFX[256];
    private static double[] keys = new double[256];
    private static int[] idx = new int[256];
    private static int[] idxTmp = new int[256];

    private ParticleRenderer() {
    }

    /**
     * Layers 0-2: for every non-empty layer bind its texture (0 particles.png, 1 blocks atlas, 2 items atlas), set exactly MITE's state
     * (colour 1,1,1,1; depth mask on; blend SRC_ALPHA/ONE_MINUS_SRC_ALPHA; alpha ref 1/255; lightmap + fog as set by the caller), draw one batch
     * per layer, then reset (blend off, depth mask on, alpha 0.1). Starts with {@code Frame.beginPhase(Phase.PARTICLES)} is NOT done here (the
     * world frame does it); sets {@code EntityFX.interpPos*} from the camera entity exactly like MITE.
     */
    @SuppressWarnings("rawtypes")
    public static void renderParticles(List[] layers, TextureManager textures, Entity camera, float partialTicks) {
        float rotX = ActiveRenderInfo.rotationX;
        float rotZ = ActiveRenderInfo.rotationZ;
        float rotYZ = ActiveRenderInfo.rotationYZ;
        float rotXY = ActiveRenderInfo.rotationXY;
        float rotXZ = ActiveRenderInfo.rotationXZ;
        EntityFX.interpPosX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        EntityFX.interpPosY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        EntityFX.interpPosZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        ParticleFastPath.beginFrame();
        boolean fast = ParticleFastPath.enabled();
        boolean sort = VulkanConfig.particlesSort();

        for (int layer = 0; layer < 3; layer++) {
            List list = layers[layer];
            if (list.isEmpty()) {
                continue;
            }
            switch (layer) {
                case 0:
                default:
                    textures.bindTexture(PARTICLE_TEXTURES);
                    break;
                case 1:
                    textures.bindTexture(TextureMap.locationBlocksTexture);
                    break;
                case 2:
                    textures.bindTexture(TextureMap.locationItemsTexture);
            }

            Draw.color(1.0F, 1.0F, 1.0F, 1.0F);
            Draw.depthMask(true);
            Draw.blend(true);
            Draw.blendFunc(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            Draw.alphaFunc(0.003921569F);
            Tessellator tess = Tessellator.instance;
            tess.startDrawingQuads();

            int n = list.size();
            EntityFX[] order = null;
            if (sort && n > 1) {
                order = sorted(list, n);
            }
            for (int i = 0; i < n; i++) {
                EntityFX fx = order != null ? order[i] : (EntityFX) list.get(i);
                if (fast && ParticleFastPath.cullable(fx.getClass()) && ParticleFastPath.culled(fx, partialTicks)) {
                    if (ParticleFastPath.initsPrevPosWhenCulled(fx.getClass())) {
                        ParticleFastPath.initPrevPos(fx);
                    }
                    continue;
                }
                tess.setBrightness(fx.getBrightnessForRender(partialTicks));
                fx.renderParticle(tess, partialTicks, rotX, rotXZ, rotZ, rotYZ, rotXY);
            }
            if (order != null) {
                java.util.Arrays.fill(order, 0, n, null);
            }

            tess.draw();
            Draw.blend(false);
            Draw.depthMask(true);
            Draw.alphaFunc(0.1F);
        }
    }

    /** Copies the layer into the scratch array and sorts it back-to-front by squared distance to the camera. */
    @SuppressWarnings("rawtypes")
    private static EntityFX[] sorted(List list, int n) {
        if (scratch.length < n) {
            int cap = Math.max(n, scratch.length * 2);
            scratch = new EntityFX[cap];
            sortOut = new EntityFX[cap];
            keys = new double[cap];
            idx = new int[cap];
            idxTmp = new int[cap];
        }
        EntityFX[] a = scratch;
        double[] key = keys;
        for (int i = 0; i < n; i++) {
            EntityFX fx = (EntityFX) list.get(i);
            a[i] = fx;
            double dx = fx.posX - EntityFX.interpPosX;
            double dy = fx.posY - EntityFX.interpPosY;
            double dz = fx.posZ - EntityFX.interpPosZ;
            key[i] = dx * dx + dy * dy + dz * dz;
            idx[i] = i;
        }
        int[] res = mergeSortDescending(key, idx, idxTmp, n);
        EntityFX[] out = sortOut;
        for (int i = 0; i < n; i++) {
            out[i] = a[res[i]];
        }
        System.arraycopy(out, 0, a, 0, n);
        java.util.Arrays.fill(out, 0, n, null);
        return a;
    }

    /**
     * Stable bottom-up merge sort of indices {@code 0..n-1} by {@code key}, descending (farthest first). Ties keep their original
     * order, matching the previous stable {@code Arrays.sort}. Works on the given scratch arrays only; returns whichever of
     * {@code idx} / {@code tmp} holds the result.
     */
    private static int[] mergeSortDescending(double[] key, int[] idx, int[] tmp, int n) {
        int[] src = idx;
        int[] dst = tmp;
        for (int width = 1; width < n; width <<= 1) {
            for (int lo = 0; lo < n; lo += width << 1) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + (width << 1), n);
                int i = lo;
                int j = mid;
                int k = lo;
                while (i < mid && j < hi) {
                    // take the right element only when it is strictly farther, so equal keys stay in original order
                    if (Double.compare(key[src[j]], key[src[i]]) > 0) {
                        dst[k++] = src[j++];
                    } else {
                        dst[k++] = src[i++];
                    }
                }
                while (i < mid) {
                    dst[k++] = src[i++];
                }
                while (j < hi) {
                    dst[k++] = src[j++];
                }
            }
            int[] swap = src;
            src = dst;
            dst = swap;
        }
        return src;
    }

    /** Layer 3 (lit particles, per particle own state): see MITE {@code renderLitParticles}; consecutive equal render types merge. */
    @SuppressWarnings("rawtypes")
    public static void renderLitParticles(List[] layers, Entity camera, float partialTicks) {
        float c = MathHelper.cos(camera.rotationYaw * (float) (Math.PI / 180.0));
        float s = MathHelper.sin(camera.rotationYaw * (float) (Math.PI / 180.0));
        float sp = MathHelper.sin(camera.rotationPitch * (float) (Math.PI / 180.0));
        float v6 = -s * sp;
        float v7 = c * sp;
        float v8 = MathHelper.cos(camera.rotationPitch * (float) (Math.PI / 180.0));
        List list = layers[3];
        if (!list.isEmpty()) {
            Tessellator tess = Tessellator.instance;
            for (int i = 0; i < list.size(); i++) {
                EntityFX fx = (EntityFX) list.get(i);
                tess.setBrightness(fx.getBrightnessForRender(partialTicks));
                fx.renderParticle(tess, partialTicks, c, v8, s, v6, v7);
            }
        }
    }
}

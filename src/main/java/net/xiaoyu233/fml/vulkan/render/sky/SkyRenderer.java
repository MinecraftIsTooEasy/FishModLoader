package net.xiaoyu233.fml.vulkan.render.sky;

import net.xiaoyu233.fml.vulkan.render.buffer.StaticMeshes;

/**
 * Sky drawing (3.12). NR-11 implements it. <b>NR-00 stub:</b> the list indices (0 stars, 1 sky, 2 sky2) are the offsets from
 * {@link StaticMeshes#skyBase()} and the capture/draw calls forward to {@link StaticMeshes}, so NR-09's ordinal-indexed
 * {@code glNewList}/{@code glEndList} redirects work before NR-11 lands.
 */
public final class SkyRenderer {
    /** {@link StaticMeshes#deviceEpoch()} the three lists were last built against; differs until the first build. */
    private static int builtEpoch = Integer.MIN_VALUE;

    private SkyRenderer() {
    }

    private static int id(int listIndex) {
        int base = StaticMeshes.skyBase();
        if (base == 0) {
            base = StaticMeshes.reserveSkyIds();
        }
        return base + listIndex;
    }

    /** Called by NR-09's {@code glNewList} redirects in {@code RenderGlobal.<init>}: 0 stars, 1 sky, 2 sky2. */
    public static void beginCapture(int listIndex) {
        StaticMeshes.beginCapture(id(listIndex));
    }

    public static void endCapture(int listIndex) {
        StaticMeshes.endCapture();
    }

    /** Replacement of {@code glCallList} in {@code renderSky}. */
    public static void drawList(int listIndex) {
        if (StaticMeshes.deviceEpoch() != builtEpoch) {
            buildStaticMeshes();
        }
        int id = id(listIndex);
        if (!StaticMeshes.exists(id)) {
            buildList(listIndex);
        }
        StaticMeshes.draw(id);
    }

    private static void buildList(int listIndex) {
        beginCapture(listIndex);
        try {
            switch (listIndex) {
                case 0 -> SkyDraw.renderStars();
                case 1 -> SkyDraw.buildSkyDisc();
                default -> SkyDraw.buildLowerDisc();
            }
        } catch (Throwable t) {
            StaticMeshes.abortCapture();
            throw t;
        }
        endCapture(listIndex);
    }

    /**
     * Rebuilds all three lists (resource reload or device loss). {@link #drawList(int)} calls it automatically when the
     * {@link StaticMeshes#deviceEpoch()} changed, so device re-creation needs no explicit caller.
     */
    public static void buildStaticMeshes() {
        int epoch = StaticMeshes.deviceEpoch();
        for (int i = 0; i < 3; i++) {
            buildList(i);
        }
        builtEpoch = epoch;
    }

    /** Body of {@code RenderGlobal.renderSky}; the mixin passes the fields it shadows. */
    public static void renderSky(net.minecraft.Minecraft mc, net.minecraft.TextureManager engine, float partialTicks) {
        SkyDraw.renderSky(mc, engine, partialTicks);
    }

    /** Body of {@code RenderGlobal.renderStars}. */
    public static void renderStars() {
        SkyDraw.renderStars();
    }
}

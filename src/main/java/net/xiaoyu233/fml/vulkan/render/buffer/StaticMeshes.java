package net.xiaoyu233.fml.vulkan.render.buffer;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.gl.draw.DisplayLists;
import net.xiaoyu233.fml.vulkan.render.state.Draw;
import net.xiaoyu233.fml.vulkan.render.tess.TessBridge;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Replacement of display lists in rewritten code (1.6, 3.1): ids map to GPU-resident {@link StaticMesh}es. When the GPU core is not
 * initialised the NR-00 behaviour (phase-1 display-list emulation) is used so pre-Vulkan paths keep working. Render thread only.
 */
public final class StaticMeshes {
    static {
        Batches.install();
    }

    private static final int GL_COMPILE = 4864;

    /** Id base of the most recent {@link #reserveIds(int)} block of three (the sky/star lists), 0 when none was reserved. */
    private static int skyBase;

    private static StaticMesh[] meshes = new StaticMesh[64];
    /** Ids whose content lives in the legacy display-list emulation. */
    private static final BitSet LEGACY = new BitSet();
    private static boolean gpuUnavailable;

    /** VkDevice handle last seen by {@link #deviceEpoch()}; 0 when RenderCore was not initialised. */
    private static long seenDevice;
    private static int deviceEpoch;

    private static StaticMeshBuilder capturing;
    private static int captureId;
    private static boolean captureLegacy;

    private StaticMeshes() {
    }

    /** Reserves {@code count} consecutive mesh ids and returns the first. Ids are monotonic. */
    public static int reserveIds(int count) {
        return DisplayLists.genLists(count);
    }

    /** Reserves the three sky/star ids (stars, sky, sky2) and remembers the base for {@link #skyBase()}. */
    public static int reserveSkyIds() {
        skyBase = DisplayLists.genLists(3);
        return skyBase;
    }

    /** First id of the last reserved block of three ids (star, sky, sky2 lists), 0 if there is none. */
    public static int skyBase() {
        return skyBase;
    }

    /**
     * Device epoch: a counter bumped whenever the VkDevice differs from the one last seen (device re-creation, shutdown, first use).
     * On a change every stored mesh is dropped WITHOUT being freed: its memory went down with the old device, and freeing it
     * through the new arena would be a foreign allocation. Callers that cache GPU-backed content (sky lists, clouds) store the epoch
     * they built against and rebuild when it differs. Render thread only.
     */
    public static int deviceEpoch() {
        long d = 0L;
        var device = RenderCore.device();
        if (device != null) {
            d = device.address();
        }
        if (d != seenDevice) {
            seenDevice = d;
            deviceEpoch++;
            for (StaticMesh m : meshes) {
                if (m != null) {
                    m.destroyed = true;
                }
            }
            Arrays.fill(meshes, null);
            gpuUnavailable = false;
        }
        return deviceEpoch;
    }

    public static void beginCapture(int id) {
        if (capturing != null || captureLegacy) {
            throw new IllegalStateException("Already capturing");
        }
        deviceEpoch();
        captureId = id;
        if (!gpuUnavailable) {
            try {
                StaticMesh.arena();
            } catch (Throwable t) {
                gpuUnavailable = true;
            }
        }
        if (gpuUnavailable) {
            captureLegacy = true;
            DisplayLists.newList(id, GL_COMPILE);
            return;
        }
        capturing = new StaticMeshBuilder();
        TessBridge.renderThread().beginCapture(capturing);
    }

    /**
     * Abandons the capture in progress (its body threw before {@link #endCapture()}): frees the capture builder, restores the
     * tessellator's previous target and closes the legacy list being compiled. The mesh previously stored under the id is kept.
     * Safe to call when no capture is in progress. Capture bodies must call this from a {@code finally} or {@code catch} block.
     */
    public static void abortCapture() {
        if (captureLegacy) {
            captureLegacy = false;
            DisplayLists.endList();
            return;
        }
        StaticMeshBuilder b = capturing;
        if (b == null) {
            return;
        }
        capturing = null;
        try {
            TessBridge.renderThread().endCapture();
        } finally {
            b.discard();
        }
    }

    public static void endCapture() {
        if (captureLegacy) {
            captureLegacy = false;
            DisplayLists.endList();
            deleteMesh(captureId);
            LEGACY.set(captureId);
            return;
        }
        StaticMeshBuilder b = capturing;
        if (b == null) {
            throw new IllegalStateException("Not capturing");
        }
        capturing = null;
        TessBridge.renderThread().endCapture();
        StaticMesh mesh = b.build();
        deleteMesh(captureId);
        LEGACY.clear(captureId);
        if (captureId >= meshes.length) {
            meshes = Arrays.copyOf(meshes, Math.max(captureId + 1, meshes.length * 2));
        }
        meshes[captureId] = mesh;
    }

    public static void draw(int id) {
        if (id >= 0 && id < meshes.length) {
            StaticMesh m = meshes[id];
            if (m != null) {
                if (m.quadCount() > 0) {
                    Draw.drawStatic(m);
                }
                return;
            }
        }
        if (id >= 0 && LEGACY.get(id)) {
            DisplayLists.callList(id);
        }
    }

    public static void delete(int id) {
        deleteMesh(id);
        if (id >= 0 && LEGACY.get(id)) {
            LEGACY.clear(id);
            DisplayLists.deleteLists(id, 1);
        }
    }

    public static boolean exists(int id) {
        if (id >= 0 && id < meshes.length && meshes[id] != null) {
            return true;
        }
        return id >= 0 && LEGACY.get(id);
    }

    private static void deleteMesh(int id) {
        if (id >= 0 && id < meshes.length) {
            StaticMesh m = meshes[id];
            if (m != null) {
                meshes[id] = null;
                m.destroy();
            }
        }
    }
}

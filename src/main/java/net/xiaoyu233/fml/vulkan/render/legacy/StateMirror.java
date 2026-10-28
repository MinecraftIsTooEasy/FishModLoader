package net.xiaoyu233.fml.vulkan.render.legacy;

import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.gl.state.GLState;
import net.xiaoyu233.fml.vulkan.render.state.Draw;

/**
 * Typed state mirroring between {@code Draw} and the GL emulation ({@code GLState}), ARCHITECTURE-NATIVE 1.16.3. Lazy mode: dirty
 * bits only, groups are copied when unmigrated code is about to draw / change state. Render thread only; the sync methods are O(1)
 * no-ops while the relevant dirty mask is 0.
 */
public final class StateMirror {
    public static final String PROP_MODE = "fml.vulkan.legacyMirror";

    /** Set by the GL facades' state setters (lazy mode). */
    public static volatile int legacyDirty;

    /**
     * Set (one {@code |=}) by Draw setters in lazy mode: LegacyGroups bits the emulation has not pulled yet. Render thread only, so a
     * plain field (the hot path is one load and one store).
     */
    public static int nativeDirty;
    /** Counters for the acceptance check "0 sync calls per frame when no unmigrated code runs". */
    public static long syncNativeToLegacyCalls;
    public static long syncLegacyToNativeCalls;

    /** Mode read once from the system property (the first touch of this class happens after launch properties are set). */
    private static final LegacyMirrorMode MODE = LegacyMirrorMode.parse(System.getProperty(PROP_MODE));
    /** Cached flags so the hot setters do not call {@link #mode()} or read the enum. */
    private static final boolean EAGER = MODE == LegacyMirrorMode.EAGER;
    private static final boolean OFF = MODE == LegacyMirrorMode.OFF;
    private static boolean syncing;

    private StateMirror() {
    }

    /** Current mode (system property {@code fml.vulkan.legacyMirror}, read once). */
    public static LegacyMirrorMode mode() {
        return MODE;
    }

    /**
     * Called by the GL facades' state setters <em>before</em> they mutate the emulation fields. A pending native value for the same group
     * is pulled into the emulation first, so the setter's change lands on top of the newest native state and is not overwritten by a
     * stale native value at the next pull. Only the bit is marked here: an eager push would copy the pre-change values and lose the
     * change, so pending legacy bits are pushed at the next LegacyInterop hook. OFF mode records nothing.
     */
    public static void onLegacyChanged(int groupBit) {
        if (OFF) {
            return;
        }
        if ((nativeDirty & groupBit) != 0 && !syncing) {
            syncNativeToLegacy();
        }
        if ((legacyDirty & groupBit) != groupBit) legacyDirty |= groupBit;
    }

    /** Resets the per-frame sync counters and returns {native-to-legacy, legacy-to-native} of the finished frame. */
    public static long[] drainFrameCounters() {
        final long[] r = {syncNativeToLegacyCalls, syncLegacyToNativeCalls};
        syncNativeToLegacyCalls = 0;
        syncLegacyToNativeCalls = 0;
        return r;
    }

    public static boolean enabled() {
        return !OFF && VulkanConfig.nativeEnabled();
    }

    /** Pulls the native-dirty groups of {@code Draw} into the GL emulation. */
    public static void syncNativeToLegacy() {
        final int bits = nativeDirty;
        if (bits == 0 || syncing) {
            return;
        }
        syncing = true;
        // the emulation's own dirty bits must not see the mirror writes (no ping-pong)
        final int savedLegacy = legacyDirty;
        try {
            nativeDirty = 0;
            GLState.get().mirrorPullFromNative(bits);
        } finally {
            legacyDirty = savedLegacy;
            syncing = false;
        }
        syncNativeToLegacyCalls++;
    }

    /** Pushes the legacy-dirty groups of the GL emulation into {@code Draw}. */
    public static void syncLegacyToNative() {
        final int bits = legacyDirty;
        if (bits == 0 || syncing) {
            return;
        }
        syncing = true;
        legacyDirty = 0;
        Draw.beginLegacyPull();
        try {
            GLState.get().mirrorPushToNative(bits);
        } finally {
            Draw.endLegacyPull();
            syncing = false;
        }
        syncLegacyToNativeCalls++;
    }

    /** Draw setters: lazy = set a dirty bit; eager = push now. */
    public static void onNativeChanged(int groupBit) {
        if (OFF) {
            return;
        }
        // the native write is newer than a pending legacy write of the same group: drop that bit so the older value is not pushed back
        legacyDirty &= ~groupBit;
        nativeDirty |= groupBit;
        if (EAGER && !syncing) {
            syncNativeToLegacy();
        }
    }
}

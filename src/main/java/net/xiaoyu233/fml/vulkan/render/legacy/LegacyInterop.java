package net.xiaoyu233.fml.vulkan.render.legacy;

import net.xiaoyu233.fml.vulkan.VulkanConfig;
import net.xiaoyu233.fml.vulkan.gl.draw.DrawSystem;
import net.xiaoyu233.fml.vulkan.render.buffer.Batches;
import net.xiaoyu233.fml.vulkan.render.gpu.CommandStream;

/**
 * Ordering hooks between native batches and emulated GL draws (1.16.4, 5.11). All hooks are no-ops when
 * {@code fml.vulkan.native=false}, re-entrancy safe (guard flag) and O(1) without allocation when nothing is pending.
 * TODO(NR-03/NR-01): a cheap "has pending" query on Batches / DrawSystem would avoid the call overhead; both flush entry points are
 * already O(1) when empty.
 */
public final class LegacyInterop {
    private static final boolean NATIVE = VulkanConfig.nativeEnabled();

    /** Set while a hook runs a flush/sync: flushes call back into these hooks (Batches.flushPhase -> beforeNativeRecord). */
    private static boolean busy;

    private LegacyInterop() {
    }

    /** Before the emulation records a draw: pending native batches first, then the native state the emulation has not pulled. */
    public static void beforeEmulatedDraw() {
        if (!NATIVE || busy) {
            return;
        }
        // Not under busy: flushPhase -> beforeNativeRecord must still run so the pending emulated merged draw
        // (DrawSystem) is recorded before the native batches. Batches' own 'flushing' guard prevents re-entry.
        Batches.flushPhase();
        if (StateMirror.nativeDirty != 0) {
            busy = true;
            try {
                StateMirror.syncNativeToLegacy();
            } finally {
                busy = false;
            }
        }
    }

    /** Before native code records: the emulation's pending merged draw goes first, then the legacy state is pulled. */
    public static void beforeNativeRecord() {
        if (!NATIVE || busy) {
            return;
        }
        busy = true;
        try {
            DrawSystem.flush();
            if (StateMirror.legacyDirty != 0) {
                StateMirror.syncLegacyToNative();
            }
        } finally {
            busy = false;
        }
    }

    /**
     * Before native code records raw vkCmd* into the main command buffer (texture copies, readback, timers): the pending native batches
     * and the emulation's pending merged draw are recorded first, so the raw commands do not overtake them. Callers must invoke this
     * before recording and {@link #afterRawCommands()} after.
     */
    public static void beforeRawCommands() {
        if (!NATIVE || busy) {
            return;
        }
        // Batches.flushPhase only reaches beforeNativeRecord when native entries are pending, so DrawSystem is flushed explicitly too.
        Batches.flushPhase();
        busy = true;
        try {
            DrawSystem.flush();
        } finally {
            busy = false;
        }
    }

    /**
     * After native code recorded raw vkCmd*: the caches shared with the emulation no longer match the command buffer. Callers must
     * have called {@link #beforeRawCommands()} before recording.
     */
    public static void afterRawCommands() {
        if (!NATIVE) {
            return;
        }
        CommandStream.invalidate();
    }

    /** After an emulated GL call changed state: pull it into the native side (before Draw.materialKey()). */
    public static void afterEmulatedCall() {
        if (!NATIVE || StateMirror.legacyDirty == 0 || busy) {
            return;
        }
        busy = true;
        try {
            StateMirror.syncLegacyToNative();
        } finally {
            busy = false;
        }
    }
}

package net.xiaoyu233.fml.vulkan.render.tess;

import net.minecraft.Tessellator;

/**
 * Selects the {@link NativeTess} of the calling thread without a ThreadLocal (1.6): a {@link MeshWorkerThread} carries its instance in a
 * final field, every other thread gets the render-thread instance (as MITE had one shared {@code Tessellator.instance}). The forwarding
 * mixins of {@code Tessellator}/{@code TessellatorMITE} call {@link #current()} for every vertex, so it is one type check and one field
 * read.
 */
public final class TessBridge {
    private static final NativeTess RENDER_TESS = new NativeTess();

    static {
        RENDER_TESS.setMirrorFields(true);
    }

    private static volatile Thread renderThread;

    private TessBridge() {
    }

    /** A mesh worker's own instance, the render thread's instance for every other thread. */
    public static NativeTess current() {
        return Thread.currentThread() instanceof MeshWorkerThread w ? w.tess() : RENDER_TESS;
    }

    public static NativeTess renderThread() {
        return RENDER_TESS;
    }

    /** A new instance with target {@link TessTarget#MESH} that delivers its quads to {@code sink}. */
    public static NativeTess createWorkerTess(MeshSink sink) {
        NativeTess t = new NativeTess();
        t.setTarget(TessTarget.MESH);
        t.setMeshSink(sink);
        return t;
    }

    /**
     * The render-thread instance after a draw (or any state change a game class reads back): copies {@code isDrawing}, {@code drawMode}
     * and the offsets into the public fields of {@code Tessellator.instance} (5.5). Only the render-thread instance does that.
     */
    public static void syncPublicFields(Tessellator t) {
        RENDER_TESS.mirrorTo(t);
    }

    /**
     * Makes {@code t} the instance of the calling thread when the calling thread is a {@link MeshWorkerThread} whose instance is
     * {@code t} already (nothing to do), and rejects every other binding: threads are bound at construction ({@code MeshWorkerThread}),
     * a ThreadLocal would defeat the purpose of {@link #current()}.
     */
    public static void bindCurrentThread(NativeTess t) {
        if (Thread.currentThread() instanceof MeshWorkerThread w && w.tess() == t) {
            return;
        }
        throw new IllegalStateException("A NativeTess is bound to a thread at construction (MeshWorkerThread); thread "
                + Thread.currentThread().getName() + " cannot be rebound");
    }

    /** Records the real render thread (NativeRenderer.init). */
    public static void setRenderThread(Thread t) {
        renderThread = t;
    }

    /** True on the recorded render thread; before one is recorded every non-worker thread counts as the render thread. */
    public static boolean isRenderThread() {
        Thread r = renderThread;
        Thread c = Thread.currentThread();
        return r != null ? c == r : !(c instanceof MeshWorkerThread);
    }
}

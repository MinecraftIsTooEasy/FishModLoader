package vkbench;

/**
 * Camera paths of the four-dimension route (7.3). Every pose is a pure function of the elapsed client tick of the segment, never of wall-clock time, so every build
 * of the comparison flies the identical path. Coordinates are relative to the segment origin (x0, z0); yaw follows Minecraft's convention (0 = +Z, -90 = +X).
 */
public final class BenchRoute {
    /** Ticks per second of the game clock. */
    public static final int TPS = 20;

    private BenchRoute() {
    }

    /** One camera path: {@code pose} fills {x, y, z, yaw, pitch} for a tick of the segment. */
    public interface Path {
        int ticks();

        void pose(int tick, double[] out);

        /** Short description for the summary. */
        String describe();
    }

    /**
     * Straight flight along +X from the origin at {@code speed} blocks per tick; the height is {@code y0} blinearly moving to {@code y1} over the segment; every
     * {@code turnEveryBlocks} blocks the camera turns once around (360 degrees over {@code turnTicks} ticks) while it keeps flying.
     */
    public static Path flight(double x0, double z0, int ticks, double speed, double y0, double y1, float pitch, double turnEveryBlocks, int turnTicks) {
        return new Path() {
            @Override
            public int ticks() {
                return ticks;
            }

            @Override
            public void pose(int tick, double[] out) {
                double dist = speed * tick;
                out[0] = x0 + dist;
                out[1] = y0 + (y1 - y0) * ((double) tick / Math.max(1, ticks));
                out[2] = z0;
                float yaw = -90.0F;
                if (turnEveryBlocks > 0) {
                    long turnIndex = (long) Math.floor(dist / turnEveryBlocks);
                    if (turnIndex >= 1) {
                        double sinceTurn = (dist - turnIndex * turnEveryBlocks) / speed;
                        if (sinceTurn < turnTicks) {
                            yaw += (float) (360.0 * sinceTurn / turnTicks);
                        }
                    }
                }
                out[3] = yaw;
                out[4] = pitch;
            }

            @Override
            public String describe() {
                return String.format(java.util.Locale.ROOT, "flight %d ticks, %.2f blocks/tick, y %.0f->%.0f%s", ticks, speed, y0, y1,
                    turnEveryBlocks > 0 ? ", 360 turn every " + (int) turnEveryBlocks + " blocks" : "");
            }
        };
    }

    /** Standing still at (x, y, z) looking along {@code yaw}/{@code pitch}. */
    public static Path idle(double x, double y, double z, float yaw, float pitch, int ticks) {
        return new Path() {
            @Override
            public int ticks() {
                return ticks;
            }

            @Override
            public void pose(int tick, double[] out) {
                out[0] = x;
                out[1] = y;
                out[2] = z;
                out[3] = yaw;
                out[4] = pitch;
            }

            @Override
            public String describe() {
                return String.format(java.util.Locale.ROOT, "idle %d ticks at %.0f/%.0f/%.0f", ticks, x, y, z);
            }
        };
    }
}

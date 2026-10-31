package net.xiaoyu233.fml.vulkan.testbot;

/**
 * What the scripted world offers to capture scenes and package commands (implemented by the bot script, published through {@link TestBot#stage()}): the
 * deterministic test world of seed 20261027, a creative flying player and a block museum on a platform. All methods are for the bot script thread.
 */
public interface Stage {
    /** Spawn column (x, z) of the player when the world was entered. */
    double[] spawn();

    /** Builds the block museum once and returns its centre {x, y of the platform surface, z}. */
    double[] ensureMuseum() throws Exception;

    /** Server side teleport plus pinned client pose; returns false when the client did not reach the position in time. */
    boolean place(double x, double feetY, double z, float yaw, float pitch) throws Exception;

    void look(float yaw, float pitch) throws Exception;

    /** Sets the adjusted time of day (MITE clock: sunrise 5000, noon 12000, sunset 19000, midnight 0) and waits until the client shows it. */
    void setTimeOfDay(long adjusted) throws Exception;

    /** Creative flying invulnerable player, no daylight cycle / mob spawning (and the frozen-world server setup in frozen mode). */
    void prepPlayer() throws Exception;

    /** Waits until the rebuild queue stayed empty / quiet; returns the seconds it took. */
    double settle(int minTicks, int quietTicks, int maxTicks) throws Exception;

    /** Waits until {@code frames} consecutive frames passed without a section rebuild (chunk update counter unchanged, queue empty when known), at most {@code maxFrames}. */
    boolean quietFrames(int frames, int maxFrames) throws Exception;
}

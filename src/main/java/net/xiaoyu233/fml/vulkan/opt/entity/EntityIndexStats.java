package net.xiaoyu233.fml.vulkan.opt.entity;

import java.util.concurrent.atomic.LongAdder;

/** Runtime switches and statistics of the entity query optimisation (R7). Not a mixin class: safe to reference from mixins. */
public final class EntityIndexStats {
    /** fml.vulkan.opt.entity.index (default true): use the optimised chunk scan. false = vanilla scan (still timed, for baselines). */
    public static final boolean INDEX = !"false".equalsIgnoreCase(System.getProperty("fml.vulkan.opt.entity.index"));
    /** fml.vulkan.opt.entity.verify=true: also run the vanilla scan per chunk and log mismatches. */
    public static final boolean VERIFY = "true".equalsIgnoreCase(System.getProperty("fml.vulkan.opt.entity.verify"));

    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder NANOS = new LongAdder();
    private static final LongAdder CHECKS = new LongAdder();
    private static final LongAdder MISMATCHES = new LongAdder();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            long c = CALLS.sum();
            double mean = c == 0 ? 0.0 : NANOS.sum() / 1.0e6 / c;
            System.out.println("[fml-opt-entity] getEntitiesWithinAABB calls=" + c + " meanMs=" + String.format("%.6f", mean)
                + " totalMs=" + String.format("%.3f", NANOS.sum() / 1.0e6) + " index=" + INDEX
                + " verify=" + VERIFY + " checks=" + CHECKS.sum() + " mismatches=" + MISMATCHES.sum());
        }, "fml-opt-entity-stats"));
    }

    private EntityIndexStats() { }

    public static void record(long startNanos) {
        CALLS.increment();
        NANOS.add(System.nanoTime() - startNanos);
    }

    public static void check(boolean equal, String what, int expected, int actual) {
        CHECKS.increment();
        if (!equal) {
            MISMATCHES.increment();
            System.err.println("[fml-opt-entity] MISMATCH in " + what + ": vanilla=" + expected + " indexed=" + actual);
        }
    }
}

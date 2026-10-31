package vkbench;

import net.fabricmc.api.ClientModInitializer;

/** Entry point of the dev-only bench mod. Does nothing unless -Dfml.bench=true (runBench / benchBaseline). */
public class BenchInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (Bench.ENABLED) {
            System.out.println(Bench.PREFIX + "bench mod active, tag " + Bench.TAG + ", build " + Bench.BUILD);
        }
    }
}

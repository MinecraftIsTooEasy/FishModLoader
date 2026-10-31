package net.xiaoyu233.fml.vulkan.testbot.cmd.nr14;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/** {@code mapInline [frames]}: holds a map for N frames and asserts that {@code FrameStats.inlineCopies} stayed 0 in every one of them. */
final class MapInlineCommand implements DevCommand {
    @Override
    public String name() {
        return "mapInline";
    }

    @Override
    public String description() {
        return "hold a map for N frames (default 120): FrameStats.inlineCopies must be 0 in every frame";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        int frames = args.length > 0 ? Math.max(1, Integer.parseInt(args[0])) : 120;
        TestBot bot = TestBot.get();
        Scene.stage(bot);
        Scene.give(bot, 0, "map");
        bot.stage().look(0.0F, 12.0F);
        bot.ticks(10);
        AtomicInteger seen = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        Consumer<FrameStats.Frame> l = f -> {
            seen.incrementAndGet();
            total.addAndGet(f.inlineCopies());
            max.accumulateAndGet(f.inlineCopies(), Math::max);
        };
        FrameStats.addListener(l);
        try {
            bot.frames(frames);
        } finally {
            FrameStats.removeListener(l);
        }
        section.check(seen.get() > 0, "mapInline frames observed", "no frame statistics arrived");
        section.check(total.get() == 0, "mapInline inline copies", "total " + total.get() + ", max per frame " + max.get() + " over " + seen.get() + " frames");
        section.info("mapInline", seen.get() + " frames observed while holding a map");
    }
}

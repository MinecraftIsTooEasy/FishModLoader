package net.xiaoyu233.fml.vulkan.testbot.cmd.nr07;

import net.xiaoyu233.fml.vulkan.render.frame.FramePacer;
import net.xiaoyu233.fml.vulkan.render.frame.FrameStats;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code nr07.loopOrder} (PAC-01): runs frames and checks through {@link FrameStats} that every frame presents before the limiter
 * sleeps. Per frame N the order must be: present end of N, then the limiter start (the deferred stamp taken by
 * {@link FramePacer#runDeferredAfterPresent()} after {@link FramePacer#endFrame()}, before {@link FramePacer#limit(int)}), then the
 * input poll of N+1. The limiter start is not exported by FramePacer, so it is sampled here: a poll probe of each frame queues a stamp
 * through {@link FramePacer#defer(Runnable)}, which runs after that frame's present and before its limiter.
 */
final class LoopOrderCommand implements DevCommand {
    /** Frames the probe stays armed for; the stamps of the analysed frames are all taken inside this window. */
    private static final int MARGIN = 2;

    /** Limiter-start stamp per ring slot, and the frame index it belongs to (guards against stale slots). */
    private static final long[] LIMIT_AT = new long[FrameStats.RING];
    private static final long[] LIMIT_FOR = new long[FrameStats.RING];
    private static volatile int armedPolls;

    /** Runs after the present of the frame it stamps: {@code frameCount()} already counts that frame. */
    private static final Runnable STAMP = () -> {
        long idx = FrameStats.frameCount() - 1;
        if (idx >= 0) {
            int slot = (int) (idx & (FrameStats.RING - 1));
            LIMIT_FOR[slot] = idx;
            LIMIT_AT[slot] = System.nanoTime();
        }
    };

    @Override
    public String name() {
        return "nr07.loopOrder";
    }

    @Override
    public String description() {
        return "runs frames and asserts present end precedes the limiter start and the next poll (FrameStats / FramePacer)";
    }

    @Override
    public void run(Report.Section s, String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        TestBot bot = TestBot.get();
        if (bot == null) {
            s.warn("loopOrder", "test bot is not running");
            return;
        }
        for (int i = 0; i < LIMIT_FOR.length; i++) {
            LIMIT_FOR[i] = -1L;
            LIMIT_AT[i] = 0L;
        }
        armedPolls = n + MARGIN;
        FramePacer.probeNextPoll(LoopOrderCommand::pollProbe);
        try {
            bot.frames(n);
        } finally {
            armedPolls = 0;
            FramePacer.probeNextPoll(null);
        }
        s.check(FramePacer.loopRunning(), "loop uses FramePacer.pollInput", "FramePacer.loopRunning() is false");
        long count = FrameStats.frameCount();
        int usable = (int) Math.min(count - 2, Math.min(n, FrameStats.RING - 2));
        int bad = 0;
        int considered = 0;
        int stamped = 0;
        int badStamp = 0;
        long sleepSum = 0;
        int sleepCount = 0;
        for (int i = 0; i < usable; i++) {
            long idx = count - 2 - i;
            long presentEnd = FrameStats.presentEndNanos(idx);
            long nextPoll = FrameStats.pollNanos(idx + 1);
            if (presentEnd == 0L || nextPoll == 0L) {
                continue;
            }
            considered++;
            if (presentEnd > nextPoll) {
                bad++;
            }
            int slot = (int) (idx & (FrameStats.RING - 1));
            if (LIMIT_FOR[slot] != idx || LIMIT_AT[slot] == 0L) {
                continue;
            }
            stamped++;
            long limitStart = LIMIT_AT[slot];
            if (presentEnd > limitStart || limitStart > nextPoll) {
                badStamp++;
            } else {
                sleepSum += nextPoll - limitStart;
                sleepCount++;
            }
        }
        s.check(considered > 0, "frames with timestamps", "no frame carried poll/present timestamps");
        s.check(bad == 0, "present precedes next poll", bad + " of " + considered + " frames presented after the next poll");
        s.check(stamped > 0, "limiter-start stamps taken", "no frame carried a limiter-start stamp");
        s.check(badStamp == 0, "present precedes limiter start precedes next poll",
                badStamp + " of " + stamped + " frames broke present < limiter start < next poll (deferred stamp runs after endFrame)");
        long lastEnd = FramePacer.lastPresentEndNanos();
        s.check(lastEnd != 0L && lastEnd <= System.nanoTime(), "lastPresentEndNanos set", "lastPresentEndNanos=" + lastEnd);
        s.info("loopOrder", stamped + " frames stamped, mean limiter-start to next-poll gap "
                + (sleepCount > 0 ? (sleepSum / sleepCount) / 1000 : 0) + " us (the limiter sleep when a frame limit is set,"
                + " otherwise only loop overhead; this command does not force a limit)");
    }

    /** Poll probe (render thread, start of {@code pollInput}): queues this frame's limiter stamp and re-arms itself. */
    private static void pollProbe() {
        if (armedPolls <= 0) {
            return;
        }
        armedPolls--;
        FramePacer.probeNextPoll(LoopOrderCommand::pollProbe);
        FramePacer.defer(STAMP);
    }
}

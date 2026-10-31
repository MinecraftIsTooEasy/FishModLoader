package net.xiaoyu233.fml.vulkan.testbot.cmd.nr04;

import net.xiaoyu233.fml.vulkan.core.RenderCore;
import net.xiaoyu233.fml.vulkan.render.texture.Textures;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.texture.TextureTestSupport;

/**
 * {@code mapUploadTest [frames]}: re-uploads a 128x128 texture three times per frame while sampling it in between (what a held map
 * does: render, update, render ...). Gate: 0 inline copies, and the descriptor set captured after each upload shows exactly the pixels
 * of that upload (read back from the backing image behind the set), while the sets of the earlier uploads of the frame still show theirs.
 */
final class MapUploadTestCommand implements DevCommand {
    private static final int SIZE = 128;

    @Override
    public String name() {
        return "mapUploadTest";
    }

    @Override
    public String description() {
        return "re-upload a 128x128 texture 3x per frame while sampling it between: 0 inline copies, per-draw contents correct";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        int frames = args.length > 0 ? Math.max(1, Integer.parseInt(args[0])) : 20;
        int id = RenderThreadTasks.call(() -> {
            int t = Textures.create();
            Textures.bind(t);
            Textures.allocate(t, SIZE, SIZE);
            return t;
        }, 30_000);
        int bad = 0;
        int maxInline = 0;
        int maxImages = 0;
        try {
            for (int frame = 0; frame < frames; frame++) {
                final int f = frame;
                int[] result = RenderThreadTasks.call(() -> uploadThriceAndVerify(id, f), 60_000);
                bad += result[0];
                maxInline = Math.max(maxInline, result[1]);
                maxImages = Math.max(maxImages, result[2]);
                Thread.sleep(20);
            }
        } finally {
            RenderThreadTasks.call(() -> {
                Textures.delete(id);
                return null;
            }, 30_000);
        }
        section.check(bad == 0, "mapUploadTest contents", bad + " of " + frames * 3 + " per-draw contents were wrong");
        section.check(maxInline == 0, "mapUploadTest inline copies", "max " + maxInline + " inline copies in a frame");
        section.info("mapUploadTest", frames + " frames x 3 uploads, backing images per texture: " + maxImages);
    }

    /** One simulated frame: upload, sample, upload, sample, upload, sample; returns {wrong, inline copies, backing images}. */
    private static int[] uploadThriceAndVerify(int id, int frame) {
        int wrong = 0;
        int inlineBefore = Textures.inlineCopiesThisFrame();
        long[] sets = new long[3];
        int[][] expected = new int[3][];
        for (int i = 0; i < 3; i++) {
            int[] pixels = pattern(frame * 3 + i);
            expected[i] = pixels;
            Textures.uploadArgb(id, pixels, SIZE, SIZE, 0, 0, false, true);
            sets[i] = Textures.descriptorSet(id); // the draw that samples it: marks the texture used in this frame
        }
        for (int i = 0; i < 3; i++) {
            int[] seen = TextureTestSupport.readBehindSet(id, sets[i]);
            if (seen == null || !java.util.Arrays.equals(seen, expected[i])) {
                wrong++;
            }
        }
        int inline = Textures.inlineCopiesThisFrame() - inlineBefore;
        return new int[] {wrong, inline, TextureTestSupport.imageCount(id)};
    }

    /** A pattern that differs in every texel between uploads. */
    private static int[] pattern(int seed) {
        int[] p = new int[SIZE * SIZE];
        for (int i = 0; i < p.length; i++) {
            int mixed = (i + 1) * 0x9E3779B1 ^ (seed + 1) * 0x85EBCA6B;
            p[i] = mixed | 0xFF000000;
        }
        return p;
    }
}

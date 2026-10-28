package net.xiaoyu233.fml.vulkan.testbot.cmd.nr04;

import java.util.Random;
import net.xiaoyu233.fml.vulkan.render.texture.AtlasOpacity;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;
import net.xiaoyu233.fml.vulkan.texture.AtlasMips;

/**
 * {@code textureUnit}: CPU-only checks of the texture system: the alpha-weighted mip filter (colour of transparent texels never darkens
 * a cutout edge, opaque stays opaque, a uniform image stays uniform, transparent regions stay transparent) and the opacity test.
 */
final class TextureUnitCommand implements DevCommand {
    @Override
    public String name() {
        return "textureUnit";
    }

    @Override
    public String description() {
        return "CPU checks: alpha-weighted atlas mip filter and opacity test";
    }

    @Override
    public void run(Report.Section section, String[] args) {
        int[] uniform = new int[16 * 16];
        java.util.Arrays.fill(uniform, 0xFF336699);
        int[] mip = new int[8 * 8];
        AtlasMips.downsample(uniform, 0, 16, 16, mip);
        boolean same = true;
        for (int v : mip) {
            same &= v == 0xFF336699;
        }
        section.check(same, "uniform opaque image stays uniform", "got a different colour");

        // leaf edge: left half green opaque, right half black transparent: the left mip texel must stay pure green
        int[] edge = new int[2 * 2];
        edge[0] = 0xFF00FF00;
        edge[2] = 0xFF00FF00;
        edge[1] = 0x00000000;
        edge[3] = 0x00000000;
        int[] one = new int[1];
        AtlasMips.downsample(edge, 0, 2, 2, one);
        int a = one[0] >>> 24;
        int g = (one[0] >> 8) & 0xFF;
        int r = (one[0] >> 16) & 0xFF;
        section.check(g == 0xFF && r == 0 && a == 128, "cutout edge keeps its colour (alpha weighted)",
            "argb " + Integer.toHexString(one[0]) + " expected alpha 128 and pure green");

        int[] clear = new int[4];
        AtlasMips.downsample(clear, 0, 2, 2, one);
        section.check(one[0] >>> 24 == 0, "fully transparent stays transparent", "alpha " + (one[0] >>> 24));

        Random rnd = new Random(7);
        int[] noise = new int[32 * 32];
        boolean bounds = true;
        for (int i = 0; i < noise.length; i++) {
            noise[i] = rnd.nextInt();
        }
        int[] reduced = new int[16 * 16];
        AtlasMips.downsample(noise, 0, 32, 32, reduced);
        for (int y = 0; y < 16 && bounds; y++) {
            for (int x = 0; x < 16; x++) {
                int[] px = {noise[(2 * y) * 32 + 2 * x], noise[(2 * y) * 32 + 2 * x + 1], noise[(2 * y + 1) * 32 + 2 * x],
                    noise[(2 * y + 1) * 32 + 2 * x + 1]};
                for (int shift = 0; shift <= 16; shift += 8) {
                    int lo = 255;
                    int hi = 0;
                    for (int p : px) {
                        int c = (p >> shift) & 0xFF;
                        lo = Math.min(lo, c);
                        hi = Math.max(hi, c);
                    }
                    int v = (reduced[y * 16 + x] >> shift) & 0xFF;
                    bounds &= v >= lo && v <= hi;
                }
            }
        }
        section.check(bounds, "random image: every channel within the min/max of its 2x2 block", "a mip texel left the range of its block");

        int[] translucent = {0xFFFFFFFF, 0xFFFFFFFF, 0xFEFFFFFF};
        section.check(AtlasOpacity.allOpaque(new int[] {0xFF000000, 0xFFFFFFFF}, 2), "opaque image is opaque", "reported translucent");
        section.check(!AtlasOpacity.allOpaque(translucent, 3), "alpha 254 is not opaque", "reported opaque");
    }
}

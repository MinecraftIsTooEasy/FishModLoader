package net.xiaoyu233.fml.vulkan.testbot.cmd.nr10;

import java.util.Random;
import net.minecraft.ChunkCache;
import net.minecraft.World;
import net.minecraft.WorldServer;
import net.minecraft.server.MinecraftServer;
import net.xiaoyu233.fml.vulkan.render.mesh.SectionSnapshot;
import net.xiaoyu233.fml.vulkan.testbot.Report;
import net.xiaoyu233.fml.vulkan.testbot.TestBot;
import net.xiaoyu233.fml.vulkan.testbot.cmd.DevCommand;

/**
 * {@code snapCompare} of NR-10: for 200 random sections spread over every loaded server world (overworld, nether, ...) and skylightSubtracted
 * 0..10 (dusk), compares every IBlockAccess method of a {@link SectionSnapshot} with a MITE {@link ChunkCache} (the 18^3 box of
 * WorldRenderer.updateRenderer) for all 18^3 cells. Runs on the server thread, which owns the server worlds.
 */
final class SnapCompareCommand implements DevCommand {
    @Override
    public String name() {
        return "snapCompare";
    }

    @Override
    public String description() {
        return "SectionSnapshot vs ChunkCache over 200 random sections (all dimensions, skylightSubtracted 0..10)";
    }

    @Override
    public void run(Report.Section section, String[] args) throws Exception {
        int sections = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        long[] result = TestBot.get().server(() -> compare(section, sections));
        section.check(result[1] == 0, "snapCompare " + result[0] + " sections, " + result[2] + " comparisons", result[1] + " mismatches");
    }

    private static long[] compare(Report.Section report, int sections) {
        MinecraftServer srv = MinecraftServer.getServer();
        Random rnd = new Random(0x5EC7104L);
        long mismatches = 0;
        long compared = 0;
        int done = 0;
        for (int i = 0; i < sections; i++) {
            WorldServer w = srv.worldServers[i % srv.worldServers.length];
            if (w == null) {
                continue;
            }
            int x0 = (rnd.nextInt(13) - 6) * 16;
            int z0 = (rnd.nextInt(13) - 6) * 16;
            int y0 = rnd.nextInt(16) * 16;
            int saved = w.skylightSubtracted;
            w.skylightSubtracted = i % 3 == 0 ? 0 : 1 + rnd.nextInt(10);
            SectionSnapshot s = null;
            try {
                s = SectionSnapshot.capture(w, x0, y0, z0);
                ChunkCache c = new ChunkCache(w, x0 - 1, y0 - 1, z0 - 1, x0 + 16, y0 + 16, z0 + 16, 1);
                long[] r = compareOne(report, w, s, c, x0, y0, z0, rnd);
                compared += r[0];
                mismatches += r[1];
                done++;
            } finally {
                w.skylightSubtracted = saved;
                if (s != null) {
                    s.release();
                }
            }
        }
        return new long[] {done, mismatches, compared};
    }

    private static long[] compareOne(Report.Section rep, World w, SectionSnapshot s, ChunkCache c, int x0, int y0, int z0, Random rnd) {
        long n = 0;
        long bad = 0;
        bad += eq(rep, "extendedLevels", s.extendedLevelsInChunkCache(), c.extendedLevelsInChunkCache(), x0, y0, z0, 0, 0, 0);
        n++;
        bad += eq(rep, "getHeight", s.getHeight(), c.getHeight(), x0, y0, z0, 0, 0, 0);
        bad += eq(rep, "skylightSubtracted", s.skylightSubtracted(), w.skylightSubtracted, x0, y0, z0, 0, 0, 0);
        bad += eq(rep, "hasNoSky", s.hasNoSky(), w.provider.hasNoSky, x0, y0, z0, 0, 0, 0);
        bad += eq(rep, "lightTable", java.util.Arrays.equals(s.lightBrightnessTable(), w.provider.lightBrightnessTable), true, x0, y0, z0, 0, 0, 0);
        n += 3;
        for (int y = y0 - 1; y <= y0 + 16; y++) {
            for (int z = z0 - 1; z <= z0 + 16; z++) {
                for (int x = x0 - 1; x <= x0 + 16; x++) {
                    n += 20;
                    bad += eq(rep, "getBlockId", s.getBlockId(x, y, z), c.getBlockId(x, y, z), x, y, z, 0, 0, 0);
                    bad += eq(rep, "getBlock", s.getBlock(x, y, z) == c.getBlock(x, y, z), true, x, y, z, 0, 0, 0);
                    bad += eq(rep, "getBlockMetadata", s.getBlockMetadata(x, y, z), c.getBlockMetadata(x, y, z), x, y, z, 0, 0, 0);
                    bad += eq(rep, "getBlockMaterial", s.getBlockMaterial(x, y, z) == c.getBlockMaterial(x, y, z), true, x, y, z, 0, 0, 0);
                    bad += eq(rep, "isAirBlock", s.isAirBlock(x, y, z), c.isAirBlock(x, y, z), x, y, z, 0, 0, 0);
                    bad += eq(rep, "isBlockNormalCube", s.isBlockNormalCube(x, y, z), c.isBlockNormalCube(x, y, z), x, y, z, 0, 0, 0);
                    bad += eq(rep, "isBlockStandardFormOpaqueCube", s.isBlockStandardFormOpaqueCube(x, y, z), c.isBlockStandardFormOpaqueCube(x, y, z), x, y, z, 0, 0, 0);
                    bad += eq(rep, "isBlockSolid", s.isBlockSolid(x, y, z), c.isBlockSolid(x, y, z), x, y, z, 0, 0, 0);
                    bad += eq(rep, "isBlockTopFlatAndSolid", s.isBlockTopFlatAndSolid(x, y, z), c.isBlockTopFlatAndSolid(x, y, z), x, y, z, 0, 0, 0);
                    bad += eq(rep, "getLightBrightness", Float.floatToIntBits(s.getLightBrightness(x, y, z)), Float.floatToIntBits(c.getLightBrightness(x, y, z)), x, y, z, 0, 0, 0);
                    int min = rnd.nextInt(16);
                    bad += eq(rep, "getBrightness", Float.floatToIntBits(s.getBrightness(x, y, z, min)), Float.floatToIntBits(c.getBrightness(x, y, z, min)), x, y, z, min, 0, 0);
                    bad += eq(rep, "getLightBrightnessForSkyBlocks", s.getLightBrightnessForSkyBlocks(x, y, z, min), c.getLightBrightnessForSkyBlocks(x, y, z, min), x, y, z, min, 0, 0);
                    bad += eq(rep, "getLightBrightnessForSkyBlocks0", s.getLightBrightnessForSkyBlocks(x, y, z, 0), c.getLightBrightnessForSkyBlocks(x, y, z, 0), x, y, z, 0, 0, 0);
                    for (int d = 0; d < 6; d++) {
                        n++;
                        bad += eq(rep, "isBlockProvidingPowerTo", s.isBlockProvidingPowerTo(x, y, z, d), c.isBlockProvidingPowerTo(x, y, z, d), x, y, z, d, 0, 0);
                    }
                    if (x >= x0 && x < x0 + 16 && y >= y0 && y < y0 + 16 && z >= z0 && z < z0 + 16 && c.getBlock(x, y, z) != null
                        && c.getBlock(x, y, z).hasTileEntity()) {
                        n++;
                        bad += eq(rep, "getBlockTileEntity", s.getBlockTileEntity(x, y, z) == c.getBlockTileEntity(x, y, z), true, x, y, z, 0, 0, 0);
                    }
                    if (y == y0 && (x & 3) == 0 && (z & 3) == 0) {
                        n++;
                        bad += eq(rep, "getBiomeGenForCoords", s.getBiomeGenForCoords(x, z) == c.getBiomeGenForCoords(x, z), true, x, y, z, 0, 0, 0);
                    }
                }
            }
        }
        return new long[] {n, bad};
    }

    private static int eq(Report.Section rep, String what, Object a, Object b, int x, int y, int z, int p, int q, int r) {
        if (a.equals(b)) {
            return 0;
        }
        rep.fail("snapCompare " + what, "(" + x + "," + y + "," + z + ") arg " + p + ": snapshot=" + a + " chunkCache=" + b);
        return 1;
    }
}

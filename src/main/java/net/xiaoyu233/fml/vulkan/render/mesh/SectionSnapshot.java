package net.xiaoyu233.fml.vulkan.render.mesh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.BiomeGenBase;
import net.minecraft.Block;
import net.minecraft.Chunk;
import net.minecraft.ChunkPosition;
import net.minecraft.EnumDirection;
import net.minecraft.ExtendedBlockStorage;
import net.minecraft.IBlockAccess;
import net.minecraft.Material;
import net.minecraft.NibbleArray;
import net.minecraft.TileEntity;
import net.minecraft.TileEntityRenderer;
import net.minecraft.Vec3Pool;
import net.minecraft.World;

/**
 * Copy of the blocks, light and biomes around one section, readable on a mesh worker (design 2.3 #3-#7, 5.8). {@link #capture} runs on
 * the render thread and copies the 20^3 region {@code origin-2 .. origin+17} from the live chunks; every {@link IBlockAccess} method then
 * reproduces the arithmetic of the decompiled {@code ChunkCache} on the copy.
 *
 * <p><b>Thread rule:</b> a worker must never call into {@link #getWorld()} (it only exists so that code can test identity, e.g. the
 * flower-pot override); everything else a worker needs is in the snapshot. A snapshot is used by one task at a time; the memoised colour
 * averages are not synchronised.
 *
 * <p>Reads outside the 20^3 region return air / darkness (ChunkCache would throw there); {@code getBiomeGenForCoords} clamps to the region.
 */
public final class SectionSnapshot implements IBlockAccess {
    private static final int SIDE = 20;
    private static final int BORDER = 2;
    private static final int CELLS = SIDE * SIDE * SIDE;
    private static final int COLUMNS = SIDE * SIDE;
    private static final int MAX_POOLED = 512;

    /** sky byte: low nibble raw sky light (present storage) or 15/0 canBlockSeeTheSky (absent storage). */
    private static final int ABSENT = 0x10;
    /** sky byte: chunk missing or EmptyChunk (every light query reports 0). */
    private static final int EMPTY = 0x20;

    private static final EnumDirection[] DIRECTIONS = EnumDirection.values();
    private static final int[] DX = {0, 0, 0, 0, 1, -1};
    private static final int[] DY = {1, -1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1, 0, 0};

    private static final ConcurrentLinkedQueue<SectionSnapshot> POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger POOL_SIZE = new AtomicInteger();

    private final short[] ids = new short[CELLS];
    private final byte[] meta = new byte[CELLS];
    private final byte[] blockLight = new byte[CELLS];
    private final byte[] sky = new byte[CELLS];
    private final boolean[] domain = new boolean[COLUMNS];
    private final byte[] biome = new byte[COLUMNS];
    private final int[] grass = new int[256];
    private final int[] foliage = new int[256];
    private final int[] water = new int[256];
    private final float[] lightTable = new float[16];
    /** Captured tile entities keyed by cell index; cells are appended in ascending order, so the arrays stay sorted. */
    private int[] teCells = new int[64];
    private TileEntity[] teRefs = new TileEntity[64];
    private int teCount;
    /** 3x3 chunk window of the 20^3 region, index (cx - cxMin) * 3 + (cz - czMin); reused across captures. */
    private final Chunk[] chunks = new Chunk[9];
    private final List<TileEntity> special = new ArrayList<>();
    private final Vec3Pool vecPool = new Vec3Pool(300, 2000);

    private World world;
    private int originX;
    private int originY;
    private int originZ;
    private int skylightSubtracted;
    private boolean hasNoSky;
    private boolean allAir;
    private boolean sawSkyLight;
    private int epoch;

    private SectionSnapshot() {
    }

    /** Render thread only. */
    public static SectionSnapshot capture(World world, int originX, int originY, int originZ) {
        SectionSnapshot s = POOL.poll();
        if (s == null) {
            s = new SectionSnapshot();
        } else {
            POOL_SIZE.decrementAndGet();
        }
        s.fill(world, originX, originY, originZ);
        return s;
    }

    private void fill(World w, int x0, int y0, int z0) {
        this.world = w;
        this.originX = x0;
        this.originY = y0;
        this.originZ = z0;
        this.epoch = MeshWorkers.epoch();
        this.skylightSubtracted = w.skylightSubtracted;
        float[] table = w.provider.lightBrightnessTable;
        System.arraycopy(table, 0, this.lightTable, 0, 16);
        this.hasNoSky = w.provider.hasNoSky;
        this.sawSkyLight = false;
        this.clearTileEntities();
        this.special.clear();
        Arrays.fill(this.ids, (short) 0);
        Arrays.fill(this.meta, (byte) 0);
        Arrays.fill(this.blockLight, (byte) 0);
        Arrays.fill(this.sky, (byte) 0);
        Arrays.fill(this.grass, -1);
        Arrays.fill(this.foliage, -1);
        Arrays.fill(this.water, -1);

        int bx = x0 - BORDER;
        int by = y0 - BORDER;
        int bz = z0 - BORDER;

        // ChunkCache(world, x0-1, y0-1, z0-1, x0+16, y0+16, z0+16, 1): isEmpty from the chunks of the unexpanded 18^3 box
        this.allAir = true;
        int cxMin = bx >> 4;
        int czMin = bz >> 4;
        int cxMax = (bx + SIDE - 1) >> 4;
        int czMax = (bz + SIDE - 1) >> 4;
        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                this.chunks[(cx - cxMin) * 3 + (cz - czMin)] = w.getChunkFromChunkCoords(cx, cz);
            }
        }
        for (int cx = (x0 - 1) >> 4; cx <= (x0 + 16) >> 4 && this.allAir; cx++) {
            for (int cz = (z0 - 1) >> 4; cz <= (z0 + 16) >> 4; cz++) {
                Chunk c = this.chunks[(cx - cxMin) * 3 + (cz - czMin)];
                if (c != null && !c.getAreLevelsEmpty(y0 - 1, y0 + 16)) {
                    this.allAir = false;
                    break;
                }
            }
        }

        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                this.copyChunk(this.chunks[(cx - cxMin) * 3 + (cz - czMin)], cx, cz, bx, by, bz);
            }
        }

        for (int lz = 0; lz < SIDE; lz++) {
            for (int lx = 0; lx < SIDE; lx++) {
                int x = bx + lx;
                int z = bz + lz;
                int col = lz * SIDE + lx;
                this.domain[col] = w.isWithinBlockDomain(x, z);
                this.biome[col] = (byte) w.getBiomeGenForCoords(x, z).biomeID;
            }
        }

        // Approximation of MITE's static Chunk.isLit. MITE sets it only for the cells that getBlockLightValue is actually asked
        // about while meshing (all chunks, border included), so this scan can differ from it. It scans the inner 16x16 columns over
        // all 20 Y levels where sky storage is present (ABSENT/EMPTY bytes are skipped). WorldRenderer.isChunkLit has no reader in
        // MITE, so the difference is not observable yet. If a reader is added, set the flag from the worker inside the same
        // savedLight/lightValueExt sampling paths, including the border.
        if (!this.hasNoSky) {
            for (int ly = 0; ly < SIDE && !this.sawSkyLight; ly++) {
                for (int lz = BORDER; lz < BORDER + 16 && !this.sawSkyLight; lz++) {
                    int base = (ly * SIDE + lz) * SIDE;
                    for (int lx = BORDER; lx < BORDER + 16; lx++) {
                        int v = this.sky[base + lx];
                        if ((v & (ABSENT | EMPTY)) == 0 && (v & 15) > 0) {
                            this.sawSkyLight = true;
                            break;
                        }
                    }
                }
            }
        }

        this.captureTileEntities(cxMin, czMin, bx, by, bz);
    }

    private void copyChunk(Chunk chunk, int cx, int cz, int bx, int by, int bz) {
        int xs = Math.max(bx, cx << 4);
        int xe = Math.min(bx + SIDE - 1, (cx << 4) + 15);
        int zs = Math.max(bz, cz << 4);
        int ze = Math.min(bz + SIDE - 1, (cz << 4) + 15);
        boolean empty = chunk == null || chunk.isEmpty();
        ExtendedBlockStorage[] storages = empty ? null : chunk.getBlockStorageArray();
        int[] heightMap = empty ? null : chunk.heightMap;
        for (int ly = 0; ly < SIDE; ly++) {
            int y = by + ly;
            if (y < 0 || y > 255) {
                continue;
            }
            ExtendedBlockStorage ebs = empty ? null : storages[y >> 4];
            int yl = y & 15;
            for (int z = zs; z <= ze; z++) {
                int rowBase = (ly * SIDE + (z - bz)) * SIDE - bx;
                int zl = z & 15;
                if (empty) {
                    for (int x = xs; x <= xe; x++) {
                        this.sky[rowBase + x] = EMPTY;
                    }
                } else if (ebs == null) {
                    for (int x = xs; x <= xe; x++) {
                        this.sky[rowBase + x] = (byte) (ABSENT | (y >= heightMap[zl << 4 | (x & 15)] ? 15 : 0));
                    }
                } else {
                    byte[] lsb = ebs.blockLSBArray;
                    boolean hasMsb = ebs.blockMSBArray != null;
                    byte[] md = ebs.getMetadataArray().data;
                    byte[] bl = ebs.getBlocklightArray().data;
                    NibbleArray skyArr = ebs.getSkylightArray();
                    byte[] sk = skyArr == null ? null : skyArr.data;
                    for (int x = xs; x <= xe; x++) {
                        int xl = x & 15;
                        int n = yl << 8 | zl << 4 | xl;
                        int cell = rowBase + x;
                        // MITE's Chunk.getBlockID is authoritative whenever a MSB array exists
                        this.ids[cell] = (short) (hasMsb ? chunk.getBlockID(xl, y, zl) : lsb[n] & 255);
                        this.meta[cell] = (byte) nibble(md, n);
                        this.blockLight[cell] = (byte) nibble(bl, n);
                        this.sky[cell] = (byte) (sk == null ? 0 : nibble(sk, n));
                    }
                }
            }
        }
    }

    private static int nibble(byte[] data, int n) {
        return (n & 1) == 0 ? data[n >> 1] & 15 : data[n >> 1] >> 4 & 15;
    }

    private void captureTileEntities(int cxMin, int czMin, int bx, int by, int bz) {
        if (this.allAir) {
            return;
        }
        for (int ly = 0; ly < SIDE; ly++) {
            int y = by + ly;
            if (y < 0 || y > 255) {
                continue;
            }
            boolean innerY = ly >= BORDER && ly < BORDER + 16;
            for (int lz = 0; lz < SIDE; lz++) {
                boolean innerZ = lz >= BORDER && lz < BORDER + 16;
                for (int lx = 0; lx < SIDE; lx++) {
                    int cell = (ly * SIDE + lz) * SIDE + lx;
                    int id = this.ids[cell];
                    if (id <= 0) {
                        continue;
                    }
                    Block block = Block.blocksList[id];
                    if (block == null || !block.hasTileEntity()) {
                        continue;
                    }
                    int x = bx + lx;
                    int z = bz + lz;
                    Chunk chunk = this.chunks[((x >> 4) - cxMin) * 3 + ((z >> 4) - czMin)];
                    if (chunk == null || chunk.isEmpty()) {
                        continue;
                    }
                    TileEntity te;
                    if (innerY && innerZ && lx >= BORDER && lx < BORDER + 16) {
                        te = chunk.getChunkBlockTileEntity(x & 15, y, z & 15);
                        if (te != null && TileEntityRenderer.instance.hasSpecialRenderer(te)) {
                            this.special.add(te);
                        }
                    } else {
                        te = (TileEntity) chunk.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
                        if (te != null && te.isInvalid()) {
                            te = null;
                        }
                    }
                    if (te != null) {
                        this.addTileEntity(cell, te);
                    }
                }
            }
        }
    }

    private void clearTileEntities() {
        Arrays.fill(this.teRefs, 0, this.teCount, null);
        this.teCount = 0;
    }

    /** Called in ascending cell order by captureTileEntities (y, z, x loop), so the arrays stay sorted for binary search. */
    private void addTileEntity(int cell, TileEntity te) {
        if (this.teCount == this.teRefs.length) {
            int n = this.teCount * 2;
            this.teCells = Arrays.copyOf(this.teCells, n);
            this.teRefs = Arrays.copyOf(this.teRefs, n);
        }
        this.teCells[this.teCount] = cell;
        this.teRefs[this.teCount] = te;
        this.teCount++;
    }

    private TileEntity findTileEntity(int cell) {
        int i = Arrays.binarySearch(this.teCells, 0, this.teCount, cell);
        return i < 0 ? null : this.teRefs[i];
    }

    // ---- accessors for VisGraph / SectionMeshBuilder (inner 16^3, local coordinates 0..15) ----

    public int originX() {
        return this.originX;
    }

    public int originY() {
        return this.originY;
    }

    public int originZ() {
        return this.originZ;
    }

    /** 12-bit block id of the inner cell (lx, ly, lz in 0..15; the 2-wide border is reachable with -2..17). */
    int rawId(int lx, int ly, int lz) {
        return this.ids[((ly + BORDER) * SIDE + lz + BORDER) * SIDE + lx + BORDER];
    }

    int rawMetadata(int lx, int ly, int lz) {
        return this.meta[((ly + BORDER) * SIDE + lz + BORDER) * SIDE + lx + BORDER];
    }

    /** true when the section's ChunkCache would report {@code extendedLevelsInChunkCache()} (nothing to mesh). */
    boolean allAir() {
        return this.allAir;
    }

    int biomeId(int x, int z) {
        return this.biome[this.column(x, z)] & 255;
    }

    private int column(int x, int z) {
        int lx = Math.min(SIDE - 1, Math.max(0, x - this.originX + BORDER));
        int lz = Math.min(SIDE - 1, Math.max(0, z - this.originZ + BORDER));
        return lz * SIDE + lx;
    }

    /** Called by the worker after each task. */
    void cleanPool() {
        this.vecPool.clear();
    }

    // ---- public API ----

    public void overrideMetadata(int x, int y, int z, int metadata) {
        int cell = this.cell(x, y, z);
        if (cell >= 0) {
            this.meta[cell] = (byte) metadata;
        }
    }

    public boolean sawSkyLight() {
        return this.sawSkyLight;
    }

    public List<TileEntity> specialTileEntities() {
        return this.special;
    }

    public void release() {
        if (this.world == null) {
            return;
        }
        this.world = null;
        this.clearTileEntities();
        Arrays.fill(this.chunks, null);
        this.special.clear();
        this.vecPool.clear();
        if (POOL_SIZE.get() < MAX_POOLED) {
            POOL_SIZE.incrementAndGet();
            POOL.add(this);
        }
    }

    public int skylightSubtracted() {
        return this.skylightSubtracted;
    }

    public float[] lightBrightnessTable() {
        return this.lightTable;
    }

    public boolean hasNoSky() {
        return this.hasNoSky;
    }

    public int epoch() {
        return this.epoch;
    }

    // ---- cell helpers ----

    /** Cell index of an absolute coordinate, -1 outside the 20^3 region or outside 0..255. */
    private int cell(int x, int y, int z) {
        int lx = x - this.originX + BORDER;
        int ly = y - this.originY + BORDER;
        int lz = z - this.originZ + BORDER;
        if ((lx | ly | lz) < 0 || lx >= SIDE || ly >= SIDE || lz >= SIDE) {
            return -1;
        }
        return (ly * SIDE + lz) * SIDE + lx;
    }

    private boolean inDomain(int x, int z) {
        int lx = x - this.originX + BORDER;
        int lz = z - this.originZ + BORDER;
        if ((lx | lz) < 0 || lx >= SIDE || lz >= SIDE) {
            return false;
        }
        return this.domain[lz * SIDE + lx];
    }

    /** Chunk.getSavedLightValue on the copy; y already clamped to 0..255. */
    private int savedLight(boolean skyType, int x, int y, int z) {
        int cell = this.cell(x, y, z);
        if (cell < 0) {
            return 0;
        }
        int s = this.sky[cell];
        if ((s & EMPTY) != 0) {
            return 0;
        }
        if ((s & ABSENT) != 0) {
            return skyType ? s & 15 : 0;
        }
        if (skyType) {
            return this.hasNoSky ? 0 : s & 15;
        }
        return this.blockLight[cell];
    }

    private int skyBlockTypeBrightness(boolean skyType, int x, int y, int z) {
        if (y < 0) {
            y = 0;
        } else if (y > 255) {
            y = 255;
        }
        if (!this.inDomain(x, z)) {
            return skyType ? 15 : 0;
        }
        if (skyType && this.hasNoSky) {
            return 0;
        }
        int id = this.getBlockId(x, y, z);
        if (!Block.useNeighborBrightness[id]) {
            return this.savedLight(skyType, x, y, z);
        }
        Block block = Block.getBlock(id);
        int metadata = this.getBlockMetadata(x, y, z);
        int brightness = 0;
        for (int o = 0; o < 6; o++) {
            if (block.useNeighborBrightness(metadata, DIRECTIONS[o])) {
                brightness = Math.max(brightness, this.specialBlockBrightness(skyType, x + DX[o], y + DY[o], z + DZ[o]));
                if (brightness > 14) {
                    break;
                }
            }
        }
        return brightness;
    }

    private int specialBlockBrightness(boolean skyType, int x, int y, int z) {
        if (y < 0) {
            y = 0;
        } else if (y > 255) {
            y = 255;
        }
        if (this.inDomain(x, z)) {
            return this.savedLight(skyType, x, y, z);
        }
        return skyType ? 15 : 0;
    }

    private int lightValue(int x, int y, int z) {
        return this.lightValueExt(x, y, z, true);
    }

    private int lightValueExt(int x, int y, int z, boolean useNeighbors) {
        if (!this.inDomain(x, z)) {
            return 15;
        }
        if (useNeighbors) {
            int id = this.getBlockId(x, y, z);
            if (Block.useNeighborBrightness[id]) {
                Block block = Block.getBlock(id);
                int metadata = this.getBlockMetadata(x, y, z);
                int brightness = 0;
                for (int o = 0; o < 6; o++) {
                    if (block.useNeighborBrightness(metadata, DIRECTIONS[o])) {
                        brightness = Math.max(brightness, this.lightValueExt(x + DX[o], y + DY[o], z + DZ[o], false));
                        if (brightness > 14) {
                            break;
                        }
                    }
                }
                return brightness;
            }
        }
        if (y < 0) {
            return 0;
        }
        if (y >= 256) {
            int v = 15 - this.skylightSubtracted;
            return v < 0 ? 0 : v;
        }
        int cell = this.cell(x, y, z);
        if (cell < 0) {
            return 0;
        }
        int s = this.sky[cell];
        if ((s & EMPTY) != 0) {
            return 0;
        }
        if ((s & ABSENT) != 0) {
            return !this.hasNoSky && this.skylightSubtracted < 15 ? 15 - this.skylightSubtracted : 0;
        }
        int v = this.hasNoSky ? 0 : s & 15;
        v -= this.skylightSubtracted;
        int b = this.blockLight[cell];
        if (b > v) {
            v = b;
        }
        return v;
    }

    // ---- IBlockAccess ----

    @Override
    public int getBlockId(int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return 0;
        }
        int cell = this.cell(x, y, z);
        return cell < 0 ? 0 : this.ids[cell];
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        return Block.getBlock(this.getBlockId(x, y, z));
    }

    /** The reference stored at capture (inner cells created as MITE does, border cells existing only) or null. */
    @Override
    public TileEntity getBlockTileEntity(int x, int y, int z) {
        int cell = this.cell(x, y, z);
        return cell < 0 ? null : this.findTileEntity(cell);
    }

    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minBlockLight) {
        int s = this.skyBlockTypeBrightness(true, x, y, z);
        int b = this.skyBlockTypeBrightness(false, x, y, z);
        if (b < minBlockLight) {
            b = minBlockLight;
        }
        return s << 20 | b << 4;
    }

    @Override
    public float getBrightness(int x, int y, int z, int minBlockLight) {
        int v = this.lightValue(x, y, z);
        if (v < minBlockLight) {
            v = minBlockLight;
        }
        return this.lightTable[v];
    }

    @Override
    public float getLightBrightness(int x, int y, int z) {
        return this.lightTable[this.lightValue(x, y, z)];
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return 0;
        }
        int cell = this.cell(x, y, z);
        return cell < 0 ? 0 : this.meta[cell];
    }

    @Override
    public Material getBlockMaterial(int x, int y, int z) {
        int id = this.getBlockId(x, y, z);
        return id == 0 ? Material.air : Block.blocksList[id].blockMaterial;
    }

    @Override
    public Material getBlockMaterial(int blockId) {
        return blockId == 0 ? Material.air : Block.blocksList[blockId].blockMaterial;
    }

    @Override
    public boolean isBlockStandardFormOpaqueCube(int x, int y, int z) {
        return Block.isBlockOpaqueStandardFormCube(this, x, y, z);
    }

    @Override
    public boolean isBlockNormalCube(int x, int y, int z) {
        return Block.isNormalCube(this.getBlockId(x, y, z));
    }

    @Override
    public boolean isAirBlock(int x, int y, int z) {
        return Block.blocksList[this.getBlockId(x, y, z)] == null;
    }

    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return BiomeGenBase.biomeList[this.biome[this.column(x, z)] & 255];
    }

    @Override
    public int getHeight() {
        return 256;
    }

    @Override
    public boolean extendedLevelsInChunkCache() {
        return this.allAir;
    }

    @Override
    public boolean isBlockTopFlatAndSolid(int x, int y, int z) {
        Block block = Block.blocksList[this.getBlockId(x, y, z)];
        return block != null && block.isTopFlatAndSolid(this.getBlockMetadata(x, y, z));
    }

    @Override
    public Vec3Pool getWorldVec3Pool() {
        return this.vecPool;
    }

    @Override
    public int isBlockProvidingPowerTo(int x, int y, int z, int direction) {
        int id = this.getBlockId(x, y, z);
        return id == 0 ? 0 : Block.blocksList[id].isProvidingStrongPower(this, x, y, z, direction);
    }

    /** Identity only: workers must never call into the returned live world. */
    @Override
    public World getWorld() {
        return this.world;
    }

    @Override
    public boolean isBlockSolid(int x, int y, int z) {
        return Block.isBlockSolid(this, x, y, z);
    }
}

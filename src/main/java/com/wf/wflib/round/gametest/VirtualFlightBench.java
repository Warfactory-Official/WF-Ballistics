package com.wf.wflib.round.gametest;

import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.round.terrain.AsyncTerrainSource;
import com.wf.wflib.round.terrain.RegionReader;
import com.wf.wflib.round.terrain.SectionSolidity;
import com.wf.wflib.round.terrain.SolidityDecoder;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Round step cost over the same flat lane loaded vs from disk (cache warm), terrain decode per column (lane +
 * {@code wflib.bench.regions} region file, default {@code world/region/r.0.0.mca} under the run dir). Logs only.
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class VirtualFlightBench {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation ROUND = KineticPresetRegistry.rl("test_virtual_bench");
    /** Chunk-loading twin: no terrain span walk, the pre-1.4.0 per-step ground check. */
    private static final ResourceLocation LOADING = KineticPresetRegistry.rl("test_virtual_bench_loading");
    private static final int CHUNKS = 12;
    private static final int ROUNDS = 500;
    private static final int STEPS = 25;
    private static final int REPS = 7;
    /** Inside the flat world's bottom section: mixed, every voxel looked up. */
    private static final double Y = -58.5;
    private static long sink;
    /** Planted copy of the row, chunks +Z: a region the server never opened. */
    private static final int DISK = 128;
    /** {@link #dispersedFire}: field side, chunks (unwatched rounds step up to 16 x 4 blocks a tick). */
    private static final int GRID = 160;
    private static final int DISPERSED = 500;
    private static final int DISPERSED_TICKS = 20;

    static {
        KineticPresetRegistry.register(KineticPreset.builder(ROUND, null, WarheadRegistry.rl("inert")).speed(4.0)
                .drag(0.01).gravity(0.0).life(400).impactDamage(1.0).caliber(5.56).noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(LOADING, null, WarheadRegistry.rl("inert")).speed(4.0)
                .drag(0.01).gravity(0.0).life(400).impactDamage(1.0).caliber(5.56).build());
    }

    @GameTest(template = "empty", required = false, batch = "wflib_bench", timeoutTicks = 12000)
    public static void virtualFlightCost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int cx0 = (o.getX() - 4000) >> 4;
        int cz = (o.getZ() - 4000) >> 4;
        Vec3 start = new Vec3((cx0 << 4) + 8.5, Y, (cz << 4) + 8.3);
        Vec3 v = new Vec3(4.0, 0.0, 0.0);
        Vec3 disk = start.add(0.0, 0.0, DISK * 16.0);
        double[] loaded = new double[REPS];
        double[] loading = new double[REPS];
        long[] decodes = new long[2];
        helper.startSequence()
                .thenExecute(() -> row(level, cx0, cz, true))
                .thenWaitUntil(() -> helper.assertTrue(rowLoaded(level, cx0, cz) == CHUNKS, "row loading"))
                .thenExecute(() -> {
                    for (int r = 0; r < REPS; r++) {
                        loaded[r] = pass(level, ROUND, start, v);
                        loading[r] = pass(level, LOADING, start, v);
                    }
                    for (int k = 0; k < CHUNKS; k++) {
                        VirtualFlightGameTest.plant(level, new ChunkPos(cx0 + k, cz), new ChunkPos(cx0 + k, cz + DISK));
                    }
                    row(level, cx0, cz, false);
                    AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
                    decodes[0] = terrain.decodes();
                    decodes[1] = terrain.decodeNanos();
                })
                .thenWaitUntil(() -> helper.assertTrue(Rounds.prefetch(level, disk,
                        disk.add((CHUNKS - 1) * 16.0, 0.0, 0.0)), "decoding"))
                .thenExecute(() -> {
                    double[] onDisk = new double[REPS];
                    for (int r = 0; r < REPS; r++) {
                        onDisk[r] = pass(level, ROUND, disk, v);
                    }
                    AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
                    double ml = median(loaded);
                    double md = median(onDisk);
                    LOGGER.info(String.format(Locale.ROOT, "VirtualFlightBench step (%d rounds x %d, median of %d): "
                                    + "loaded %.0f ns (chunk-loading twin %.0f ns), disk %.0f ns (%.2fx); lane decode %.0f us/column; cache %d "
                                    + "columns %d B", ROUNDS, STEPS, REPS, ml, median(loading), md, md / ml,
                            (terrain.decodeNanos() - decodes[1]) / 1000.0 / Math.max(1, terrain.decodes() - decodes[0]),
                            terrain.cachedColumns(), terrain.cachedBytes()));
                    realTerrain(level);
                })
                .thenSucceed();
    }

    /**
     * Unwatched rounds at random headings over a planted {@code GRID}^2-chunk field (farmland top layer: partial
     * sections): per tick round-step wall time, stalled rounds, region stats, cache size and bytes. Logs only.
     */
    @GameTest(template = "empty", required = false, batch = "wflib_bench_dispersed", timeoutTicks = 12000)
    public static void dispersedFire(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        ChunkPos centre = new ChunkPos((o.getX() - 12000) >> 4, (o.getZ() - 12000) >> 4);
        ChunkPos template = new ChunkPos(centre.x, centre.z + 200);
        KineticPreset preset = KineticPresetRegistry.get(ROUND);
        AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
        long[] keys = new long[DISPERSED];
        Vec3[] last = new Vec3[DISPERSED];
        long[] stats = new long[3];
        long[] resolve = new long[DISPERSED_TICKS];
        int[] stalled = new int[DISPERSED_TICKS];
        int[] tick = new int[1];
        long[] paced = new long[1];
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, template.x, template.z, true))
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(template.x, template.z)
                        != null, "template chunk loading"))
                .thenExecute(() -> {
                    LevelChunk c = level.getChunkSource().getChunkNow(template.x, template.z);
                    for (int i = 0; i < 256; i++) {
                        c.setBlockState(new BlockPos(template.getMinBlockX() + (i & 15), -61,
                                template.getMinBlockZ() + (i >> 4)), Blocks.FARMLAND.defaultBlockState(), false);
                    }
                    long t0 = System.nanoTime();
                    plantGrid(level, template, centre);
                    LOGGER.info(String.format(Locale.ROOT, "VirtualFlightBench dispersed: planted %d chunks in %.1f s",
                            GRID * GRID, (System.nanoTime() - t0) / 1.0e9));
                    ForcedChunks.set(level, template.x, template.z, false);
                    RandomSource random = RandomSource.create(11L);
                    Vec3 from = new Vec3(centre.getMiddleBlockX() + 0.5, Y, centre.getMiddleBlockZ() + 0.5);
                    for (int k = 0; k < DISPERSED; k++) {
                        double a = random.nextDouble() * Math.PI * 2.0;
                        keys[k] = Rounds.launch(level, preset, from, new Vec3(Math.cos(a) * 4.0, 0.0,
                                Math.sin(a) * 4.0), null, null);
                        last[k] = from;
                    }
                    stats[0] = terrain.stats();
                    stats[1] = terrain.decodes();
                    stats[2] = terrain.decodeNanos();
                })
                .thenExecuteFor(DISPERSED_TICKS, () -> {
                    int t = tick[0]++;
                    if (t >= DISPERSED_TICKS) {
                        return;
                    }
                    // Gametest server sprints: decode threads get a real tick's wall time only if paced.
                    long now = System.nanoTime();
                    if (t > 0 && now - paced[0] < 50_000_000L) {
                        try {
                            Thread.sleep((50_000_000L - (now - paced[0])) / 1_000_000L);
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    paced[0] = System.nanoTime();
                    resolve[t] = Rounds.lastResolveNanos(level);
                    for (int k = 0; k < DISPERSED; k++) {
                        Vec3 p = Rounds.position(level, keys[k]);
                        stalled[t] += p != null && p.equals(last[k]) ? 1 : 0;
                        last[k] = p;
                    }
                    LOGGER.info(String.format(Locale.ROOT, "VirtualFlightBench dispersed t%02d: step %.2f ms, stalled "
                                    + "%d, stats %d, cache %d columns %.1f MB, pending %d",
                            t, resolve[t] / 1.0e6, stalled[t], terrain.stats() - stats[0], terrain.cachedColumns(),
                            terrain.cachedBytes() / 1048576.0, terrain.pendingColumns()));
                })
                .thenExecute(() -> {
                    for (long key : keys) {
                        Rounds.destroy(level, key);
                    }
                    long[] sorted = resolve.clone();
                    Arrays.sort(sorted);
                    long sum = 0;
                    int stalls = 0;
                    for (int t = 0; t < DISPERSED_TICKS; t++) {
                        sum += resolve[t];
                        stalls += t == 0 ? 0 : stalled[t];
                    }
                    int decodes = terrain.decodes() - (int) stats[1];
                    LOGGER.info(String.format(Locale.ROOT, "VirtualFlightBench dispersed (%d rounds, %d ticks): step "
                                    + "mean %.2f ms, median %.2f, max %.2f; stalled round-ticks %d; stats %d; decode "
                                    + "%d x %.0f us; cache %d columns %.1f MB",
                            DISPERSED, DISPERSED_TICKS, sum / 1.0e6 / DISPERSED_TICKS,
                            sorted[DISPERSED_TICKS / 2] / 1.0e6, sorted[DISPERSED_TICKS - 1] / 1.0e6, stalls,
                            terrain.stats() - stats[0], decodes,
                            decodes == 0 ? 0.0 : (terrain.decodeNanos() - stats[2]) / 1000.0 / decodes,
                            terrain.cachedColumns(), terrain.cachedBytes() / 1048576.0));
                    deleteGrid(level, centre);
                })
                .thenSucceed();
    }

    /** The field's region files (the server never opened them). */
    private static void deleteGrid(ServerLevel level, ChunkPos centre) {
        int x0 = centre.x - GRID / 2;
        int z0 = centre.z - GRID / 2;
        Path dir = RegionReader.directory(level);
        try {
            for (int rx = x0 >> 5; rx <= (x0 + GRID - 1) >> 5; rx++) {
                for (int rz = z0 >> 5; rz <= (z0 + GRID - 1) >> 5; rz++) {
                    Files.delete(dir.resolve("r." + rx + "." + rz + ".mca"));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code template}'s save at every chunk of the {@link #GRID}^2 field around {@code centre}, a region at a time. */
    private static void plantGrid(ServerLevel level, ChunkPos template, ChunkPos centre) {
        CompoundTag tag = VirtualFlightGameTest.saved(level, template, template);
        int x0 = centre.x - GRID / 2;
        int z0 = centre.z - GRID / 2;
        Path dir = RegionReader.directory(level);
        for (int rx = x0 >> 5; rx <= (x0 + GRID - 1) >> 5; rx++) {
            for (int rz = z0 >> 5; rz <= (z0 + GRID - 1) >> 5; rz++) {
                if (level.getChunkSource().chunkMap.worker.storage.regionCache.containsKey(ChunkPos.asLong(rx, rz))) {
                    throw new IllegalStateException("region " + rx + "," + rz + " is open in the server");
                }
                try (RegionFile region = new RegionFile(new RegionStorageInfo("wflib_bench", level.dimension(),
                        "chunk"), dir.resolve("r." + rx + "." + rz + ".mca"), dir, false)) {
                    for (int i = 0; i < 1024; i++) {
                        int cx = (rx << 5) + (i & 31);
                        int cz = (rz << 5) + (i >> 5);
                        if (cx < x0 || cx >= x0 + GRID || cz < z0 || cz >= z0 + GRID) {
                            continue;
                        }
                        tag.putInt("xPos", cx);
                        tag.putInt("zPos", cz);
                        try (DataOutputStream out = region.getChunkDataOutputStream(new ChunkPos(cx, cz))) {
                            NbtIo.write(tag, out);
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    /** ns per step, all rounds stepped to {@link #STEPS} synchronously. */
    private static double pass(ServerLevel level, ResourceLocation round, Vec3 start, Vec3 v) {
        KineticPreset preset = KineticPresetRegistry.get(round);
        long[] keys = new long[ROUNDS];
        for (int k = 0; k < ROUNDS; k++) {
            keys[k] = Rounds.launch(level, preset, start.add(0.0, 0.0, k * 0.013), v, null, null);
        }
        long t0 = System.nanoTime();
        int steps = 0;
        for (int s = 0; s < STEPS; s++) {
            for (long key : keys) {
                steps += Rounds.step(level, key) ? 1 : 0;
            }
        }
        long ns = System.nanoTime() - t0;
        for (long key : keys) {
            sink += Rounds.remainingLife(level, key);
            Rounds.destroy(level, key);
        }
        if (steps != ROUNDS * STEPS) {
            throw new IllegalStateException(steps + " of " + ROUNDS * STEPS + " steps flew");
        }
        return ns / (double) steps;
    }

    /** One real region file decoded on this thread. */
    private static void realTerrain(ServerLevel level) {
        Path file = Path.of(System.getProperty("wflib.bench.regions", "world/region/r.0.0.mca"));
        if (!Files.isRegularFile(file)) {
            LOGGER.info("VirtualFlightBench real terrain: {} absent, skipped", file.toAbsolutePath());
            return;
        }
        String[] name = file.getFileName().toString().split("\\.");
        int rx = Integer.parseInt(name[1]);
        int rz = Integer.parseInt(name[2]);
        var blocks = level.holderLookup(Registries.BLOCK);
        long read = 0;
        long decode = 0;
        long bytes = 0;
        int chunks = 0;
        int mixed = 0;
        try {
            for (int i = 0; i < 1024; i++) {
                int cx = (rx << 5) + (i & 31);
                int cz = (rz << 5) + (i >> 5);
                long t0 = System.nanoTime();
                CompoundTag tag = RegionReader.read(file.getParent(), cx, cz);
                long t1 = System.nanoTime();
                if (tag == null) {
                    continue;
                }
                SectionSolidity[] column = SolidityDecoder.decode(tag, blocks, level.getMinSection(),
                        level.getSectionsCount());
                decode += System.nanoTime() - t1;
                read += t1 - t0;
                chunks++;
                for (SectionSolidity s : column) {
                    bytes += s.bytes();
                    mixed += s.bytes() > 0 ? 1 : 0;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("decoding " + file, e);
        }
        LOGGER.info(String.format(Locale.ROOT, "VirtualFlightBench real terrain %s: %d chunks, read+inflate %.0f us, "
                        + "decode %.0f us per chunk; %.1f mixed sections, %d B per chunk", file, chunks,
                read / 1000.0 / chunks, decode / 1000.0 / chunks, mixed / (double) chunks, bytes / chunks));
    }

    private static void row(ServerLevel level, int cx0, int cz, boolean forced) {
        for (int k = 0; k < CHUNKS; k++) {
            ForcedChunks.set(level, cx0 + k, cz, forced);
        }
    }

    private static int rowLoaded(ServerLevel level, int cx0, int cz) {
        int n = 0;
        for (int k = 0; k < CHUNKS; k++) {
            n += level.getChunkSource().getChunkNow(cx0 + k, cz) != null ? 1 : 0;
        }
        return n;
    }

    private static double median(double[] xs) {
        double[] s = xs.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }
}

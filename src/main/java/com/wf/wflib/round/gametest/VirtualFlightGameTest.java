package com.wf.wflib.round.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.StrikeContext;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.DeferredImpact;
import com.wf.wflib.round.DeferredImpacts;
import com.wf.wflib.round.RoundEnd;
import com.wf.wflib.round.RoundImpactEvent;
import com.wf.wflib.round.RoundNetwork;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.round.terrain.AsyncTerrainSource;
import com.wf.wflib.round.terrain.SectionSolidity;
import com.wf.wflib.round.terrain.SolidityDecoder;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Non-chunk-loading rounds over unloaded ground: on-disk terrain, deferred impacts, re-entry, fast-forward. Lanes run
 * -X (arena grid grows +X/+Z) at distinct far offsets; own batches (no other arenas' chunks, no stray players).
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = WFLib.MODID)
public class VirtualFlightGameTest {

    private static final String TEMPLATE = "empty";
    private static final String BATCH = "wflib_virtual";
    /** A watcher's chunk tickets and presence must not reach the other lanes. */
    private static final String WATCH_BATCH = "wflib_virtual_watch";
    /** Built in a chunk this far (+Z, chunks) from where it is planted: another region. */
    static final int BUILD_OFFSET = 128;

    private static final ResourceLocation RECORDING_WARHEAD = WarheadRegistry.rl("test_virtual_recording");
    private static final List<Vec3> DETONATIONS = new CopyOnWriteArrayList<>();
    private static final List<StrikeContext> STRIKES = new CopyOnWriteArrayList<>();
    private static final List<BlockHitResult> IMPACTS = new CopyOnWriteArrayList<>();

    private static final ResourceLocation CROSSING = KineticPresetRegistry.rl("test_virtual_crossing");
    private static final ResourceLocation SHELL = KineticPresetRegistry.rl("test_virtual_shell");
    /** Drag, quadratic drag, gravity, water: every term of the recurrence. */
    private static final ResourceLocation DIVER = KineticPresetRegistry.rl("test_virtual_diver");
    private static final ResourceLocation WATCHED = KineticPresetRegistry.rl("test_virtual_watched");
    private static final ResourceLocation WATCHED_PEN = KineticPresetRegistry.rl("test_virtual_watched_pen");
    /** Planted-chunk regions off every lane and build row (lanes +0, builds +{@link #BUILD_OFFSET}), blocks +Z. */
    private static final int SIDE_ROW = 1024;

    static {
        WarheadRegistry.register(RECORDING_WARHEAD, (source, pos) -> DETONATIONS.add(pos));
        ResourceLocation inert = WarheadRegistry.rl("inert");
        KineticPresetRegistry.register(KineticPreset.builder(CROSSING, null, inert).speed(12.0).drag(0.0)
                .gravity(0.0).life(200).impactDamage(5.0).caliber(7.62).noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(SHELL, null, RECORDING_WARHEAD).speed(12.0)
                .drag(0.0).gravity(0.0).life(200).caliber(30.0).noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(DIVER, null, inert).speed(2.0).drag(0.02)
                .quadraticDrag(0.001).gravity(0.0245).water(0.3, 0.6).life(400).impactDamage(1.0).caliber(5.56)
                .noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(WATCHED, null, inert).speed(40.0).drag(0.0)
                .gravity(0.0).life(40).impactDamage(1.0).caliber(5.56).noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(WATCHED_PEN, null, inert).speed(40.0).drag(0.0)
                .gravity(0.0).life(140).mass(0.004).energyDamage(0.01).caliber(5.56).blockPen(10.0f)
                .noChunkLoading().build());
    }

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        StrikeContext strike = StrikeContext.of(event.getSource());
        if (strike != null) {
            STRIKES.add(strike);
        }
    }

    @SubscribeEvent
    public static void onImpact(RoundImpactEvent event) {
        if (event.preset().id().equals(DIVER)) {
            IMPACTS.add(event.hit());
        }
    }

    /** Loaded -> unloaded gap -> loaded: the target past the gap is struck (was: lost at the first unloaded chunk). */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void aRoundCrossesUnloadedGroundToATargetBeyond(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 start = lane(helper, 200.0);
        Vec3 target = start.add(-480.0, 0.0, 0.0);
        ChunkPos far = new ChunkPos(BlockPos.containing(target));
        ForcedChunks.set(level, far.x, far.z, true);
        Husk[] husk = new Husk[1];
        long[] key = new long[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(BlockPos.containing(target)),
                        "target chunk not ticking"))
                .thenExecute(() -> {
                    helper.assertTrue(unloadedBetween(level, start, target) > 0, "setup: nothing unloaded between");
                    husk[0] = new Husk(EntityType.HUSK, level);
                    husk[0].moveTo(target.x, target.y - 1.0, target.z);
                    husk[0].setNoAi(true);
                    husk[0].setNoGravity(true);
                    level.addFreshEntity(husk[0]);
                    STRIKES.clear();
                    key[0] = Rounds.launch(level, KineticPresetRegistry.get(CROSSING), start,
                            new Vec3(-12.0, 0.0, 0.0), null, null);
                })
                .thenWaitUntil(() -> helper.assertTrue(STRIKES.stream()
                        .anyMatch(s -> Long.valueOf(key[0]).equals(s.key())), "not struck; round at "
                        + Rounds.position(level, key[0])))
                .thenExecute(() -> {
                    husk[0].discard();
                    Rounds.destroy(level, key[0]);
                    ForcedChunks.set(level, far.x, far.z, false);
                })
                .thenSucceed();
    }

    /**
     * Segment ending in an undecoded column (region file present => async read): the target short of it is struck on
     * the launch step (was: whole step waited for the far column).
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void aTargetShortOfUndecodedGroundIsStruckAtOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        ChunkPos near = new ChunkPos((o.getX() - 4200) >> 4, o.getZ() >> 4);
        Vec3 start = new Vec3(near.getMinBlockX() + 2.5, 200.0, o.getZ() + 0.5);
        Vec3 velocity = new Vec3(-60.0, 0.0, 0.0);
        ChunkPos far = new ChunkPos(BlockPos.containing(start.add(velocity)));
        AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, near.x, near.z, true))
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(BlockPos.containing(start)),
                        "near chunk not ticking"))
                .thenExecute(() -> {
                    helper.assertFalse(loaded(level, far), "setup: segment end loaded");
                    plant(level, near, far);
                    terrain.invalidate(far.toLong());
                    Husk husk = new Husk(EntityType.HUSK, level);
                    husk.moveTo(start.x - 4.0, start.y - 1.0, start.z);
                    husk.setNoAi(true);
                    husk.setNoGravity(true);
                    level.addFreshEntity(husk);
                    STRIKES.clear();
                    long key = Rounds.launch(level, KineticPresetRegistry.get(CROSSING), start, velocity, null, null);
                    try {
                        Rounds.step(level, key);
                        helper.assertTrue(terrain.peek(far.x, far.z) == null, "setup: far column decoded");
                        helper.assertTrue(STRIKES.stream().anyMatch(s -> Long.valueOf(key).equals(s.key())),
                                "not struck; round at " + Rounds.position(level, key));
                    } finally {
                        husk.discard();
                        Rounds.destroy(level, key);
                        ForcedChunks.set(level, near.x, near.z, false);
                    }
                })
                .thenSucceed();
    }

    /**
     * Wall on disk in an unloaded chunk: the round stops on it without loading it; the warhead goes off once the chunk
     * loads, not before.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 2000)
    public static void anImpactOnUnloadedGroundWaitsForItsChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 start = lane(helper, 180.0);
        int wallX = BlockPos.containing(start).getX() - 1200;
        BlockPos wall = new BlockPos(wallX, BlockPos.containing(start).getY(), BlockPos.containing(start).getZ());
        ChunkPos chunk = new ChunkPos(wall);
        ChunkPos build = new ChunkPos(chunk.x, chunk.z + BUILD_OFFSET);
        BlockPos built = wall.offset(0, 0, BUILD_OFFSET * 16);
        long[] key = new long[1];
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(loaded(level, build), "build chunk not loaded"))
                .thenExecute(() -> {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            level.setBlockAndUpdate(built.offset(0, dy, dz), Blocks.STONE.defaultBlockState());
                        }
                    }
                    plant(level, build, chunk);
                    ForcedChunks.set(level, build.x, build.z, false);
                    DETONATIONS.clear();
                    key[0] = Rounds.launch(level, KineticPresetRegistry.get(SHELL), start,
                            new Vec3(-12.0, 0.0, 0.0), null, null);
                })
                .thenWaitUntil(() -> helper.assertTrue(Rounds.position(level, key[0]) == null, "still flying at "
                        + Rounds.position(level, key[0])))
                .thenExecute(() -> {
                    helper.assertFalse(loaded(level, chunk), "the impact loaded the wall chunk");
                    helper.assertTrue(DETONATIONS.isEmpty(), "warhead went off in an unloaded chunk");
                    List<DeferredImpact> owed = DeferredImpacts.pending(level, chunk);
                    helper.assertTrue(owed.size() == 1, "owed " + owed);
                    DeferredImpact d = owed.get(0);
                    helper.assertTrue(Math.abs(d.at().x - (wallX + 1)) < 1.0e-9 && d.face() == Direction.EAST
                            && !d.landed() && d.block().equals(wall), "impact " + d + ", wall face x " + (wallX + 1));
                    ForcedChunks.set(level, chunk.x, chunk.z, true);
                })
                .thenWaitUntil(() -> helper.assertFalse(DETONATIONS.isEmpty(), "no detonation after load"))
                .thenExecute(() -> {
                    Vec3 at = DETONATIONS.get(0);
                    helper.assertTrue(Math.abs(at.x - (wallX + 1)) < 1.0e-9 && Math.abs(at.y - start.y) < 1.0e-9,
                            "detonation " + at);
                    helper.assertTrue(level.getBlockState(wall).is(Blocks.STONE), "loaded chunk is not the planted one");
                    helper.assertTrue(DeferredImpacts.pending(level, chunk).isEmpty(), "impact still owed");
                    ForcedChunks.set(level, chunk.x, chunk.z, false);
                })
                .thenSucceed();
    }

    /**
     * Same launch into a water pool on a slab floor, once from disk and once loaded (same chunk, same coordinates):
     * every step's state and the hit bit-for-bit equal.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 2000)
    public static void theRecurrenceOverDiskTerrainIsTheLoadedOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        ChunkPos chunk = new ChunkPos((o.getX() - 2000) >> 4, o.getZ() >> 4);
        ChunkPos build = new ChunkPos(chunk.x, chunk.z + BUILD_OFFSET);
        int x0 = chunk.getMinBlockX() + 4;
        int z0 = chunk.getMinBlockZ() + 4;
        int floor = 150;
        Vec3 start = new Vec3(x0 + 4.3, floor + 25.7, z0 + 4.1);
        Vec3 velocity = new Vec3(0.21, -1.7, 0.13);
        KineticPreset diver = KineticPresetRegistry.get(DIVER);
        List<double[]> diskRun = new ArrayList<>();
        DeferredImpact[] diskHit = new DeferredImpact[1];
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(loaded(level, build), "build chunk not loaded"))
                .thenExecute(() -> {
                    buildPool(level, x0, z0 + BUILD_OFFSET * 16, floor);
                    plant(level, build, chunk);
                    ForcedChunks.set(level, build.x, build.z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(Rounds.prefetch(level, start, start), "decoding"))
                .thenExecute(() -> {
                    long key = Rounds.launch(level, diver, start, velocity, null, null);
                    run(level, key, diskRun);
                    helper.assertFalse(loaded(level, chunk), "the disk run loaded the chunk");
                    List<DeferredImpact> owed = DeferredImpacts.pending(level, chunk);
                    helper.assertTrue(owed.size() == 1, "disk run: owed " + owed);
                    diskHit[0] = owed.get(0);
                    IMPACTS.clear();
                    ForcedChunks.set(level, chunk.x, chunk.z, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(DeferredImpacts.pending(level, chunk).isEmpty()
                        && IMPACTS.size() == 1, "deferred impact not applied"))
                .thenExecute(() -> {
                    IMPACTS.clear();
                    List<double[]> loadedRun = new ArrayList<>();
                    long key = Rounds.launch(level, diver, start, velocity, null, null);
                    run(level, key, loadedRun);
                    helper.assertTrue(IMPACTS.size() == 1, "loaded run: impacts " + IMPACTS.size());
                    helper.assertTrue(wetSteps(diver, loadedRun, velocity) > 0, "setup: never in the water");
                    helper.assertTrue(diskRun.size() == loadedRun.size(), "steps: loaded " + loadedRun.size()
                            + ", disk " + diskRun.size());
                    for (int s = 0; s < diskRun.size(); s++) {
                        double[] a = loadedRun.get(s);
                        double[] b = diskRun.get(s);
                        for (int k = 0; k < a.length; k++) {
                            helper.assertTrue(Double.doubleToRawLongBits(a[k]) == Double.doubleToRawLongBits(b[k]),
                                    "step " + s + " component " + k + ": loaded " + a[k] + ", disk " + b[k]);
                        }
                    }
                    DeferredImpact d = diskHit[0];
                    BlockHitResult h = IMPACTS.get(0);
                    helper.assertTrue(d.at().equals(h.getLocation()) && d.face() == h.getDirection()
                                    && d.block().equals(h.getBlockPos()),
                            "hit: loaded " + h.getLocation() + " " + h.getDirection() + ", disk " + d);
                    ForcedChunks.set(level, chunk.x, chunk.z, false);
                })
                .thenSucceed();
    }

    /**
     * Two rounds over unloaded ground: one with a player near its launch point steps once a tick, one without runs
     * ahead; the watched round's end reaches that player from past the audience range.
     */
    @GameTest(template = TEMPLATE, batch = WATCH_BATCH, timeoutTicks = 200)
    public static void aWatchedRoundIsNeverFastForwarded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 spot = lane(helper, 200.0);
        Vec3 watchedStart = spot.add(-400.0, 0.0, 0.0);
        Vec3 aloneStart = spot.add(-400.0, 0.0, 3000.0);
        Vec3 v = new Vec3(-40.0, 0.0, 0.0);
        KineticPreset preset = KineticPresetRegistry.get(WATCHED);
        ServerPlayer watcher = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "wflib_watcher"), ClientInformation.createDefault());
        List<RoundNetwork.RoundPacket> packets = capture(level, watcher);
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        watcher.moveTo(o.getX() + 0.5, o.getY() + 2.0, o.getZ() + 0.5, 0.0F, 0.0F);
        long[] key = new long[2];
        int[] life = new int[1];
        helper.startSequence()
                .thenExecute(() -> level.addFreshEntity(watcher))
                .thenWaitUntil(() -> helper.assertTrue(
                        Rounds.prefetch(level, watchedStart, watchedStart.add(v.scale(preset.lifeTicks())))
                                & Rounds.prefetch(level, aloneStart, aloneStart.add(v.scale(preset.lifeTicks()))),
                        "decoding"))
                .thenExecute(() -> {
                    helper.assertTrue(unloadedBetween(level, watchedStart, watchedStart.add(v)) > 0
                            && unloadedBetween(level, aloneStart, aloneStart.add(v)) > 0, "setup: lanes loaded");
                    key[0] = Rounds.launch(level, preset, watchedStart, v, null, null);
                    key[1] = Rounds.launch(level, preset, aloneStart, v, null, null);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    life[0] = Rounds.remainingLife(level, key[0]);
                    int aloneLeft = Rounds.remainingLife(level, key[1]);
                    int watchedSteps = preset.lifeTicks() - life[0];
                    int aloneSteps = preset.lifeTicks() - Math.max(aloneLeft, 0);
                    helper.assertTrue(watchedSteps <= 3 && aloneSteps > 3 * watchedSteps,
                            "steps in the same ticks: watched " + watchedSteps + ", unwatched " + aloneSteps);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    int watchedSteps = life[0] - Rounds.remainingLife(level, key[0]);
                    helper.assertTrue(watchedSteps == 10, "watched round: " + watchedSteps + " steps in 10 ticks");
                })
                .thenWaitUntil(() -> helper.assertTrue(Rounds.position(level, key[0]) == null, "watched in flight"))
                .thenIdle(1)
                .thenExecute(() -> {
                    watcher.discard();
                    boolean spawned = packets.stream().anyMatch(p -> p.spawns().stream().anyMatch(s -> s.key() == key[0]));
                    RoundNetwork.End end = packets.stream().flatMap(p -> p.ends().stream())
                            .filter(e -> e.key() == key[0]).findFirst().orElse(null);
                    double gone = end == null ? Double.NaN : Math.abs(end.x() - watcher.getX());
                    helper.assertTrue(spawned && end != null && end.reason() == RoundEnd.EXPIRED && gone > 1024.0,
                            "watcher: spawn " + spawned + ", end " + end + " at " + gone + " blocks");
                })
                .thenSucceed();
    }

    /**
     * Column cached from the old save, then the chunk saved anew (event + IO worker write, as {@code ChunkMap.save}):
     * the round stops on the new wall.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 2000)
    public static void aSaveReplacesTheCachedColumn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        Vec3 start = new Vec3(o.getX() + 0.5, 180.0, o.getZ() + SIDE_ROW + 0.5);
        BlockPos wall = BlockPos.containing(start).offset(-1200, 0, 0);
        ChunkPos chunk = new ChunkPos(wall);
        ChunkPos build = new ChunkPos(chunk.x, chunk.z + BUILD_OFFSET);
        BlockPos built = wall.offset(0, 0, BUILD_OFFSET * 16);
        long[] key = new long[1];
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(loaded(level, build), "build chunk not loaded"))
                .thenExecute(() -> plant(level, build, chunk))
                .thenWaitUntil(() -> helper.assertTrue(Rounds.prefetch(level, start, wall.getCenter()), "decoding"))
                .thenExecute(() -> {
                    helper.assertFalse(solidOnDisk(level, wall), "setup: old save already has the wall");
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            level.setBlockAndUpdate(built.offset(0, dy, dz), Blocks.STONE.defaultBlockState());
                        }
                    }
                    CompoundTag tag = saved(level, build, chunk);
                    ForcedChunks.set(level, build.x, build.z, false);
                    NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(new ProtoChunk(chunk, UpgradeData.EMPTY, level,
                            level.registryAccess().registryOrThrow(Registries.BIOME), null), level, tag));
                    level.getChunkSource().chunkMap.write(chunk, tag);
                })
                .thenIdle(1)
                .thenWaitUntil(() -> helper.assertTrue(Rounds.prefetch(level, start, wall.getCenter()), "decoding"))
                .thenExecute(() -> key[0] = Rounds.launch(level, KineticPresetRegistry.get(CROSSING), start,
                        new Vec3(-12.0, 0.0, 0.0), null, null))
                .thenWaitUntil(() -> helper.assertTrue(Rounds.position(level, key[0]) == null, "still flying at "
                        + Rounds.position(level, key[0])))
                .thenExecute(() -> {
                    List<DeferredImpact> owed = DeferredImpacts.pending(level, chunk);
                    helper.assertTrue(owed.size() == 1 && owed.get(0).block().equals(wall),
                            "flew through the saved wall: owed " + owed);
                    ForcedChunks.set(level, chunk.x, chunk.z, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(DeferredImpacts.pending(level, chunk).isEmpty(),
                        "impact still owed"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getBlockState(wall).is(Blocks.STONE), "loaded chunk is not the saved one");
                    ForcedChunks.set(level, chunk.x, chunk.z, false);
                })
                .thenSucceed();
    }

    /** Slot holding another chunk's NBT (sector reuse): not cached as air; the retry reads the rewritten slot. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void aFailedReadIsRetriedNotFlownAsAir(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        BlockPos wall = new BlockPos(o.getX() - 2000, 180, o.getZ() + SIDE_ROW);
        ChunkPos chunk = new ChunkPos(wall);
        ChunkPos build = new ChunkPos(chunk.x, chunk.z + BUILD_OFFSET);
        AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(loaded(level, build), "build chunk not loaded"))
                .thenExecute(() -> {
                    level.setBlockAndUpdate(wall.offset(0, 0, BUILD_OFFSET * 16), Blocks.STONE.defaultBlockState());
                    write(level, chunk, saved(level, build, build));
                    helper.assertTrue(terrain.column(chunk.x, chunk.z) == null, "setup: column served at once");
                })
                .thenWaitUntil(() -> helper.assertTrue(terrain.failedAttempts(chunk.x, chunk.z) == 1,
                        "attempts " + terrain.failedAttempts(chunk.x, chunk.z)))
                .thenExecute(() -> {
                    helper.assertTrue(terrain.peek(chunk.x, chunk.z) == null, "failed read cached");
                    plant(level, build, chunk);
                    ForcedChunks.set(level, build.x, build.z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(terrain.column(chunk.x, chunk.z) != null, "not retried"))
                .thenExecute(() -> helper.assertTrue(solidOnDisk(level, wall), "retry did not read the wall"))
                .thenSucceed();
    }

    /** Every column of a region with a file looked up 11 times in one tick (queue overflows): one stat. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void aFullQueueDoesNotStatPerLookup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        ChunkPos chunk = new ChunkPos((o.getX() - 2800) >> 4, (o.getZ() + SIDE_ROW) >> 4);
        ChunkPos build = new ChunkPos(chunk.x, chunk.z + BUILD_OFFSET);
        AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
        int rx = chunk.getRegionX() << 5;
        int rz = chunk.getRegionZ() << 5;
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(loaded(level, build), "build chunk not loaded"))
                .thenExecute(() -> {
                    plant(level, build, chunk);
                    ForcedChunks.set(level, build.x, build.z, false);
                    long before = terrain.stats();
                    int peak = 0;
                    for (int pass = 0; pass < 11; pass++) {
                        for (int k = 0; k < 1024; k++) {
                            terrain.column(rx + (k & 31), rz + (k >> 5));
                            peak = Math.max(peak, terrain.pendingColumns());
                        }
                    }
                    long stats = terrain.stats() - before;
                    helper.assertTrue(peak == AsyncTerrainSource.MAX_IN_FLIGHT, "setup: queue peak " + peak);
                    helper.assertTrue(stats <= 1, stats + " stats for 11264 lookups in one region");
                })
                .thenSucceed();
    }

    /**
     * Air columns (ungenerated chunks of a region with a file; no region file) kept apart: 2x the LRU of them never
     * evicts a decoded wall column.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 1200)
    public static void airColumnsDoNotEvictTerrain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        BlockPos wall = new BlockPos(o.getX() - 4600, 180, o.getZ() + SIDE_ROW);
        ChunkPos chunk = new ChunkPos(wall);
        ChunkPos build = new ChunkPos(chunk.x, chunk.z + BUILD_OFFSET);
        AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
        int rx = chunk.getRegionX() << 5;
        int rz = chunk.getRegionZ() << 5;
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(loaded(level, build), "build chunk not loaded"))
                .thenExecute(() -> {
                    level.setBlockAndUpdate(wall.offset(0, 0, BUILD_OFFSET * 16), Blocks.STONE.defaultBlockState());
                    plant(level, build, chunk);
                    ForcedChunks.set(level, build.x, build.z, false);
                })
                .thenWaitUntil(() -> {
                    int pending = 0;
                    for (int k = 0; k < 1024; k++) {
                        pending += terrain.column(rx + (k & 31), rz + (k >> 5)) == null ? 1 : 0;
                    }
                    helper.assertTrue(pending == 0, pending + " columns decoding");
                })
                .thenExecute(() -> {
                    helper.assertTrue(solidOnDisk(level, wall), "setup: wall not decoded");
                    int far = chunk.z + 8192;
                    SectionSolidity[] none = terrain.column(chunk.x, far);
                    helper.assertTrue(none != null, "setup: column without a region file not served at once");
                    int air = 0;
                    for (int k = 0; k < 1024; k++) {
                        air += terrain.peek(rx + (k & 31), rz + (k >> 5)) == none ? 1 : 0;
                    }
                    helper.assertTrue(air >= 1000, "ungenerated in a region file: " + air + " air columns");
                    for (int k = 0; k < 2 * 2048; k++) {
                        helper.assertTrue(terrain.column(chunk.x + k % 64, far + k / 64) == none,
                                "setup: column without a region file not served at once");
                    }
                    helper.assertTrue(solidOnDisk(level, wall), "wall column evicted by air columns");
                })
                .thenSucceed();
    }

    /** Falling below the build range by {@link Rounds#VOID_DEPTH}: expired; rising or above that: flies on. */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void aRoundFallenBelowTheWorldExpires(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset preset = KineticPresetRegistry.get(CROSSING);
        double deep = level.getMinBuildHeight() - Rounds.VOID_DEPTH - 6.0;
        helper.startSequence()
                .thenExecute(() -> {
                    long falling = Rounds.launch(level, preset, lane(helper, deep), new Vec3(0.0, -2.0, 0.0), null,
                            null);
                    long rising = Rounds.launch(level, preset, lane(helper, deep), new Vec3(0.0, 2.0, 0.0), null,
                            null);
                    long shallow = Rounds.launch(level, preset, lane(helper, level.getMinBuildHeight() - 10.0),
                            new Vec3(0.0, -2.0, 0.0), null, null);
                    helper.assertTrue(!Rounds.step(level, falling), "falling round below the world still flies");
                    helper.assertTrue(Rounds.step(level, rising), "rising round ended");
                    helper.assertTrue(Rounds.step(level, shallow), "round within the void margin ended");
                    Rounds.destroy(level, rising);
                    Rounds.destroy(level, shallow);
                })
                .thenSucceed();
    }

    /**
     * Section of partial blocks (farmland, slab, snow, path): every voxel's state kept, byte-per-voxel at most; 16
     * slabs in the next: sparse.
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 400)
    public static void aPartialSectionDecodesCompactly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        ChunkPos chunk = new ChunkPos((o.getX() - 3400) >> 4, (o.getZ() + SIDE_ROW) >> 4);
        BlockState[] kinds = {Blocks.FARMLAND.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState(),
                Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 4),
                Blocks.DIRT_PATH.defaultBlockState(), Blocks.STONE.defaultBlockState()};
        int y0 = 160;
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, chunk.x, chunk.z, true))
                .thenWaitUntil(() -> helper.assertTrue(loaded(level, chunk), "chunk not loaded"))
                .thenExecute(() -> {
                    LevelChunk c = level.getChunkSource().getChunkNow(chunk.x, chunk.z);
                    for (int i = 0; i < 4096; i++) {
                        c.setBlockState(new BlockPos(chunk.getMinBlockX() + (i & 15), y0 + (i >> 8),
                                chunk.getMinBlockZ() + (i >> 4 & 15)), kinds[i * 7 % kinds.length], false);
                    }
                    for (int k = 0; k < 16; k++) {
                        c.setBlockState(new BlockPos(chunk.getMinBlockX() + k, y0 + 16 + k, chunk.getMinBlockZ() + 3),
                                kinds[1], false);
                    }
                    SectionSolidity[] column;
                    try {
                        column = SolidityDecoder.decode(ChunkSerializer.write(level, c),
                                level.holderLookup(Registries.BLOCK), level.getMinSection(),
                                level.getSectionsCount());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    SectionSolidity section = column[(y0 >> 4) - level.getMinSection()];
                    SectionSolidity sparse = column[(y0 >> 4) + 1 - level.getMinSection()];
                    for (int i = 0; i < 4096; i++) {
                        boolean slab = (i & 15) == (i >> 8) && (i >> 4 & 15) == 3;
                        helper.assertTrue(sparse.partial(i) == (slab ? kinds[1] : null), "sparse voxel " + i + ": "
                                + sparse.partial(i));
                    }
                    helper.assertTrue(sparse.bytes() <= 128, "16 slabs hold " + sparse.bytes() + " B");
                    for (int i = 0; i < 4096; i++) {
                        BlockState want = kinds[i * 7 % kinds.length];
                        boolean solid = want.is(Blocks.STONE);
                        helper.assertTrue(section.solid(i) == solid
                                        && (solid ? section.partial(i) == null : section.partial(i) == want),
                                "voxel " + i + ": " + section.partial(i) + " solid " + section.solid(i) + ", want "
                                        + want);
                    }
                    helper.assertTrue(section.bytes() <= 512 + 4096 + 64, "section holds " + section.bytes() + " B");
                    for (int i = 0; i < 8192; i++) {
                        c.setBlockState(new BlockPos(chunk.getMinBlockX() + (i & 15), y0 + (i >> 8),
                                chunk.getMinBlockZ() + (i >> 4 & 15)), Blocks.AIR.defaultBlockState(), false);
                    }
                    ForcedChunks.set(level, chunk.x, chunk.z, false);
                })
                .thenSucceed();
    }

    /**
     * Watched round pierces a plank on disk past 1024 of its watcher: still 1 step/tick after the resync, and the
     * pierce, resync and end reach the watcher.
     */
    @GameTest(template = TEMPLATE, batch = WATCH_BATCH, timeoutTicks = 300)
    public static void aResyncFarFromTheWatcherKeepsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 spot = lane(helper, 200.0);
        Vec3 start = spot.add(-400.0, 0.0, 0.0);
        BlockPos plank = BlockPos.containing(spot).offset(-5000, 0, 0);
        ChunkPos chunk = new ChunkPos(plank);
        ChunkPos build = new ChunkPos(chunk.x, chunk.z + BUILD_OFFSET);
        Vec3 v = new Vec3(-40.0, 0.0, 0.0);
        KineticPreset preset = KineticPresetRegistry.get(WATCHED_PEN);
        ServerPlayer watcher = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "wflib_pen_watcher"), ClientInformation.createDefault());
        List<RoundNetwork.RoundPacket> packets = capture(level, watcher);
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        watcher.moveTo(o.getX() + 0.5, o.getY() + 2.0, o.getZ() + 0.5, 0.0F, 0.0F);
        long[] key = new long[1];
        int[] life = new int[1];
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(loaded(level, build), "build chunk not loaded"))
                .thenExecute(() -> {
                    level.setBlockAndUpdate(plank.offset(0, 0, BUILD_OFFSET * 16),
                            Blocks.OAK_PLANKS.defaultBlockState());
                    plant(level, build, chunk);
                    ForcedChunks.set(level, build.x, build.z, false);
                    level.addFreshEntity(watcher);
                })
                .thenWaitUntil(() -> helper.assertTrue(Rounds.prefetch(level, start,
                        start.add(v.scale(preset.lifeTicks()))), "decoding"))
                .thenExecute(() -> key[0] = Rounds.launch(level, preset, start, v, null, null))
                .thenWaitUntil(() -> helper.assertTrue(packets.stream().anyMatch(p -> p.pierces().stream()
                        .anyMatch(r -> r.key() == key[0])), "no pierce; round at " + Rounds.position(level, key[0])))
                .thenExecute(() -> {
                    Vec3 at = Rounds.position(level, key[0]);
                    helper.assertTrue(at != null && Math.abs(at.x - watcher.getX()) > 1024.0, "pierced at " + at);
                    life[0] = Rounds.remainingLife(level, key[0]);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    int steps = life[0] - Rounds.remainingLife(level, key[0]);
                    helper.assertTrue(steps == 10, "after the resync: " + steps + " steps in 10 ticks");
                })
                .thenWaitUntil(() -> helper.assertTrue(Rounds.position(level, key[0]) == null, "in flight"))
                .thenIdle(1)
                .thenExecute(() -> {
                    watcher.discard();
                    long spawns = packets.stream().flatMap(p -> p.spawns().stream()).filter(s -> s.key() == key[0])
                            .count();
                    boolean ended = packets.stream().flatMap(p -> p.ends().stream()).anyMatch(e -> e.key() == key[0]);
                    helper.assertTrue(spawns >= 2 && ended, "watcher: spawns " + spawns + ", end " + ended);
                })
                .thenSucceed();
    }

    // --- fixtures -----------------------------------------------------------------------------

    /** Wall voxel solid in its decoded column (decoded already). */
    private static boolean solidOnDisk(ServerLevel level, BlockPos pos) {
        AsyncTerrainSource terrain = AsyncTerrainSource.of(level);
        SectionSolidity[] column = terrain.peek(pos.getX() >> 4, pos.getZ() >> 4);
        if (column == null) {
            throw new IllegalStateException("column of " + pos + " not decoded");
        }
        return column[(pos.getY() >> 4) - terrain.minSection()].solid(SectionSolidity.index(pos.getX(), pos.getY(),
                pos.getZ()));
    }

    /** Above the arena. */
    private static Vec3 lane(GameTestHelper helper, double y) {
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        return new Vec3(o.getX() + 0.5, y, o.getZ() + 0.5);
    }

    private static boolean loaded(ServerLevel level, ChunkPos chunk) {
        return level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null;
    }

    /**
     * {@code from}'s saved form written as {@code to} into its region file, as the IO worker would. Test fixture: a
     * sprinting gametest server never gets to save on unload. Ticks and structure refs carry {@code from}'s
     * coordinates: dropped.
     */
    static void plant(ServerLevel level, ChunkPos from, ChunkPos to) {
        write(level, to, saved(level, from, to));
    }

    /** {@code from}'s saved form, positioned as {@code as}. */
    static CompoundTag saved(ServerLevel level, ChunkPos from, ChunkPos as) {
        CompoundTag tag = ChunkSerializer.write(level, level.getChunkSource().getChunkNow(from.x, from.z));
        tag.putInt("xPos", as.x);
        tag.putInt("zPos", as.z);
        tag.remove("block_ticks");
        tag.remove("fluid_ticks");
        tag.remove("structures");
        return tag;
    }

    /** {@code tag} into {@code to}'s region slot through the server's IO worker; returns once written. */
    static void write(ServerLevel level, ChunkPos to, CompoundTag tag) {
        level.getChunkSource().chunkMap.write(to, tag).join();
    }

    /** Unloaded chunks at 8-block samples along the lane. */
    private static int unloadedBetween(ServerLevel level, Vec3 from, Vec3 to) {
        int n = 0;
        int samples = (int) Math.ceil(from.distanceTo(to) / 8.0);
        for (int k = 1; k < samples; k++) {
            BlockPos p = BlockPos.containing(from.lerp(to, k / (double) samples));
            n += level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null ? 1 : 0;
        }
        return n;
    }

    /** Steps to its end now; one {x, y, z, vx, vy, vz} per step survived. */
    private static void run(ServerLevel level, long key, List<double[]> out) {
        for (int s = 0; s < 400; s++) {
            Vec3 p = Rounds.position(level, key);
            Vec3 before = p;
            if (!Rounds.step(level, key)) {
                return;
            }
            p = Rounds.position(level, key);
            Vec3 v = Rounds.velocity(level, key);
            if (p.equals(before)) {
                Rounds.destroy(level, key);
                throw new IllegalStateException("round waited at step " + s + " (terrain not decoded)");
            }
            out.add(new double[]{p.x, p.y, p.z, v.x, v.y, v.z});
        }
        Rounds.destroy(level, key);
        throw new IllegalStateException("round never ended");
    }

    /** Steps whose velocity change is not the dry law. */
    private static int wetSteps(KineticPreset preset, List<double[]> run, Vec3 launch) {
        int n = 0;
        Vec3 v = launch;
        for (double[] s : run) {
            double dry = v.y * preset.decay(v.length()) - preset.gravity();
            n += s[4] != dry ? 1 : 0;
            v = new Vec3(s[3], s[4], s[5]);
        }
        return n;
    }

    /** 8x8 slab floor at {@code floor}, glass walls, water 7 deep; top open. */
    private static void buildPool(ServerLevel level, int x0, int z0, int floor) {
        for (int x = x0 - 1; x <= x0 + 8; x++) {
            for (int z = z0 - 1; z <= z0 + 8; z++) {
                boolean wall = x < x0 || x > x0 + 7 || z < z0 || z > z0 + 7;
                level.setBlockAndUpdate(new BlockPos(x, floor - 1, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, floor, z),
                        wall ? Blocks.GLASS.defaultBlockState() : Blocks.STONE_SLAB.defaultBlockState());
                for (int y = floor + 1; y <= floor + 8; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), wall ? Blocks.GLASS.defaultBlockState()
                            : y <= floor + 7 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Listener with the rounds channel that keeps what is sent. */
    private static List<RoundNetwork.RoundPacket> capture(ServerLevel level, ServerPlayer player) {
        List<RoundNetwork.RoundPacket> out = new CopyOnWriteArrayList<>();
        new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
                if (packet instanceof ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof RoundNetwork.RoundPacket rounds) {
                    out.add(rounds);
                }
            }

            @Override
            public boolean hasChannel(ResourceLocation payloadId) {
                return RoundNetwork.RoundPacket.TYPE.id().equals(payloadId);
            }
        };
        return out;
    }
}

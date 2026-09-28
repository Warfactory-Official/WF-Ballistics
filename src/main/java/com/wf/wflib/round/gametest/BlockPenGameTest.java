package com.wf.wflib.round.gametest;

import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.DeferredImpact;
import com.wf.wflib.round.DeferredImpacts;
import com.wf.wflib.round.RoundImpactEvent;
import com.wf.wflib.round.RoundPierceEvent;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.round.pen.BlockPen;
import com.wf.wflib.round.pen.PenMaterial;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Non-destructive block penetration: rifle round (blockPen 10, 800 m/s) vs plank, stone, glass, loaded and on disk.
 * Loaded arenas fly straight up (no other arena above).
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = WFLib.MODID)
public class BlockPenGameTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation RIFLE = KineticPresetRegistry.rl("test_pen_rifle");
    /** Same energy fractions at 1/10 the speed: a tracer a client can watch (`/wflib round`). */
    private static final ResourceLocation SLOW = KineticPresetRegistry.rl("test_pen_slow");
    private static final double SPEED = 40.0;
    private static final float BLOCK_PEN = 10.0f;
    private static final List<RoundPierceEvent> PIERCES = new CopyOnWriteArrayList<>();
    private static final List<RoundImpactEvent> IMPACTS = new CopyOnWriteArrayList<>();
    private static final List<ProjectileStrikeEvent> STRIKES = new CopyOnWriteArrayList<>();

    static {
        KineticPresetRegistry.register(KineticPreset.builder(RIFLE, null, WarheadRegistry.rl("inert")).speed(SPEED)
                .drag(0.0).gravity(0.0).life(100).mass(0.004).energyDamage(0.01).caliber(5.56).blockPen(BLOCK_PEN)
                .tracer(0xFFAA00).noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(SLOW, null, WarheadRegistry.rl("inert")).speed(SPEED / 10.0)
                .drag(0.0).gravity(0.0).life(200).mass(0.004).energyDamage(0.01).caliber(5.56).blockPen(BLOCK_PEN)
                .tracer(0xFF3300).noChunkLoading().build());
    }

    @SubscribeEvent
    public static void onPierce(RoundPierceEvent event) {
        if (event.preset().id().equals(RIFLE)) {
            PIERCES.add(event);
        }
    }

    @SubscribeEvent
    public static void onImpact(RoundImpactEvent event) {
        if (event.preset().id().equals(RIFLE)) {
            IMPACTS.add(event);
        }
    }

    @SubscribeEvent
    public static void onStrike(ProjectileStrikeEvent event) {
        STRIKES.add(event);
    }

    /** Through one plank: 6 of 10 spent => 40% energy out, block intact, exit on the far face, heading within 4.8 deg. */
    @GameTest(template = TEMPLATE)
    public static void aRifleRoundExitsAPlankSlower(GameTestHelper helper) {
        Shot s = shoot(helper, Blocks.OAK_PLANKS);
        Vec3 v = s.velocity;
        helper.assertTrue(s.pierce != null && s.pierce.pass().exited(), "no exit: " + s.pierce);
        helper.assertTrue(v != null, "plank stopped the round");
        double energy = v.lengthSqr() / (SPEED * SPEED);
        double turned = Math.acos(v.normalize().y);
        LOGGER.info("pen: plank energy {} speed {} deflection {} deg", energy, v.length(), Math.toDegrees(turned));
        helper.assertTrue(Math.abs(energy - (1.0 - PenMaterial.WOOD.resistance / (double) BLOCK_PEN)) < 1.0e-9,
                "energy fraction " + energy);
        helper.assertTrue(Math.abs(s.pierce.pass().point().y - (s.wall.getY() + 1)) < 1.0e-9,
                "exit " + s.pierce.pass().point());
        helper.assertTrue(turned <= 0.6 * BlockPen.MAX_DEFLECTION + 1.0e-9, "deflection " + turned);
        helper.assertTrue(s.intact, "plank destroyed");
        helper.succeed();
    }

    /** 4 left after the first plank < 6: stops 2/3 into the second; KE, not a flat budget. */
    @GameTest(template = TEMPLATE)
    public static void theSecondPlankStopsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(wall, Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(wall.above(), Blocks.OAK_PLANKS.defaultBlockState());
        PIERCES.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(RIFLE), centre(helper, 5.0),
                new Vec3(0.0, SPEED, 0.0), null, null);
        boolean flying = Rounds.step(level, key);
        List<RoundPierceEvent> mine = mine(key);
        boolean intact = level.getBlockState(wall).is(Blocks.OAK_PLANKS) && level.getBlockState(wall.above())
                .is(Blocks.OAK_PLANKS);
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(wall.above(), Blocks.AIR.defaultBlockState());
        helper.assertFalse(flying, "went through two planks");
        helper.assertTrue(mine.size() == 2 && mine.get(0).pass().exited() && !mine.get(1).pass().exited(),
                "pierces " + mine.size());
        double depth = mine.get(1).entry().getLocation().distanceTo(mine.get(1).pass().point());
        helper.assertTrue(Math.abs(depth - 4.0 / 6.0) < 1.0e-9, "stopped " + depth + " into the second plank");
        helper.assertTrue(intact, "planks destroyed");
        helper.succeed();
    }

    /** 60 mm/m: stopped 1/6 into the first of two stone blocks; impact event, no block lost. */
    @GameTest(template = TEMPLATE)
    public static void aRifleRoundStopsInTwoBlocksOfStone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
        PIERCES.clear();
        IMPACTS.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(RIFLE), centre(helper, 5.0),
                new Vec3(0.0, SPEED, 0.0), null, null);
        boolean flying = Rounds.step(level, key);
        List<RoundPierceEvent> mine = mine(key);
        boolean intact = level.getBlockState(wall).is(Blocks.STONE) && level.getBlockState(wall.above())
                .is(Blocks.STONE);
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(wall.above(), Blocks.AIR.defaultBlockState());
        helper.assertFalse(flying, "went through stone");
        helper.assertTrue(mine.size() == 1 && !mine.get(0).pass().exited(), "pierces " + mine.size());
        double depth = mine.get(0).pass().point().y - wall.getY();
        LOGGER.info("pen: stone stop depth {}", depth);
        helper.assertTrue(Math.abs(depth - BLOCK_PEN / (double) PenMaterial.STONE.resistance) < 1.0e-9, "depth " + depth);
        helper.assertTrue(IMPACTS.size() == 1 && IMPACTS.get(0).hit().getBlockPos().equals(wall), "impacts " + IMPACTS);
        helper.assertTrue(intact, "stone destroyed");
        helper.succeed();
    }

    /** 0.3 mm/m: 97% of the energy through. */
    @GameTest(template = TEMPLATE)
    public static void glassBarelySlowsIt(GameTestHelper helper) {
        Shot s = shoot(helper, Blocks.GLASS);
        helper.assertTrue(s.velocity != null && s.pierce != null && s.pierce.pass().exited(), "glass stopped it");
        double ratio = s.velocity.length() / SPEED;
        LOGGER.info("pen: glass speed ratio {}", ratio);
        helper.assertTrue(ratio > 0.98 && ratio < 1.0, "speed ratio " + ratio);
        helper.assertTrue(s.intact, "glass destroyed");
        helper.succeed();
    }

    /** A body behind the plank is struck at the exit speed. */
    @GameTest(template = TEMPLATE)
    public static void whatIsBehindThePlankTakesTheSlowerRound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(wall, Blocks.OAK_PLANKS.defaultBlockState());
        Husk husk = new Husk(EntityType.HUSK, level);
        Vec3 c = centre(helper, 13.0);
        husk.moveTo(c.x, c.y, c.z);
        husk.setNoAi(true);
        husk.setNoGravity(true);
        level.addFreshEntity(husk);
        STRIKES.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(RIFLE), centre(helper, 5.0),
                new Vec3(0.0, SPEED, 0.0), null, null);
        Rounds.step(level, key);
        Rounds.destroy(level, key);
        husk.discard();
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        ProjectileStrikeEvent strike = STRIKES.stream().filter(e -> Long.valueOf(key).equals(e.key())).findFirst()
                .orElse(null);
        helper.assertTrue(strike != null, "husk behind the plank not struck");
        double energy = strike.velocity().lengthSqr() / (SPEED * SPEED);
        helper.assertTrue(Math.abs(energy - 0.4) < 1.0e-9, "struck with energy fraction " + energy);
        helper.succeed();
    }

    /**
     * Plank over stone on disk in an unloaded chunk, then the same shot loaded (same chunk, coordinates, shot seed):
     * pierce points, exit velocity and stop bit-equal; unloaded stop => deferred impact at the stop point.
     */
    @GameTest(template = TEMPLATE, batch = "wflib_virtual", timeoutTicks = 2000)
    public static void aPierceOverDiskTerrainIsTheLoadedOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        ChunkPos chunk = new ChunkPos((o.getX() - 2800) >> 4, o.getZ() >> 4);
        ChunkPos build = new ChunkPos(chunk.x, chunk.z + VirtualFlightGameTest.BUILD_OFFSET);
        int x = chunk.getMinBlockX() + 7;
        int z = chunk.getMinBlockZ() + 9;
        int plankY = 150;
        int stoneY = 146;
        Vec3 start = new Vec3(x + 0.37, 170.0, z + 0.61);
        Vec3 velocity = new Vec3(0.013, -SPEED, 0.021);
        KineticPreset rifle = KineticPresetRegistry.get(RIFLE);
        List<Vec3[]> disk = new ArrayList<>();
        DeferredImpact[] owed = new DeferredImpact[1];
        helper.startSequence()
                .thenExecute(() -> ForcedChunks.set(level, build.x, build.z, true))
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(build.x, build.z) != null,
                        "build chunk not loaded"))
                .thenExecute(() -> {
                    int bz = z + VirtualFlightGameTest.BUILD_OFFSET * 16;
                    level.setBlockAndUpdate(new BlockPos(x, plankY, bz), Blocks.OAK_PLANKS.defaultBlockState());
                    for (BlockPos p : BlockPos.betweenClosed(x - 1, stoneY - 1, bz - 1, x + 1, stoneY, bz + 1)) {
                        level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                    }
                    VirtualFlightGameTest.plant(level, build, chunk);
                    ForcedChunks.set(level, build.x, build.z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(Rounds.prefetch(level, start, start), "decoding"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null, "setup: loaded");
                    fly(level, rifle, start, velocity, disk);
                    helper.assertTrue(level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null,
                            "the disk run loaded the chunk");
                    List<DeferredImpact> d = DeferredImpacts.pending(level, chunk);
                    helper.assertTrue(d.size() == 1, "owed " + d);
                    owed[0] = d.get(0);
                    ForcedChunks.set(level, chunk.x, chunk.z, true);
                })
                .thenWaitUntil(() -> helper.assertTrue(DeferredImpacts.pending(level, chunk).isEmpty(),
                        "deferred impact not applied"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getBlockState(new BlockPos(x, plankY, z)).is(Blocks.OAK_PLANKS),
                            "loaded chunk is not the planted one");
                    List<Vec3[]> loaded = new ArrayList<>();
                    fly(level, rifle, start, velocity, loaded);
                    helper.assertTrue(disk.size() == loaded.size() && disk.size() == 2, "records: disk "
                            + disk.size() + ", loaded " + loaded.size());
                    for (int k = 0; k < disk.size(); k++) {
                        for (int c = 0; c < disk.get(k).length; c++) {
                            Vec3 a = disk.get(k)[c];
                            Vec3 b = loaded.get(k)[c];
                            helper.assertTrue(a.equals(b), "record " + k + "." + c + ": disk " + a
                                    + ", loaded " + b);
                        }
                    }
                    Vec3 stop = disk.get(disk.size() - 1)[1];
                    helper.assertTrue(owed[0].at().equals(stop), "deferred at " + owed[0].at() + ", stop " + stop);
                    helper.assertTrue(stop.y < stoneY + 1 && stop.y > stoneY, "stop " + stop);
                    ForcedChunks.set(level, chunk.x, chunk.z, false);
                })
                .thenSucceed();
    }

    /**
     * Step cost, 500 rounds x 1 step each, 7 reps: through one plank vs the same segment clear vs stopped in stone.
     * Logs only.
     */
    @GameTest(template = TEMPLATE, required = false, batch = "wflib_bench", timeoutTicks = 400)
    public static void pierceCost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = BlockPos.containing(centre(helper, 10.0));
        KineticPreset rifle = KineticPresetRegistry.get(RIFLE);
        StringBuilder out = new StringBuilder();
        for (Block block : new Block[]{Blocks.AIR, Blocks.OAK_PLANKS, Blocks.STONE, Blocks.AIR, Blocks.OAK_PLANKS,
                Blocks.STONE}) {
            level.setBlockAndUpdate(wall, block.defaultBlockState());
            double best = Double.MAX_VALUE;
            for (int rep = 0; rep < 7; rep++) {
                long t0 = System.nanoTime();
                for (int n = 0; n < 500; n++) {
                    long key = Rounds.launch(level, rifle, centre(helper, 5.0), new Vec3(0.0, SPEED, 0.0), null,
                            null);
                    Rounds.step(level, key);
                    Rounds.destroy(level, key);
                }
                best = Math.min(best, (System.nanoTime() - t0) / 500.0);
            }
            out.append(String.format(java.util.Locale.ROOT, " %s %.0f ns;", block.getDescriptionId(), best));
        }
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        PIERCES.clear();
        IMPACTS.clear();
        LOGGER.info("pierce bench (launch+step+destroy, best of 7):{}", out);
        helper.succeed();
    }

    // --- fixtures -----------------------------------------------------------------------------

    private record Shot(BlockPos wall, RoundPierceEvent pierce, Vec3 velocity, boolean intact) {
    }

    /** One block 10 up, the rifle from 5 up, one step. */
    private static Shot shoot(GameTestHelper helper, Block block) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(wall, block.defaultBlockState());
        PIERCES.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(RIFLE), centre(helper, 5.0),
                new Vec3(0.0, SPEED, 0.0), null, null);
        Rounds.step(level, key);
        Vec3 v = Rounds.velocity(level, key);
        Rounds.destroy(level, key);
        boolean intact = level.getBlockState(wall).is(block);
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        List<RoundPierceEvent> mine = mine(key);
        return new Shot(wall, mine.isEmpty() ? null : mine.get(0), v, intact);
    }

    private static List<RoundPierceEvent> mine(long key) {
        return PIERCES.stream().filter(e -> e.key() == key).toList();
    }

    /** Shot seed 77 (key-independent deflection); records {entry, point, velocity after} per pierce. */
    private static void fly(ServerLevel level, KineticPreset preset, Vec3 start, Vec3 velocity, List<Vec3[]> out) {
        PIERCES.clear();
        long key = Rounds.launch(level, preset, start, velocity, null, null, 1.0f, 0, 77);
        for (int s = 0; s < 20 && Rounds.step(level, key); s++) {
        }
        Rounds.destroy(level, key);
        for (RoundPierceEvent e : mine(key)) {
            out.add(new Vec3[]{e.entry().getLocation(), e.pass().point(), e.pass().velocity()});
        }
    }

    private static Vec3 centre(GameTestHelper helper, double height) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return new Vec3(origin.getX() + 0.5, origin.getY() + height, origin.getZ() + 0.5);
    }
}

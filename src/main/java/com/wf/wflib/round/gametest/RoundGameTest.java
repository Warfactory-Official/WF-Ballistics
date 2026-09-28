package com.wf.wflib.round.gametest;

import com.wf.wflib.MissileModels;
import com.wf.wflib.WFLib;
import com.wf.wflib.api.StrikeContext;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.kinetic.KineticShellItem;
import com.wf.wflib.round.RoundArc;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Entity-less rounds fly the preset's contract and fuze, drill and strike like the shell entity did. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = WFLib.MODID)
public class RoundGameTest {

    private static final String TEMPLATE = "empty";

    private static final ResourceLocation RECORDING_WARHEAD = WarheadRegistry.rl("test_recording_warhead");
    private static final List<Vec3> DETONATIONS = new CopyOnWriteArrayList<>();
    private static final List<StrikeContext> STRIKES = new CopyOnWriteArrayList<>();

    private static final ResourceLocation ARC_ROUND = KineticPresetRegistry.rl("test_arc_round");
    private static final ResourceLocation BULLET = KineticPresetRegistry.rl("test_bullet");
    private static final ResourceLocation DRILLING_ROUND = KineticPresetRegistry.rl("test_drilling_round");
    private static final ResourceLocation AIRBURST_ROUND = KineticPresetRegistry.rl("test_airburst_round");
    private static final ResourceLocation CONTACT_ROUND = KineticPresetRegistry.rl("test_contact_round");
    private static final ResourceLocation BOMB = KineticPresetRegistry.rl("test_bomb");
    private static final ResourceLocation HEADSHOT_ROUND = KineticPresetRegistry.rl("test_headshot_round");
    /** Two dozen blocks a tick: a whole wall fits between two positions. */
    private static final ResourceLocation FAST_ROUND = KineticPresetRegistry.rl("test_fast_round");
    private static final ResourceLocation DRAG_ROUND = KineticPresetRegistry.rl("test_drag_round");

    private static final double CLIMB_SPEED = 1.0;
    private static final float TEST_DRAG = 0.01f;

    static {
        WarheadRegistry.register(RECORDING_WARHEAD, (source, pos) -> DETONATIONS.add(pos));
        ResourceLocation micro = MissileModels.rl("micro");
        KineticPresetRegistry.register(KineticPreset.builder(ARC_ROUND, micro, RECORDING_WARHEAD)
                .speed(CLIMB_SPEED).drag(TEST_DRAG).life(4000).build());
        KineticPresetRegistry.register(KineticPreset.builder(BULLET, null, WarheadRegistry.rl("inert"))
                .speed(4.0).life(400).impactDamage(7.0).caliber(12.7).armorPenetration(20.0).noChunkLoading()
                .passesThroughEntities().tracer(0xFFAA00).build());
        KineticPresetRegistry.register(KineticPreset.builder(DRILLING_ROUND, micro, RECORDING_WARHEAD)
                .speed(12.0).penetration(3, 100.0).build());
        KineticPresetRegistry.register(KineticPreset.builder(AIRBURST_ROUND, micro, RECORDING_WARHEAD)
                .speed(2.0).airburst(6.0).build());
        KineticPresetRegistry.register(KineticPreset.builder(CONTACT_ROUND, micro, RECORDING_WARHEAD)
                .speed(2.0).build());
        KineticPresetRegistry.register(KineticPreset.builder(HEADSHOT_ROUND, null, WarheadRegistry.rl("inert"))
                .speed(0.5).gravity(0.0).impactDamage(3.0).headshot(2.0).noChunkLoading().build());
        KineticPresetRegistry.register(KineticPreset.builder(BOMB, micro, RECORDING_WARHEAD)
                .speed(0.0).drag(0.0).quadraticDrag(0.005).fuseDelay(5).burrow(1.0).noEntityContact().build());
        KineticPresetRegistry.register(KineticPreset.builder(FAST_ROUND, micro, RECORDING_WARHEAD)
                .speed(24.0).build());
        KineticPresetRegistry.register(KineticPreset.builder(DRAG_ROUND, null, WarheadRegistry.rl("inert"))
                .speed(3.0).drag(0.003).quadraticDrag(0.0011722).gravity(0.04).life(400).noChunkLoading().build());
    }

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        StrikeContext strike = StrikeContext.of(event.getSource());
        if (strike != null) {
            STRIKES.add(strike);
        }
    }

    /** A round nobody can carry is a round nobody can load. */
    @GameTest(template = TEMPLATE)
    public static void everyRoundHasAnItem(GameTestHelper helper) {
        for (KineticPreset preset : KineticPresetRegistry.all()) {
            if (preset.id().getPath().startsWith("test_")) {
                continue;
            }
            KineticShellItem item = ModItems.shellItem(preset.id()).map(holder -> holder.get()).orElse(null);
            if (item == null || item.preset() != preset) {
                helper.fail("no item carrying round " + preset.id());
                return;
            }
        }
        helper.succeed();
    }

    /** The documented recurrence, exactly: what a closed-form gun solver inverts. Above the build limit too. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void theArcIsTheOneTheContractDescribes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset preset = KineticPresetRegistry.get(ARC_ROUND);
        Vec3 start = new Vec3(centre(helper, 0.0).x, level.getMaxBuildHeight() - 20.0, centre(helper, 0.0).z);
        Vec3 velocity = new Vec3(0.05, CLIMB_SPEED, 0.05);
        long key = Rounds.launch(level, preset, start, velocity, null, null);
        helper.runAfterDelay(120, () -> {
            Vec3 at = Rounds.position(level, key);
            if (at == null) {
                helper.fail("the round ended before it could be measured");
                return;
            }
            int flown = preset.lifeTicks() - Rounds.remainingLife(level, key);
            Vec3 expected = flyBy(preset, start, velocity, flown);
            double error = expected.distanceTo(at);
            Rounds.destroy(level, key);
            if (error > 1.0e-9) {
                helper.fail("after " + flown + " ticks the round is " + error + " blocks off the recurrence");
                return;
            }
            helper.succeed();
        });
    }

    /** Debug overlay's arc ({@link RoundArc}) vs real steps: every position bit-equal. */
    @GameTest(template = TEMPLATE)
    public static void anArcPredictionIsTheFlight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset preset = KineticPresetRegistry.get(DRAG_ROUND);
        Vec3 start = new Vec3(centre(helper, 0.0).x, level.getMaxBuildHeight() + 40.0, centre(helper, 0.0).z);
        Vec3 velocity = new Vec3(0.013, 2.7, -0.021);
        long key = Rounds.launch(level, preset, start, velocity, null, null);
        int ticks = 60;
        double[] arc = new double[3 * ticks];
        int n = RoundArc.predict(preset, start.x, start.y, start.z, velocity.x, velocity.y, velocity.z,
                Rounds.remainingLife(level, key), level.getMinBuildHeight() - Rounds.VOID_DEPTH, ticks, arc);
        if (n != ticks) {
            Rounds.destroy(level, key);
            helper.fail("arc " + n + " of " + ticks + " steps");
            return;
        }
        for (int k = 0; k < ticks; k++) {
            if (!Rounds.step(level, key)) {
                helper.fail("round ended at step " + k);
                return;
            }
            Vec3 at = Rounds.position(level, key);
            if (at.x != arc[3 * k] || at.y != arc[3 * k + 1] || at.z != arc[3 * k + 2]) {
                Rounds.destroy(level, key);
                helper.fail("step " + k + ": round " + at + " arc (" + arc[3 * k] + ", " + arc[3 * k + 1] + ", "
                        + arc[3 * k + 2] + ")");
                return;
            }
        }
        Rounds.destroy(level, key);
        helper.succeed();
    }

    /** Artillery descending into unloaded ground takes a ticket for it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aShellLoadsTheGroundItFallsInto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 far = centre(helper, 60.0).add(4096.0, 0.0, 4096.0);
        long key = Rounds.launch(level, KineticPresetRegistry.get(CONTACT_ROUND), far, new Vec3(0.0, -1.0, 0.0),
                null, null);
        helper.runAfterDelay(2, () -> {
            long[] held = Rounds.heldChunks(level, key);
            Rounds.destroy(level, key);
            if (held.length != 1 || held[0] != ChunkPos.asLong(BlockPos.containing(far))) {
                helper.fail("the shell holds no ticket for the ground under it: " + java.util.Arrays.toString(held));
                return;
            }
            helper.succeed();
        });
    }

    /** Segment crossing into unloaded ground: the loaded part strikes (flight beyond: {@link VirtualFlightGameTest}). */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aTargetShortOfUnloadedGroundIsStillStruck(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk husk = pinnedHusk(level, centre(helper, 8.0));
        Vec3 start = husk.position().add(0.0, 1.0, -1.5);
        Vec3 velocity = new Vec3(0.0, 0.0, 3000.0);
        Vec3 far = start.add(velocity);
        if (level.getChunkSource().getChunkNow(Mth.floor(far.x) >> 4, Mth.floor(far.z) >> 4) != null) {
            husk.discard();
            helper.fail("setup: segment end loaded");
            return;
        }
        STRIKES.clear();
        long key = Rounds.launch(level, KineticPresetRegistry.get(BULLET), start, velocity, null, null);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(STRIKES.stream().anyMatch(sc -> Long.valueOf(key)
                        .equals(sc.key())), "not struck"))
                .thenExecute(() -> {
                    Rounds.destroy(level, key);
                    husk.discard();
                })
                .thenSucceed();
    }

    /** A direct hit hurts through a damage source carrying the round's strike (threat, travel). */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aDirectHitCarriesItsStrike(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Husk: no sunburn. Pinned: a fall drops it under the round's line. Above the barrier lid.
        Husk husk = new Husk(EntityType.HUSK, level);
        Vec3 at = centre(helper, 8.0);
        husk.moveTo(at.x, at.y, at.z);
        husk.setNoAi(true);
        husk.setNoGravity(true);
        level.addFreshEntity(husk);
        float before = husk.getHealth();
        STRIKES.clear();
        // Straight down: a lateral lane leaves the arena's chunk, and a bullet is lost over unloaded ground.
        long key = Rounds.launch(level, KineticPresetRegistry.get(BULLET), at.add(0.0, 6.0, 0.0),
                new Vec3(0.0, -4.0, 0.0), null, null);
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 6 && STRIKES.isEmpty(); i++) {
            trace.append(Rounds.position(level, key)).append(' ');
            Rounds.step(level, key);
        }
        float after = husk.getHealth();
        Rounds.destroy(level, key);
        husk.discard();
        StrikeContext strike = STRIKES.isEmpty() ? null : STRIKES.get(0);
        if (after >= before || strike == null || Math.abs(strike.threat().penetrationMm()
                - 20.0 * strike.velocity().lengthSqr() / 16.0) > 1.0e-4 || strike.velocity().y >= 0.0) {
            helper.fail("hit: health " + before + " -> " + after + ", strike " + strike + ", round " + trace);
            return;
        }
        helper.succeed();
    }

    /** Same round, sideways inside the arena footprint: at eye height loses twice what at the waist does. */
    @GameTest(template = TEMPLATE)
    public static void aHeadshotMultipliesTheHit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Husk head = pinnedHusk(level, centre(helper, 8.0));
        Husk body = pinnedHusk(level, centre(helper, 14.0));
        KineticPreset round = KineticPresetRegistry.get(HEADSHOT_ROUND);
        Vec3 left = new Vec3(-0.5, 0.0, 0.0);
        long eye = Rounds.launch(level, round, head.position().add(1.4, head.getEyeHeight(), 0.0), left, null, null);
        long waist = Rounds.launch(level, round, body.position().add(1.4, 0.8, 0.0), left, null, null);
        for (int i = 0; i < 6; i++) {
            Rounds.step(level, eye);
            Rounds.step(level, waist);
        }
        Rounds.destroy(level, eye);
        Rounds.destroy(level, waist);
        float headLoss = head.getMaxHealth() - head.getHealth();
        float bodyLoss = body.getMaxHealth() - body.getHealth();
        head.discard();
        body.discard();
        if (!(bodyLoss > 0.0f) || Math.abs(headLoss / bodyLoss - 2.0f) > 0.05f) {
            helper.fail("head lost " + headLoss + ", body lost " + bodyLoss);
            return;
        }
        helper.succeed();
    }

    private static Husk pinnedHusk(ServerLevel level, Vec3 at) {
        Husk husk = new Husk(EntityType.HUSK, level);
        husk.moveTo(at.x, at.y, at.z);
        husk.setNoAi(true);
        husk.setNoGravity(true);
        level.addFreshEntity(husk);
        return husk;
    }

    /** Contact fuze: the warhead goes off on the block, and the round ends. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void impactFiresTheWarhead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
        Vec3 start = new Vec3(target.getX() + 0.5, target.getY() + 12.0, target.getZ() + 0.5);
        long key = Rounds.launch(level, KineticPresetRegistry.get(CONTACT_ROUND), start, new Vec3(0.0, -2.0, 0.0),
                null, null);
        helper.runAfterDelay(20, () -> {
            Vec3 hit = detonationInColumn(start, 2.0);
            level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
            if (hit == null || hit.y - (target.getY() + 1) > 2.0 || Rounds.position(level, key) != null) {
                helper.fail("contact: detonation " + hit + ", round " + Rounds.position(level, key));
                return;
            }
            helper.succeed();
        });
    }

    /** Bomb: lands, digs through two dirt (0.5 each of a 1.0 budget), stops on stone, then the fuse. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aBombBurrowsThenDetonates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stone = BlockPos.containing(centre(helper, 8.0));
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(stone.above(), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(stone.above(2), Blocks.DIRT.defaultBlockState());
        Vec3 start = Vec3.atBottomCenterOf(stone).add(0.0, 14.0, 0.0);
        long key = Rounds.launch(level, KineticPresetRegistry.get(BOMB), start, Vec3.ZERO, null, null);
        helper.runAfterDelay(60, () -> {
            Vec3 hit = detonationInColumn(start, 0.5);
            boolean dug = level.getBlockState(stone.above()).isAir() && level.getBlockState(stone.above(2)).isAir();
            boolean floor = level.getBlockState(stone).is(Blocks.STONE);
            level.setBlockAndUpdate(stone, Blocks.AIR.defaultBlockState());
            if (hit == null || Math.abs(hit.y - (stone.getY() + 1)) > 1.0e-6 || !dug || !floor
                    || Rounds.position(level, key) != null) {
                helper.fail("bomb: detonation " + hit + ", dug " + dug + ", stone kept " + floor);
                return;
            }
            helper.succeed();
        });
    }

    /** Resting: still there during the fuse, not moving. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aLandedBombWaitsForItsFuse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stone = BlockPos.containing(centre(helper, 8.0));
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        long key = Rounds.launch(level, KineticPresetRegistry.get(BOMB), Vec3.atBottomCenterOf(stone).add(0.0, 2.5, 0.0),
                new Vec3(0.0, -1.0, 0.0), null, null);
        for (int i = 0; i < 3; i++) {
            Rounds.step(level, key);
        }
        Vec3 at = Rounds.position(level, key);
        Vec3 v = Rounds.velocity(level, key);
        Rounds.destroy(level, key);
        level.setBlockAndUpdate(stone, Blocks.AIR.defaultBlockState());
        if (at == null || v == null || v.lengthSqr() != 0.0 || Math.abs(at.y - (stone.getY() + 1)) > 1.0e-6) {
            helper.fail("resting bomb: at " + at + ", v " + v);
            return;
        }
        helper.succeed();
    }

    /** Swept: a tick's travel spanning a wall still meets it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aFastRoundCannotCrossAWallInOneTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        Vec3 start = new Vec3(wall.getX() + 0.5, wall.getY() + 30.0, wall.getZ() + 0.5);
        Rounds.launch(level, KineticPresetRegistry.get(FAST_ROUND), start, new Vec3(0.0, -24.0, 0.0), null, null);
        helper.runAfterDelay(10, () -> {
            Vec3 hit = detonationInColumn(start, 2.0);
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            if (hit == null || hit.y < wall.getY() - 1.0) {
                helper.fail("the round crossed the wall: detonation " + hit);
                return;
            }
            helper.succeed();
        });
    }

    /** Armour-piercing spends its penetration on cover and goes off behind it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void armourPiercingDrillsThroughACourseOfCover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos top = BlockPos.containing(centre(helper, 10.0));
        for (int i = 0; i < 5; i++) {
            level.setBlockAndUpdate(top.below(i), Blocks.STONE.defaultBlockState());
        }
        Vec3 start = new Vec3(top.getX() + 0.5, top.getY() + 8.0, top.getZ() + 0.5);
        Rounds.launch(level, KineticPresetRegistry.get(DRILLING_ROUND), start, new Vec3(0.0, -12.0, 0.0), null, null);
        helper.runAfterDelay(20, () -> {
            int drilled = 0;
            while (drilled < 5 && level.getBlockState(top.below(drilled)).isAir()) {
                drilled++;
            }
            boolean went = detonationInColumn(start, 2.0) != null;
            for (int i = 0; i < 5; i++) {
                level.setBlockAndUpdate(top.below(i), Blocks.AIR.defaultBlockState());
            }
            if (drilled != 3 || !went) {
                helper.fail("drilled " + drilled + " of 3, detonated " + went);
                return;
            }
            helper.succeed();
        });
    }

    /** Airburst: goes off over the target, not on it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void anAirburstFiresAboveTheGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
        Vec3 start = new Vec3(ground.getX() + 0.5, ground.getY() + 30.0, ground.getZ() + 0.5);
        Rounds.launch(level, KineticPresetRegistry.get(AIRBURST_ROUND), start, new Vec3(0.0, -2.0, 0.0), null, null);
        helper.runAfterDelay(30, () -> {
            Vec3 burst = detonationInColumn(start, 2.0);
            boolean groundIntact = !level.getBlockState(ground).isAir();
            level.setBlockAndUpdate(ground, Blocks.AIR.defaultBlockState());
            double above = burst == null ? -1.0 : burst.y - (ground.getY() + 1);
            if (burst == null || above < 6.0 || above > 8.5 || !groundIntact) {
                helper.fail("airburst " + above + " blocks up (fuse 6), ground intact " + groundIntact);
                return;
            }
            helper.succeed();
        });
    }

    private static Vec3 centre(GameTestHelper helper, double height) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return new Vec3(origin.getX() + 0.5, origin.getY() + height, origin.getZ() + 0.5);
    }

    /** The documented recurrence, run here rather than read out of the class under test. */
    private static Vec3 flyBy(KineticPreset preset, Vec3 start, Vec3 velocity, int ticks) {
        Vec3 pos = start;
        Vec3 v = velocity;
        double decay = (double) (1.0f - preset.drag());
        for (int i = 0; i < ticks; i++) {
            pos = pos.add(v);
            v = v.scale(decay).subtract(0.0, preset.gravity(), 0.0);
        }
        return pos;
    }

    @Nullable
    private static Vec3 detonationInColumn(Vec3 around, double xzRadius) {
        for (Vec3 pos : DETONATIONS) {
            if (Math.abs(pos.x - around.x) <= xzRadius && Math.abs(pos.z - around.z) <= xzRadius) {
                return pos;
            }
        }
        return null;
    }
}

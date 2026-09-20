package com.wf.wflib.kinetic.gametest;

import com.wf.wflib.MissileModels;
import com.wf.wflib.WFLib;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.kinetic.KineticShellEntity;
import com.wf.wflib.kinetic.KineticShellItem;
import com.wf.wflib.kinetic.KineticSim;
import com.wf.wflib.kinetic.KineticSimManager;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Does a kinetic round fly the arc its preset describes, and is leaving the world invisible? */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class KineticShellGameTest {

    private static final String TEMPLATE = "empty";

    /** Records where it went off instead of doing anything, so a test can assert on the terminal event. */
    private static final ResourceLocation RECORDING_WARHEAD = WarheadRegistry.rl("test_recording_warhead");
    private static final List<Vec3> DETONATIONS = new CopyOnWriteArrayList<>();

    /** A slow round that leaves the world when it is entitled to. */
    private static final ResourceLocation SIM_ROUND = KineticPresetRegistry.rl("test_sim_round");
    /** The same round, forbidden from leaving. The control in every comparison below. */
    private static final ResourceLocation WORLD_ROUND = KineticPresetRegistry.rl("test_world_round");
    /** Fast and hard: drills a course of blocks before it goes off. */
    private static final ResourceLocation DRILLING_ROUND = KineticPresetRegistry.rl("test_drilling_round");
    /** Bursts short of the ground. */
    private static final ResourceLocation AIRBURST_ROUND = KineticPresetRegistry.rl("test_airburst_round");
    /** Contact fuse, nothing clever: goes off on the first thing it touches. */
    private static final ResourceLocation CONTACT_ROUND = KineticPresetRegistry.rl("test_contact_round");
    /** Two dozen blocks a tick: a whole wall fits between two naive samples of where it is. */
    private static final ResourceLocation FAST_ROUND = KineticPresetRegistry.rl("test_fast_round");

    private static final double CLIMB_SPEED = 1.0;
    private static final float TEST_DRAG = 0.01f;

    static {
        WarheadRegistry.register(RECORDING_WARHEAD, (source, pos) -> DETONATIONS.add(pos));
        KineticPresetRegistry.register(KineticPreset.builder(SIM_ROUND, MissileModels.rl("micro"),
                RECORDING_WARHEAD).speed(CLIMB_SPEED).drag(TEST_DRAG).life(4000).build());
        KineticPresetRegistry.register(KineticPreset.builder(WORLD_ROUND, MissileModels.rl("micro"),
                RECORDING_WARHEAD).speed(CLIMB_SPEED).drag(TEST_DRAG).life(4000).alwaysInWorld().build());
        KineticPresetRegistry.register(KineticPreset.builder(DRILLING_ROUND, MissileModels.rl("micro"),
                RECORDING_WARHEAD).speed(12.0).penetration(3, 100.0).alwaysInWorld().build());
        KineticPresetRegistry.register(KineticPreset.builder(AIRBURST_ROUND, MissileModels.rl("micro"),
                RECORDING_WARHEAD).speed(2.0).airburst(6.0).alwaysInWorld().build());
        KineticPresetRegistry.register(KineticPreset.builder(CONTACT_ROUND, MissileModels.rl("micro"),
                RECORDING_WARHEAD).speed(2.0).alwaysInWorld().build());
        KineticPresetRegistry.register(KineticPreset.builder(FAST_ROUND, MissileModels.rl("micro"),
                RECORDING_WARHEAD).speed(24.0).alwaysInWorld().build());
    }

    /** A round nobody can carry is a round nobody can load. */
    @GameTest(template = TEMPLATE)
    public static void everyRoundHasAnItem(GameTestHelper helper) {
        for (KineticPreset preset : KineticPresetRegistry.all()) {
            if (preset.id().getPath().startsWith("test_")) {
                continue;
            }
            KineticShellItem item = ModItems.shellItem(preset.id()).map(holder -> holder.get()).orElse(null);
            if (item == null) {
                helper.fail("no item for round " + preset.id());
                return;
            }
            if (item.preset() != preset) {
                helper.fail("the item for " + preset.id() + " carries a different round: " + item.preset().id());
                return;
            }
        }
        helper.succeed();
    }

    /**
     * The entity has to integrate in the documented order, because that is the recurrence a closed-form solver
     * inverts to find an elevation.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theArcIsTheOneTheContractDescribes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset preset = KineticPresetRegistry.get(WORLD_ROUND);
        Vec3 start = centre(helper, 20.0);
        Vec3 velocity = new Vec3(0.05, CLIMB_SPEED, 0.05);
        KineticShellEntity shell = fire(level, preset, start, velocity);

        helper.runAfterDelay(40, () -> {
            if (!shell.isAlive()) {
                helper.fail("the round stopped existing before anything could be measured");
                return;
            }
            int flown = preset.lifeTicks() - shell.remainingLife();
            Vec3 expected = flyBy(preset, start, velocity, flown);
            double error = expected.distanceTo(shell.position());
            if (error > 1.0e-9) {
                helper.fail("after " + flown + " ticks the round is " + error + " blocks from where the"
                        + " recurrence says it should be: " + shell.position() + " vs " + expected);
                return;
            }
            shell.discard();
            helper.succeed();
        });
    }

    /** Above the world and still going up: there is nothing to hit, so there is no reason to be an entity. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void climbingOutOfTheWorldLeavesNoEntity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 start = aboveTheWorld(helper, level, 5.0);
        KineticShellEntity shell = fire(level, KineticPresetRegistry.get(SIM_ROUND), start,
                new Vec3(0.0, CLIMB_SPEED, 0.0));
        UUID id = shell.getUUID();

        helper.runAfterDelay(3, () -> {
            if (level.getEntity(id) != null) {
                helper.fail("the round is still an entity above the build limit while climbing");
                return;
            }
            if (KineticSimManager.find(level, id) == null) {
                helper.fail("the round left the world without being handed to the simulator");
                return;
            }
            helper.succeed();
        });
    }

    /** On the way down it is going back to things it can hit, so it has to be real for it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aDescendingRoundStaysInTheWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 start = aboveTheWorld(helper, level, 40.0);
        KineticShellEntity shell = fire(level, KineticPresetRegistry.get(SIM_ROUND), start,
                new Vec3(0.0, -1.0, 0.0));
        UUID id = shell.getUUID();

        helper.runAfterDelay(5, () -> {
            if (KineticSimManager.find(level, id) != null) {
                helper.fail("a falling round was taken out of the world");
                return;
            }
            if (level.getEntity(id) == null) {
                helper.fail("the falling round stopped existing");
                return;
            }
            shell.discard();
            helper.succeed();
        });
    }

    /** A round that is not allowed to leave must not, however high it gets. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aRoundThatMustStayRealNeverLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 start = aboveTheWorld(helper, level, 5.0);
        KineticShellEntity shell = fire(level, KineticPresetRegistry.get(WORLD_ROUND), start,
                new Vec3(0.0, CLIMB_SPEED, 0.0));
        UUID id = shell.getUUID();

        helper.runAfterDelay(5, () -> {
            if (KineticSimManager.find(level, id) != null) {
                helper.fail("a round declared always-in-world was handed to the simulator anyway");
                return;
            }
            if (level.getEntity(id) == null) {
                helper.fail("the round stopped existing without ever entering the simulator");
                return;
            }
            shell.discard();
            helper.succeed();
        });
    }

    /**
     * The tick its vertical velocity turns over, the world has to have the round back, and it has to come back
     * still going the way it was going.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void itComesBackTheTickItStartsFalling(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 start = aboveTheWorld(helper, level, 5.0);
        KineticShellEntity shell = fire(level, KineticPresetRegistry.get(SIM_ROUND), start,
                new Vec3(0.0, CLIMB_SPEED, 0.0));
        UUID id = shell.getUUID();

        helper.runAfterDelay(50, () -> {
            if (KineticSimManager.find(level, id) != null) {
                helper.fail("the round is still out of the world well after its apex");
                return;
            }
            KineticShellEntity back = inColumn(level, start, 2.0);
            if (back == null) {
                helper.fail("the round never came back");
                return;
            }
            if (back.getDeltaMovement().y > 0.0) {
                helper.fail("the round came back while it was still climbing: vy " + back.getDeltaMovement().y);
                return;
            }
            if (back.getY() <= level.getMaxBuildHeight()) {
                helper.fail("the round came back at " + back.getY() + ", below the top of the world: it was"
                        + " an entity again while it still had nothing to hit");
                return;
            }
            back.discard();
            helper.succeed();
        });
    }

    /**
     * The claim the whole feature rests on: a round that spent its ascent out of the world is in exactly the place,
     * on exactly the tick, that an identical round which never left would have been.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void theSimulatorFliesTheArcTheEntityWouldHave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 simStart = aboveTheWorld(helper, level, 5.0);
        Vec3 worldStart = simStart.add(4.0, 0.0, 0.0);
        Vec3 velocity = new Vec3(0.0, CLIMB_SPEED, 0.0);

        KineticPreset simPreset = KineticPresetRegistry.get(SIM_ROUND);
        KineticPreset worldPreset = KineticPresetRegistry.get(WORLD_ROUND);
        KineticShellEntity simulated = fire(level, simPreset, simStart, velocity);
        KineticShellEntity control = fire(level, worldPreset, worldStart, velocity);
        UUID simId = simulated.getUUID();

        helper.runAfterDelay(120, () -> {
            if (KineticSimManager.find(level, simId) != null) {
                helper.fail("the simulated round never came back, so there is nothing to compare");
                return;
            }
            KineticShellEntity back = inColumn(level, simStart, 2.0);
            if (back == null) {
                helper.fail("the simulated round is not in the world after its apex");
                return;
            }
            if (!control.isAlive()) {
                helper.fail("the control round stopped existing");
                return;
            }
            int simFlown = simPreset.lifeTicks() - back.remainingLife();
            int controlFlown = worldPreset.lifeTicks() - control.remainingLife();
            if (simFlown != controlFlown) {
                helper.fail("the simulated round has flown " + simFlown + " ticks and the control "
                        + controlFlown + ": the two of them are not advancing it once per tick");
                return;
            }
            double dy = Math.abs(back.getY() - control.getY());
            double dvy = Math.abs(back.getDeltaMovement().y - control.getDeltaMovement().y);
            if (dy > 1.0e-9 || dvy > 1.0e-9) {
                helper.fail("going through the simulator changed the arc: " + dy + " blocks and " + dvy
                        + " blocks/tick apart from the round that stayed in the world");
                return;
            }
            back.discard();
            control.discard();
            helper.succeed();
        });
    }

    /** The payload is a warhead off the shared registry, and hitting something is what fires it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void impactFiresTheWarhead(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());

        Vec3 start = new Vec3(target.getX() + 0.5, target.getY() + 12.0, target.getZ() + 0.5);
        KineticShellEntity shell = fire(level, KineticPresetRegistry.get(CONTACT_ROUND), start,
                new Vec3(0.0, -2.0, 0.0));
        UUID id = shell.getUUID();

        helper.runAfterDelay(20, () -> {
            Vec3 hit = detonationInColumn(start, 2.0);
            if (hit == null) {
                helper.fail("the round hit the block and the warhead never went off");
                return;
            }
            if (hit.y - (target.getY() + 1) > 2.0) {
                helper.fail("the warhead went off " + (hit.y - target.getY()) + " blocks over the block it"
                        + " hit, which is not a contact fuse");
                return;
            }
            if (level.getEntity(id) != null) {
                helper.fail("the round is still in the world after its warhead went off");
                return;
            }
            level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
            helper.succeed();
        });
    }

    /** A round doing two dozen blocks a tick crosses a wall entirely between one tick's position and the next. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aFastRoundCannotCrossAWallInOneTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());

        // Fired from far enough up that a tick's travel spans the wall and comes out the far side.
        Vec3 start = new Vec3(wall.getX() + 0.5, wall.getY() + 30.0, wall.getZ() + 0.5);
        KineticShellEntity shell = fire(level, KineticPresetRegistry.get(FAST_ROUND), start,
                new Vec3(0.0, -24.0, 0.0));
        UUID id = shell.getUUID();

        helper.runAfterDelay(10, () -> {
            Vec3 hit = detonationInColumn(start, 2.0);
            if (hit == null) {
                helper.fail("the round went straight through the wall without touching it");
                return;
            }
            if (hit.y < wall.getY() - 1.0) {
                helper.fail("the round went off " + (wall.getY() - hit.y) + " blocks past the wall, so it"
                        + " was already through it when the hit was noticed");
                return;
            }
            if (level.getEntity(id) != null) {
                helper.fail("the round is still in the world after its warhead went off");
                return;
            }
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            helper.succeed();
        });
    }

    /** An armour-piercing round buries itself: it spends its penetration on cover and goes off behind it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void armourPiercingDrillsThroughACourseOfCover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos top = BlockPos.containing(centre(helper, 10.0));
        for (int i = 0; i < 5; i++) {
            level.setBlockAndUpdate(top.below(i), Blocks.STONE.defaultBlockState());
        }

        Vec3 start = new Vec3(top.getX() + 0.5, top.getY() + 8.0, top.getZ() + 0.5);
        fire(level, KineticPresetRegistry.get(DRILLING_ROUND), start, new Vec3(0.0, -12.0, 0.0));

        helper.runAfterDelay(20, () -> {
            for (int i = 0; i < 3; i++) {
                if (!level.getBlockState(top.below(i)).isAir()) {
                    helper.fail("the round only got through " + i + " of the three blocks it can penetrate");
                    return;
                }
            }
            if (level.getBlockState(top.below(3)).isAir()) {
                helper.fail("the round went through more cover than its penetration allows");
                return;
            }
            if (detonationInColumn(start, 2.0) == null) {
                helper.fail("the round drilled its way in and then never went off");
                return;
            }
            for (int i = 0; i < 5; i++) {
                level.setBlockAndUpdate(top.below(i), Blocks.AIR.defaultBlockState());
            }
            helper.succeed();
        });
    }

    /** An airburst round is one that never arrives: it goes off over the target, not on it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void anAirburstFiresAboveTheGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = BlockPos.containing(centre(helper, 10.0));
        level.setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());

        Vec3 start = new Vec3(ground.getX() + 0.5, ground.getY() + 30.0, ground.getZ() + 0.5);
        fire(level, KineticPresetRegistry.get(AIRBURST_ROUND), start, new Vec3(0.0, -2.0, 0.0));

        helper.runAfterDelay(30, () -> {
            Vec3 burst = detonationInColumn(start, 2.0);
            if (burst == null) {
                helper.fail("the airburst round never went off");
                return;
            }
            double above = burst.y - (ground.getY() + 1);
            if (above < 6.0 || above > 8.5) {
                helper.fail("the round burst " + above + " blocks up; its fuse is set for six");
                return;
            }
            if (level.getBlockState(ground).isAir()) {
                helper.fail("the round reached the ground it was supposed to burst over");
                return;
            }
            level.setBlockAndUpdate(ground, Blocks.AIR.defaultBlockState());
            helper.succeed();
        });
    }

    /** A shell in the world when the server stops has to come back as the same round. */
    @GameTest(template = TEMPLATE)
    public static void aRoundRemembersWhatItIsAcrossASave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset preset = KineticPresetRegistry.get(KineticPresetRegistry.rl("rap"));
        KineticShellEntity shell = new KineticShellEntity(level, preset);
        shell.setRemainingLife(1234);
        shell.setFactionId(UUID.nameUUIDFromBytes("test".getBytes()));

        CompoundTag tag = new CompoundTag();
        shell.saveWithoutId(tag);

        KineticShellEntity reloaded = new KineticShellEntity(level, KineticPresetRegistry.fallback());
        reloaded.load(tag);

        if (!reloaded.preset().id().equals(preset.id())) {
            helper.fail("the round came back as " + reloaded.preset().id() + ", not " + preset.id());
            return;
        }
        if (reloaded.remainingLife() != 1234) {
            helper.fail("the round came back with " + reloaded.remainingLife() + " ticks of life, not 1234");
            return;
        }
        if (!shell.factionId().equals(reloaded.factionId())) {
            helper.fail("the round came back unattributed, so its blast would obey nobody's chunk rules");
            return;
        }
        helper.succeed();
    }

    private static KineticShellEntity fire(ServerLevel level, KineticPreset preset, Vec3 pos, Vec3 velocity) {
        KineticShellEntity shell = new KineticShellEntity(level, preset);
        shell.setPos(pos.x, pos.y, pos.z);
        shell.setDeltaMovement(velocity);
        shell.alignToMotion();
        level.addFreshEntity(shell);
        return shell;
    }

    /** The middle of this test's own arena, a given height up: everything is measured in its own column. */
    private static Vec3 centre(GameTestHelper helper, double height) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return new Vec3(origin.getX() + 0.5, origin.getY() + height, origin.getZ() + 0.5);
    }

    private static Vec3 aboveTheWorld(GameTestHelper helper, ServerLevel level, double height) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return new Vec3(origin.getX() + 0.5, level.getMaxBuildHeight() + height, origin.getZ() + 0.5);
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

    /** The round in this column, wherever it has got to vertically. */
    @Nullable
    private static KineticShellEntity inColumn(ServerLevel level, Vec3 around, double xzRadius) {
        AABB column = new AABB(around.x - xzRadius, level.getMinBuildHeight() - 64.0, around.z - xzRadius,
                around.x + xzRadius, level.getMaxBuildHeight() + 512.0, around.z + xzRadius);
        List<KineticShellEntity> found = level.getEntitiesOfClass(KineticShellEntity.class, column,
                Entity::isAlive);
        KineticShellEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (KineticShellEntity shell : found) {
            double dx = shell.getX() - around.x;
            double dz = shell.getZ() - around.z;
            double distance = dx * dx + dz * dz;
            if (distance < bestSq) {
                bestSq = distance;
                best = shell;
            }
        }
        return best;
    }

    /** The detonation in this test's own column. */
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

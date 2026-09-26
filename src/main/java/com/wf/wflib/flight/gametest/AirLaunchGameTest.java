package com.wf.wflib.flight.gametest;

import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileModels;
import com.wf.wflib.WFLib;
import com.wf.wflib.flight.FlightStageRegistry;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/** A pylon round drops clear on the launcher's velocity before its motor lights, and never hits its launcher doing so. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class AirLaunchGameTest {

    private static final String TEMPLATE = "empty";
    /** Past torpedo (+Z 192..336) and naval-mine (448..515) lanes. */
    private static final int LANE_ORIGIN = 800;
    private static final int LANE_SPACING = 48;
    private static final double ALTITUDE = 40.0;

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void aPylonRoundFallsBeforeItsMotorLights(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = lane(helper, 0);
        force(level, lane, true);
        MissileEntity missile = launch(level, lane, new Vec3(1.0, 0.0, 0.0), null);
        double launchY = missile.getY();
        double[] dropSpeed = new double[1];
        double[] fell = new double[1];
        MissileEntity.Phase[] dropPhase = new MissileEntity.Phase[1];
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    dropSpeed[0] = missile.getDeltaMovement().length();
                    dropPhase[0] = missile.flight().getPhase();
                    fell[0] = launchY - missile.getY();
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    force(level, lane, false);
                    MissileEntity.Phase after = missile.flight().getPhase();
                    missile.discard();
                    if (dropPhase[0] != MissileEntity.Phase.ASCEND) {
                        helper.fail("5 ticks off the rail the round is already in " + dropPhase[0]);
                    } else if (Math.abs(dropSpeed[0] - 1.0) > 0.25) {
                        helper.fail(String.format(Locale.ROOT,
                                "motor cold, a round launched at 1.00 b/t is doing %.2f", dropSpeed[0]));
                    } else if (fell[0] <= 0.3) {
                        helper.fail(String.format(Locale.ROOT, "5 ticks off the rail the round fell only %.2f", fell[0]));
                    } else if (after != MissileEntity.Phase.ATTACK) {
                        helper.fail("15 ticks off the rail the round is still in " + after);
                    }
                })
                .thenSucceed();
    }

    /** A parked launcher's round would drop onto the ground under the rail, so it lights at once. */
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void aParkedRoundLightsOnTheRail(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = lane(helper, 1);
        force(level, lane, true);
        MissileEntity missile = launch(level, lane, Vec3.ZERO, null);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    force(level, lane, false);
                    MissileEntity.Phase phase = missile.flight().getPhase();
                    missile.discard();
                    if (phase != MissileEntity.Phase.ATTACK) {
                        helper.fail("a round fired from a standstill is in " + phase + ", not ATTACK");
                    }
                })
                .thenSucceed();
    }

    /** Its launcher sits in the drop path past the arming distance; the round flies through it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aRoundDoesNotHitItsLauncherOnTheDrop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = lane(helper, 2);
        force(level, lane, true);
        ArmorStand launcher = new ArmorStand(EntityType.ARMOR_STAND, level);
        launcher.setNoGravity(true);
        launcher.moveTo(lane.getX() + 8.5, lane.getY() + ALTITUDE - 1.5, lane.getZ() + 0.5);
        level.addFreshEntity(launcher);
        MissileEntity missile = launch(level, lane, new Vec3(1.0, 0.0, 0.0), launcher);
        helper.startSequence()
                .thenIdle(12)
                .thenExecute(() -> {
                    force(level, lane, false);
                    boolean detonated = missile.isRemoved();
                    double x = missile.getX() - lane.getX();
                    missile.discard();
                    launcher.discard();
                    if (detonated) {
                        helper.fail("the round detonated on its own launcher on the drop");
                    } else if (x < 9.0) {
                        helper.fail(String.format(Locale.ROOT, "the round is at +%.2f, short of the launcher at +8.5", x));
                    }
                })
                .thenSucceed();
    }

    private static MissileEntity launch(ServerLevel level, BlockPos lane, Vec3 velocity, ArmorStand owner) {
        MissilePreset preset = MissilePreset.builder(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "test_air_launch"), MissileModels.rl("atgm"),
                        WarheadRegistry.rl("inert"))
                .highAltitude(lane.getY() + ALTITUDE)
                .cruiseSpeed(2.0)
                .ascentStage(FlightStageRegistry.rl("air_launch"))
                .attackStage(FlightStageRegistry.rl("dive"))
                .fuel(MissileEntity.FuelType.SOLID, 400)
                .build();
        MissileEntity missile = preset.build(level, new Vec3(lane.getX() + 200.5, lane.getY(), lane.getZ() + 0.5));
        missile.moveTo(lane.getX() + 0.5, lane.getY() + ALTITUDE, lane.getZ() + 0.5, -90.0f, 0.0f);
        missile.setDeltaMovement(velocity);
        if (owner != null) {
            missile.setOwner(owner);
        }
        level.addFreshEntity(missile);
        return missile;
    }

    private static BlockPos lane(GameTestHelper helper, int index) {
        return helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_ORIGIN + index * LANE_SPACING);
    }

    private static void force(ServerLevel level, BlockPos lane, boolean forced) {
        int cx = SectionPos.blockToSectionCoord(lane.getX());
        int cz = SectionPos.blockToSectionCoord(lane.getZ());
        for (int ox = -1; ox <= 2; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                ForcedChunks.set(level, cx + ox, cz + oz, forced);
            }
        }
    }
}

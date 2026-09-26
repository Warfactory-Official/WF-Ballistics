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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shot-down missiles: a spin-out wanders under power until it hits something; a downed round can land as a dud. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class DownedGameTest {

    private static final String TEMPLATE = "empty";
    /** Arenas share one world. Duds land ~20 out; a spin-out wanders ~150 any way: far past every other lane (+Z <= 896). */
    private static final int LANE_ORIGIN = 1500;
    private static final int SPIN_LANE = 2000;
    /** swing/net under 1.5 on some draws: a random walk can drift one way. */
    private static final long SPIN_SEED = 7L;
    private static final int LANE_SPACING = 64;
    private static final double ALTITUDE = 60.0;

    @GameTest(template = TEMPLATE, timeoutTicks = 500)
    public static void aSpinOutWandersUntilItHitsTheGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = helper.absolutePos(BlockPos.ZERO).offset(0, 0, SPIN_LANE);
        force(level, lane, true);
        MissileEntity missile = launch(level, lane, 0.0, 1.5);
        List<Vec3> headings = new ArrayList<>();
        double[] lastY = {Double.NaN};
        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> {
                    missile.getRandom().setSeed(SPIN_SEED);
                    missile.damage().shootDown(MissileEntity.DownedAction.SPIN_OUT);
                })
                .thenWaitUntil(() -> {
                    if (!missile.isRemoved()) {
                        headings.add(missile.getDeltaMovement().normalize());
                        lastY[0] = missile.getY();
                        helper.fail("still flying");
                    }
                })
                .thenExecute(() -> {
                    force(level, lane, false);
                    int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(missile.getX()),
                            (int) Math.floor(missile.getZ()));
                    // Wander: total heading swing over the powered part vs. the net change across it.
                    int n = Math.min(headings.size(), 30);
                    double swing = 0.0;
                    for (int i = 1; i < n; i++) {
                        swing += Math.acos(Math.min(1.0, headings.get(i - 1).dot(headings.get(i))));
                    }
                    double net = Math.acos(Math.min(1.0, headings.getFirst().dot(headings.get(n - 1))));
                    if (lastY[0] - ground > 6.0) {
                        helper.fail(String.format(Locale.ROOT, "went off %.1f above the ground, not on impact", lastY[0] - ground));
                    } else if (swing < 1.0) {
                        helper.fail(String.format(Locale.ROOT, "heading swung only %.2f rad in %d ticks", swing, n));
                    } else if (swing < net * 1.5) {
                        helper.fail(String.format(Locale.ROOT, "a steady turn, not a tumble: swing %.2f vs net %.2f", swing, net));
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aDudRestsWhereItFellAndAnExplosionSetsItOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = lane(helper, 1);
        force(level, lane, true);
        MissileEntity missile = launch(level, lane, 1.0, 0.5);
        Vec3[] rest = new Vec3[1];
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> missile.damage().shootDown(MissileEntity.DownedAction.CRASH))
                .thenWaitUntil(() -> {
                    if (!missile.isDud()) {
                        helper.fail(missile.isRemoved() ? "went off despite dudChance 1" : "still falling");
                    }
                })
                .thenExecute(() -> rest[0] = missile.position())
                .thenIdle(40)
                .thenExecute(() -> {
                    if (missile.isRemoved()) {
                        helper.fail("the dud vanished: " + missile.getRemovalReason() + " at " + missile.position() + " rest " + rest[0]);
                    } else if (missile.position().distanceTo(rest[0]) > 1.0E-6) {
                        helper.fail("the dud moved " + missile.position().distanceTo(rest[0]));
                    } else if (!missile.isPickable()) {
                        helper.fail("a dud cannot be picked, so it cannot be defused");
                    }
                    level.explode(null, missile.getX(), missile.getY() + 1.0, missile.getZ(), 2.0f, Level.ExplosionInteraction.NONE);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    force(level, lane, false);
                    if (!missile.isRemoved()) {
                        missile.discard();
                        helper.fail("an explosion next to the dud did not set it off");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aDudSurvivesSaveAndLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = lane(helper, 2);
        force(level, lane, true);
        MissileEntity missile = launch(level, lane, 1.0, 0.5);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> missile.damage().shootDown(MissileEntity.DownedAction.POWER_LOSS))
                .thenWaitUntil(() -> {
                    if (!missile.isDud()) {
                        helper.fail("not a dud yet");
                    }
                })
                .thenExecute(() -> {
                    force(level, lane, false);
                    CompoundTag tag = missile.saveWithoutId(new CompoundTag());
                    Vector3f heading = new Vector3f(missile.dudHeading());
                    missile.discard();
                    MissileEntity copy = (MissileEntity) missile.getType().create(level);
                    copy.load(tag);
                    if (!copy.isDud()) {
                        helper.fail("a reloaded dud is live flight again");
                    } else if (copy.dudHeading().distance(heading) > 1.0E-5f) {
                        helper.fail("dud heading " + heading + " reloaded as " + copy.dudHeading());
                    }
                })
                .thenSucceed();
    }

    private static MissileEntity launch(ServerLevel level, BlockPos lane, double dudChance, double speed) {
        MissilePreset preset = MissilePreset.builder(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "test_downed"),
                        MissileModels.rl("atgm"), WarheadRegistry.rl("inert"))
                .highAltitude(lane.getY() + ALTITUDE)
                .cruiseSpeed(1.5)
                .ascentStage(FlightStageRegistry.rl("air_launch"))
                .attackStage(FlightStageRegistry.rl("dive"))
                .fuel(MissileEntity.FuelType.SOLID, 400)
                .dudChance(dudChance)
                .build();
        MissileEntity missile = preset.build(level, new Vec3(lane.getX() + 400.5, lane.getY(), lane.getZ() + 0.5));
        missile.moveTo(lane.getX() + 0.5, lane.getY() + ALTITUDE, lane.getZ() + 0.5, -90.0f, 0.0f);
        missile.setDeltaMovement(new Vec3(speed, 0.0, 0.0));
        level.addFreshEntity(missile);
        return missile;
    }

    private static BlockPos lane(GameTestHelper helper, int index) {
        return helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_ORIGIN + index * LANE_SPACING);
    }

    private static void force(ServerLevel level, BlockPos lane, boolean forced) {
        int cx = SectionPos.blockToSectionCoord(lane.getX());
        int cz = SectionPos.blockToSectionCoord(lane.getZ());
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                ForcedChunks.set(level, cx + ox, cz + oz, forced);
            }
        }
    }
}

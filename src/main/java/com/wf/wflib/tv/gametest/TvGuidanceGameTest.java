package com.wf.wflib.tv.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileModels;
import com.wf.wflib.WFLib;
import com.wf.wflib.drone.cam.CameraNet;
import com.wf.wflib.drone.cam.CameraSpec;
import com.wf.wflib.flight.FlightStageRegistry;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.tv.TvGuidance;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;
import java.util.UUID;

/** TV rounds: operator's gimbal = line of sight flown; let go => seeker lock; only the launcher, only in range. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class TvGuidanceGameTest {

    private static final String TEMPLATE = "empty";
    /** Past the spin-out lanes (2000). */
    private static final int LANE_ORIGIN = 2600;
    private static final int LANE_SPACING = 160;
    private static final double ALTITUDE = 20.0;
    private static final double SPEED = 2.0;
    /** Yaw 0 = +Z; the round launches along +X. */
    private static final float NORTH_OFF_AXIS = 0.0f;

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void theOperatorsGimbalIsTheLineOfSightFlown(GameTestHelper helper) {
        Lane lane = new Lane(helper, 0);
        FakePlayer operator = operator(lane, 1);
        MissileEntity missile = lane.launch(operator.getUUID());
        take(operator, missile, NORTH_OFF_AXIS, 0.0f);
        helper.startSequence()
                .thenIdle(25)
                .thenExecute(() -> {
                    Vec3 heading = missile.getDeltaMovement().normalize();
                    boolean operated = missile.seeker().operated();
                    lane.close(missile);
                    if (!operated) {
                        helper.fail("the launcher's operator is on the stick and the round is not operated");
                    } else if (heading.z < 0.97) {
                        helper.fail(String.format(Locale.ROOT,
                                "ordered +Z off a +X launch; after 25 ticks heading is %.2f %.2f %.2f",
                                heading.x, heading.y, heading.z));
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void lettingGoLocksTheBlockUnderTheCrosshair(GameTestHelper helper) {
        Lane lane = new Lane(helper, 1);
        FakePlayer operator = operator(lane, 2);
        MissileEntity missile = lane.launch(operator.getUUID());
        int wallZ = lane.origin.getZ() + 60;
        for (int x = -8; x <= 80; x++) {
            for (int y = -6; y <= 6; y++) {
                lane.level.setBlockAndUpdate(new BlockPos(lane.origin.getX() + x, (int) lane.y() + y, wallZ),
                        Blocks.STONE.defaultBlockState());
            }
        }
        take(operator, missile, NORTH_OFF_AXIS, 0.0f);
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> CameraNet.unsubscribe(operator))
                .thenIdle(1)
                .thenExecute(() -> {
                    boolean operated = missile.seeker().operated();
                    Vec3 target = missile.flight().getTarget();
                    lane.close(missile);
                    if (operated) {
                        helper.fail("the operator let go and the round is still operated");
                    } else if (Math.abs(target.z - wallZ) > 0.01) {
                        helper.fail(String.format(Locale.ROOT, "seeker locked %.2f %.2f %.2f, not the wall face at z=%d",
                                target.x, target.y, target.z, wallZ));
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void lettingGoOnAnEntityHomesOnIt(GameTestHelper helper) {
        Lane lane = new Lane(helper, 2);
        FakePlayer operator = operator(lane, 3);
        MissileEntity missile = lane.launch(operator.getUUID());
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, lane.level);
        stand.setNoGravity(true);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    // Dead ahead of the round, wherever it has got to.
                    stand.moveTo(missile.getX() + 40.0, missile.getY() - 1.0, missile.getZ());
                    lane.level.addFreshEntity(stand);
                    take(operator, missile, -90.0f, 0.0f);
                })
                .thenIdle(3)
                .thenExecute(() -> CameraNet.unsubscribe(operator))
                .thenIdle(1)
                .thenExecute(() -> {
                    UUID designated = missile.seeker().getDesignatedTargetId();
                    lane.close(missile);
                    stand.discard();
                    if (!stand.getUUID().equals(designated)) {
                        helper.fail("let go with an armour stand in the sight line; designated " + designated);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void onlyTheLauncherMayFlyIt(GameTestHelper helper) {
        Lane lane = new Lane(helper, 3);
        FakePlayer launcher = operator(lane, 4);
        FakePlayer stranger = operator(lane, 5);
        MissileEntity missile = lane.launch(launcher.getUUID());
        boolean entitled = TvGuidance.entitled(lane.level, stranger, missile.getId());
        int offered = TvGuidance.connect(stranger);
        take(stranger, missile, NORTH_OFF_AXIS, 0.0f);
        helper.startSequence()
                .thenIdle(15)
                .thenExecute(() -> {
                    Vec3 heading = missile.getDeltaMovement().normalize();
                    boolean operated = missile.seeker().operated();
                    int own = TvGuidance.connect(launcher);
                    lane.close(missile);
                    if (entitled || offered != 0) {
                        helper.fail("a stranger is entitled (" + entitled + ") or offered round " + offered);
                    } else if (operated || heading.x < 0.97) {
                        helper.fail(String.format(Locale.ROOT, "a stranger steered it: operated %s, heading x %.2f",
                                operated, heading.x));
                    } else if (own != missile.getId()) {
                        helper.fail("its own launcher is offered round " + own + ", not " + missile.getId());
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void pastDatalinkRangeTheRoundFliesItsLock(GameTestHelper helper) {
        Lane lane = new Lane(helper, 4);
        FakePlayer operator = operator(lane, 6);
        MissileEntity missile = lane.launch(operator.getUUID());
        take(operator, missile, NORTH_OFF_AXIS, 0.0f);
        double range = CameraSpec.TV_SEEKER.downlinkRange();
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> operator.moveTo(missile.getX() - range, missile.getY(), missile.getZ()))
                .thenIdle(2)
                .thenExecute(() -> {
                    boolean operated = missile.seeker().operated();
                    Vec3 los = missile.flight().getTarget().subtract(missile.position());
                    lane.close(missile);
                    if (operated) {
                        helper.fail("operator " + range + " blocks out and still flying the round");
                    } else if (los.normalize().z < 0.5) {
                        helper.fail(String.format(Locale.ROOT,
                                "out of range the round should fly the last sight line (+Z); target is off %.2f %.2f %.2f",
                                los.x, los.y, los.z));
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void theTvPresetCarriesItsSeeker(GameTestHelper helper) {
        MissilePreset preset = MissilePresetRegistry.get(MissilePresetRegistry.rl("tv"));
        if (preset == null || preset.seeker() == null) {
            helper.fail("wflib:tv is missing or has no seeker");
            return;
        }
        MissileEntity missile = preset.build(helper.getLevel(), Vec3.ZERO);
        if (missile.seeker().spec() != CameraSpec.TV_SEEKER
                || !FlightStageRegistry.rl("direct").equals(missile.flight().getAttackStageId())) {
            helper.fail("wflib:tv builds seeker " + missile.seeker().spec() + ", attack stage " + missile.flight().getAttackStageId());
            return;
        }
        helper.succeed();
    }

    /** Subscribe + aim, as the operator's client would. */
    private static void take(FakePlayer player, MissileEntity missile, float yaw, float pitch) {
        CameraNet.subscribe(player, missile.getId());
        CameraNet.aim(player, missile.getId(), yaw, pitch, 1.0f, 0);
    }

    private static FakePlayer operator(Lane lane, int n) {
        FakePlayer player = FakePlayerFactory.get(lane.level,
                new GameProfile(new UUID(0x7654_0000L, n), "tv_operator_" + n));
        player.moveTo(lane.origin.getX() - 4.5, lane.y(), lane.origin.getZ() + 0.5);
        return player;
    }

    private static final class Lane {
        private final ServerLevel level;
        private final BlockPos origin;

        Lane(GameTestHelper helper, int index) {
            this.level = helper.getLevel();
            this.origin = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_ORIGIN + index * LANE_SPACING);
            force(true);
        }

        double y() {
            return this.origin.getY() + ALTITUDE;
        }

        MissileEntity launch(UUID launcher) {
            MissilePreset preset = MissilePreset.builder(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "test_tv"),
                            MissileModels.rl("atgm"), WarheadRegistry.rl("inert"))
                    .cruiseSpeed(SPEED).turnRate(0.12).accel(0.25, 0.25)
                    .ascentStage(FlightStageRegistry.rl("air_launch")).attackStage(FlightStageRegistry.rl("direct"))
                    .fuel(MissileEntity.FuelType.SOLID, 400)
                    .seeker(CameraSpec.TV_SEEKER)
                    .build();
            MissileEntity missile = preset.build(this.level,
                    new Vec3(this.origin.getX() + 400.5, y(), this.origin.getZ() + 0.5));
            missile.setControlId(launcher);
            missile.moveTo(this.origin.getX() + 0.5, y(), this.origin.getZ() + 0.5, -90.0f, 0.0f);
            missile.setDeltaMovement(new Vec3(SPEED, 0.0, 0.0));
            this.level.addFreshEntity(missile);
            return missile;
        }

        void close(MissileEntity missile) {
            missile.kill();
            force(false);
        }

        private void force(boolean forced) {
            int cx = SectionPos.blockToSectionCoord(this.origin.getX());
            int cz = SectionPos.blockToSectionCoord(this.origin.getZ());
            for (int ox = -1; ox <= 5; ox++) {
                for (int oz = -1; oz <= 5; oz++) {
                    ForcedChunks.set(this.level, cx + ox, cz + oz, forced);
                }
            }
        }
    }
}

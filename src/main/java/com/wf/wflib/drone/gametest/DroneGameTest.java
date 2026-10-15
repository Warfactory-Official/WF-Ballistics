package com.wf.wflib.drone.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.DroneSelfTest;
import com.wf.wflib.drone.sim.SimDrone;
import com.wf.wflib.drone.DroneState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class DroneGameTest {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE)
    public static void selfTestPasses(GameTestHelper helper) {
        List<String> failed = DroneSelfTest.runAll().stream().filter(r -> !r.passed())
                .map(r -> r.name() + ": " + r.detail()).toList();
        helper.assertTrue(failed.isEmpty(), "self-test failures: " + failed);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void simRoundTripKeepsSide(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        DroneEntity drone = new DroneEntity(helper.getLevel(), Vec3.atCenterOf(at.above(3)));
        UUID team = new UUID(7, 1);
        UUID operator = new UUID(7, 2);
        drone.setTeamId(team);
        drone.setOperatorId(operator);
        drone.setCallsign("HAWK");
        SimDrone sd = SimDrone.load(SimDrone.fromEntity(drone, null).save());
        DroneEntity back = sd.toEntity(helper.getLevel(), drone.position());
        helper.assertValueEqual(back.getTeamId(), team, "team");
        helper.assertValueEqual(back.getOperatorId(), operator, "operator");
        helper.assertValueEqual(back.callsign(), "HAWK", "callsign");
        helper.succeed();
    }

    /** Remote ride: body parked at the anchor; W + jump fly the drone along the look and up; release => body back. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aPilotFliesTheDroneFromTheirAnchor(GameTestHelper helper) {
        DroneEntity drone = new DroneEntity(helper.getLevel(), helper.absoluteVec(new Vec3(1.5, 30, 1.5)));
        helper.getLevel().addFreshEntity(drone);
        // FakePlayer refuses startRiding; a mock ServerPlayer trips mod login payloads.
        ArmorStand pilot = new ArmorStand(helper.getLevel(), 0, 0, 0);
        Vec3 anchor = helper.absoluteVec(new Vec3(1.5, 2, 1.5));
        pilot.moveTo(anchor.x, anchor.y, anchor.z, 0.0f, 0.0f);
        helper.getLevel().addFreshEntity(pilot);
        Vec3[] start = new Vec3[1];
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    drone.setDetachedBodyAnchor(pilot, pilot.position());
                    helper.assertTrue(pilot.startRiding(drone, true), "pilot could not board");
                    helper.assertTrue(drone.pilot() == pilot, "not the pilot");
                    start[0] = drone.position();
                    pilot.zza = 1.0f;
                    pilot.jumping = true;
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    Vec3 moved = drone.position().subtract(start[0]);
                    double parked = pilot.position().distanceTo(anchor);
                    pilot.zza = 0.0f;
                    pilot.jumping = false;
                    pilot.stopRiding();
                    double back = pilot.position().distanceTo(anchor);
                    boolean released = drone.pilot() == null && drone.getDetachedBodyAnchor(pilot) == null;
                    DroneState state = drone.flight().getDroneState();
                    drone.discard();
                    pilot.discard();
                    helper.assertTrue(moved.z > 2.0 && moved.y > 1.0 && state.airborne(),
                            "flew " + moved + ", state " + state);
                    helper.assertTrue(parked < 0.01 && back < 0.01 && released,
                            "body " + parked + " from anchor while flying, " + back + " after, released " + released);
                })
                .thenSucceed();
    }
}

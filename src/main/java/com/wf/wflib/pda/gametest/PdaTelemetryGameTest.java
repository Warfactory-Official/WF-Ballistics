package com.wf.wflib.pda.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.block.ModBlocks;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.DroneTask;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.item.ModDataComponents;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.pda.drone.PdaDrones;
import com.wf.wflib.telemetry.TelemetryLink;
import com.wf.wflib.telemetry.TelemetryLog;
import com.wf.wflib.telemetry.TelemetryLogPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class PdaTelemetryGameTest {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE)
    public static void logReachesOwnSideOnly(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        FakePlayer own = PdaUplinkGameTest.player(helper, 0xA1, 1, at);
        FakePlayer other = PdaUplinkGameTest.player(helper, 0xA2, 1, at);
        own.getInventory().add(new ItemStack(ModItems.PDA.get()));
        other.getInventory().add(new ItemStack(ModItems.PDA.get()));
        DroneEntity open = drone(helper, at, new UUID(0x9DA0_0000L, 0xA1));
        DroneEntity classified = drone(helper, at, new UUID(0x9DA0_0000L, 0xA1));
        classified.orders().setExchange(null, null, true, false, List.of(), List.of());

        open.recordEvent(WFEventType.CRUISE, "log test");
        classified.recordEvent(WFEventType.CRUISE, "log test");
        boolean ownHears = heard(own, open);
        boolean otherHears = heard(other, open);
        boolean classifiedHeard = heard(own, classified);
        open.discard();
        classified.discard();
        if (!ownHears || otherHears || classifiedHeard) {
            helper.fail("own " + ownHears + " other " + otherHears + " classified " + classifiedHeard);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void padDispatchesOfferedKindsOnly(GameTestHelper helper) {
        BlockPos pad = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(pad, ModBlocks.DRONE_PAD.get().defaultBlockState());
        FakePlayer player = PdaUplinkGameTest.player(helper, 0xA3, 1, pad.above());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PDA.get()));
        Vec3 target = Vec3.atCenterOf(pad).add(24, 0, 0);

        // onLoad (-> DroneDispatchers.add) runs at the next level tick.
        helper.runAfterDelay(1, () -> {
            String strike = PdaDrones.dispatch(player, DroneTask.Kind.STRIKE, target, java.util.Optional.empty());
            String loiter = PdaDrones.dispatch(player, DroneTask.Kind.LOITER, target, java.util.Optional.empty());
            CameraChannels channels = player.getMainHandItem().getOrDefault(ModDataComponents.CHANNELS.get(),
                    CameraChannels.EMPTY);
            boolean operated = false;
            for (DroneEntity e : helper.getLevel().getEntitiesOfClass(DroneEntity.class, new AABB(pad).inflate(8))) {
                operated |= player.getUUID().equals(e.datalink().operator());
                e.discard();
            }
            if (!strike.startsWith("err") || loiter.startsWith("err") || channels.size() != 1
                    || channels.targets().getFirst().drone().isEmpty() || !operated) {
                helper.fail("strike '" + strike + "' loiter '" + loiter + "' channels " + channels.size()
                        + " operated " + operated);
            }
            helper.succeed();
        });
    }

    private static DroneEntity drone(GameTestHelper helper, BlockPos at, UUID team) {
        DroneEntity drone = new DroneEntity(helper.getLevel(), Vec3.atCenterOf(at.above(3)));
        drone.setTeamId(team);
        helper.getLevel().addFreshEntity(drone);
        return drone;
    }

    private static boolean heard(FakePlayer viewer, Entity reporter) {
        long id = TelemetryLink.trackId(reporter.getUUID());
        for (TelemetryLogPacket.Entry e : TelemetryLog.since(viewer, 0).entries()) {
            if (e.id() == id && "log test".equals(e.detail())) {
                return true;
            }
        }
        return false;
    }
}

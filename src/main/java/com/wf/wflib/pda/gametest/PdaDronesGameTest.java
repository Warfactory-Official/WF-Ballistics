package com.wf.wflib.pda.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.item.ModDataComponents;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.pda.drone.DroneStatusPacket;
import com.wf.wflib.pda.drone.PdaDrones;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class PdaDronesGameTest {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE)
    public static void statusListsLinkedDronesOnly(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        DroneEntity drone = new DroneEntity(helper.getLevel(), Vec3.atCenterOf(at.above(3)));
        helper.getLevel().addFreshEntity(drone);
        FakePlayer player = PdaUplinkGameTest.player(helper, 9, 1, at);
        ItemStack pda = new ItemStack(ModItems.PDA.get());
        pda.set(ModDataComponents.CHANNELS.get(), new CameraChannels(List.of(
                FeedTarget.ofDrone(drone.getUUID()),
                FeedTarget.ofCamera(helper.getLevel(), at),
                FeedTarget.ofDrone(new UUID(0xD20E, 1))), 0));
        player.setItemInHand(InteractionHand.MAIN_HAND, pda);

        List<DroneStatusPacket.Entry> drones = PdaDrones.status(player).drones();
        drone.discard();
        if (drones.size() != 2 || !drones.get(0).loaded() || drones.get(0).status().isEmpty()
                || drones.get(1).loaded()) {
            helper.fail("drones " + drones);
        }
        helper.succeed();
    }

    /** Exchange (classified) => no feed id, no connect, status NO LINK. */
    @GameTest(template = TEMPLATE)
    public static void classifiedDroneShowsNoLink(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        DroneEntity drone = new DroneEntity(helper.getLevel(), Vec3.atCenterOf(at.above(3)));
        drone.orders().setExchange(null, null, true, false, List.of(), List.of());
        helper.getLevel().addFreshEntity(drone);
        FakePlayer player = PdaUplinkGameTest.player(helper, 9, 1, at);
        ItemStack pda = new ItemStack(ModItems.PDA.get());
        pda.set(ModDataComponents.CHANNELS.get(), new CameraChannels(List.of(FeedTarget.ofDrone(drone.getUUID())), 0));
        player.setItemInHand(InteractionHand.MAIN_HAND, pda);
        DroneStatusPacket.Entry entry = PdaDrones.status(player).drones().get(0);
        drone.discard();
        helper.assertTrue(entry.status().equals("NO LINK") && entry.feedId() == 0 && !entry.refusal().isEmpty(),
                "classified drone exposed: " + entry);
        helper.succeed();
    }
}

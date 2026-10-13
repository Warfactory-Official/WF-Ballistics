package com.wf.wflib.pda.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.block.ModBlocks;
import com.wf.wflib.block.entity.ReconHubBlockEntity;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.item.ModDataComponents;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.pda.terminal.PdaTerminal;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.grid.GridNode;
import com.wf.wflib.recon.grid.ReconGrid;
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
public class PdaTerminalGameTest {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE)
    public static void renamesLinkedDrone(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        DroneEntity drone = new DroneEntity(helper.getLevel(), Vec3.atCenterOf(at.above(3)));
        helper.getLevel().addFreshEntity(drone);
        FakePlayer player = PdaUplinkGameTest.player(helper, 0xB1, 1, at);
        ItemStack pda = new ItemStack(ModItems.PDA.get());
        pda.set(ModDataComponents.CHANNELS.get(), new CameraChannels(List.of(FeedTarget.ofDrone(drone.getUUID())), 0));
        player.setItemInHand(InteractionHand.MAIN_HAND, pda);

        String bad = PdaTerminal.run(player, "rename drone 1 a!b").getFirst();
        String ok = PdaTerminal.run(player, "rename drone #1 HAWK").getFirst();
        String listed = PdaTerminal.run(player, "drones").getFirst();
        String label = drone.datalink().label();
        drone.discard();
        if (!bad.startsWith("err") || ok.startsWith("err") || !listed.startsWith("#1 HAWK") || !label.equals("HAWK")) {
            helper.fail("bad '" + bad + "' ok '" + ok + "' listed '" + listed + "' label " + label);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void renamesOwnNetNode(GameTestHelper helper) {
        BlockPos hub = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(hub, ModBlocks.RECON_HUB.get().defaultBlockState());
        FakePlayer player = PdaUplinkGameTest.player(helper, 0xB2, 1, hub.above());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PDA.get()));
        UUID team = new UUID(0x9DA0_0000L, 0xB2);
        ((ReconHubBlockEntity) helper.getLevel().getBlockEntity(hub)).adopt(team);
        long net = ReconNet.netId(team);
        helper.runAfterDelay(5, () -> {
            String renamed = PdaTerminal.run(player, "rename node hub ALPHA").getFirst();
            helper.runAfterDelay(2, () -> {
                ReconGrid grid = ReconNet.grid(helper.getLevel(), net);
                GridNode node = grid == null ? null : grid.node(hub);
                String listed = PdaTerminal.run(player, "nodes").getFirst();
                if (renamed.startsWith("err") || node == null || !node.label().equals("ALPHA")
                        || !listed.startsWith("#1 ALPHA")) {
                    helper.fail("renamed '" + renamed + "' label " + (node == null ? null : node.label())
                            + " listed '" + listed + "'");
                }
                helper.succeed();
            });
        });
    }
}

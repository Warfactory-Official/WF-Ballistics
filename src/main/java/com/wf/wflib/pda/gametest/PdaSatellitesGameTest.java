package com.wf.wflib.pda.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.item.ModItems;
import com.wf.wflib.orbital.DeorbitReason;
import com.wf.wflib.orbital.OrbitElements;
import com.wf.wflib.orbital.OrbitalNet;
import com.wf.wflib.orbital.SatHandle;
import com.wf.wflib.orbital.SatPayloads;
import com.wf.wflib.orbital.SatSpec;
import com.wf.wflib.pda.sat.PdaSatellites;
import com.wf.wflib.pda.sat.SatStatusPacket;
import com.wf.wflib.pda.uplink.Uplink;
import com.wf.wflib.recon.ReconNet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Satellites app: own net's birds only, only with uplink; commands only to own birds. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class PdaSatellitesGameTest {
    private static final String TEMPLATE = "empty";
    private static final double LINK_RANGE = 64;

    private static long net(int team) {
        return ReconNet.netId(new UUID(0x9DA0_0000L, team));
    }

    private static boolean lists(SatStatusPacket status, String callsign) {
        for (SatStatusPacket.Sat sat : status.sats()) {
            if (sat.callsign().equals(callsign)) {
                return true;
            }
        }
        return false;
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void statusListsOwnBirdsOnlyWithUplink(GameTestHelper helper) {
        int team = 5;
        ServerLevel level = helper.getLevel();
        BlockPos hub = helper.absolutePos(new BlockPos(1, 2, 1));
        ReconNet.registerNode(level, hub, net(team), LINK_RANGE, 2, true, "hub");
        long now = level.getGameTime();
        SatHandle own = OrbitalNet.orbit(level, SatSpec.of(SatPayloads.INERT).netId(net(team)).callsign("PDA-OWN"),
                OrbitElements.leo(now, hub.getX(), hub.getZ(), 0));
        SatHandle other = OrbitalNet.orbit(level, SatSpec.of(SatPayloads.INERT).netId(net(team + 1))
                .callsign("PDA-OTHER"), OrbitElements.leo(now, hub.getX(), hub.getZ(), 0));
        FakePlayer player = PdaUplinkGameTest.player(helper, team, 1, hub.offset(20, 0, 0));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PDA.get()));
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    ReconNet.registerNode(level, hub, net(team), LINK_RANGE, 2, true, "hub");
                    SatStatusPacket status = PdaSatellites.status(player);
                    if (status.link() == Uplink.Link.NONE || !lists(status, "PDA-OWN") || lists(status, "PDA-OTHER")) {
                        helper.fail("uplinked: link " + status.link() + ", sats " + status.sats().stream()
                                .map(SatStatusPacket.Sat::callsign).toList());
                    }
                    player.moveTo(hub.getX() + 300.5, hub.getY(), hub.getZ() + 0.5);
                })
                .thenIdle(12)
                .thenExecute(() -> {
                    ReconNet.registerNode(level, hub, net(team), LINK_RANGE, 2, true, "hub");
                    SatStatusPacket status = PdaSatellites.status(player);
                    ReconNet.unregisterNode(level, hub, net(team));
                    OrbitalNet.deorbit(level, own.id(), DeorbitReason.COMMAND);
                    OrbitalNet.deorbit(level, other.id(), DeorbitReason.COMMAND);
                    if (status.link() != Uplink.Link.NONE || !status.sats().isEmpty()) {
                        helper.fail("300 blocks from the only hub: link " + status.link() + ", "
                                + status.sats().size() + " sats");
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void anotherNetsBirdTakesNoCommand(GameTestHelper helper) {
        int team = 7;
        ServerLevel level = helper.getLevel();
        BlockPos hub = helper.absolutePos(new BlockPos(1, 2, 1));
        ReconNet.registerNode(level, hub, net(team), LINK_RANGE, 2, true, "hub");
        SatHandle foreign = OrbitalNet.orbit(level, SatSpec.of(SatPayloads.INERT).netId(net(team + 1))
                .callsign("PDA-FOREIGN"), OrbitElements.leo(level.getGameTime(), hub.getX(), hub.getZ(), 0));
        FakePlayer player = PdaUplinkGameTest.player(helper, team, 1, hub.offset(10, 0, 0));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PDA.get()));
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    ReconNet.registerNode(level, hub, net(team), LINK_RANGE, 2, true, "hub");
                    PdaSatellites.command(player, foreign.id().value(), "callsign", List.of("HIJACKED"));
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    String callsign = OrbitalNet.view(level, foreign.id()).orElseThrow().callsign();
                    ReconNet.unregisterNode(level, hub, net(team));
                    OrbitalNet.deorbit(level, foreign.id(), DeorbitReason.COMMAND);
                    if (!callsign.equals("PDA-FOREIGN")) {
                        helper.fail("another net's bird took a PDA command: callsign now " + callsign);
                    }
                })
                .thenSucceed();
    }
}

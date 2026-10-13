package com.wf.wflib.pda.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.wflib.WFLib;
import com.wf.wflib.pda.uplink.Uplink;
import com.wf.wflib.recon.OwnerSource;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconOwners;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** PDA uplink: own net's online node within its link range => ground link; other nets' nodes never. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class PdaUplinkGameTest {
    private static final String TEMPLATE = "empty";
    private static final double LINK_RANGE = 64;
    /** Player name `pda_<hex team>_<n>` => side = that team. Tests use their own team: arenas share a world. */
    private static boolean sourced;

    static synchronized void source() {
        if (sourced) {
            return;
        }
        sourced = true;
        ReconOwners.register(new OwnerSource() {
            @Override
            public int priority() {
                return 100;
            }

            @Override
            @Nullable
            public UUID owningAt(ServerLevel level, BlockPos pos) {
                return null;
            }

            @Override
            @Nullable
            public UUID owningEntity(Entity entity) {
                String name = entity.getName().getString();
                if (!(entity instanceof FakePlayer) || !name.startsWith("pda_")) {
                    return null;
                }
                return new UUID(0x9DA0_0000L, Long.parseLong(name.split("_")[1], 16));
            }
        });
    }

    static FakePlayer player(GameTestHelper helper, int team, int n, BlockPos at) {
        source();
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(new UUID(0x9DA1_0000L, team * 16L + n), "pda_" + Integer.toHexString(team) + "_" + n));
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return player;
    }

    private static long net(int team) {
        return ReconNet.netId(new UUID(0x9DA0_0000L, team));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void groundLinkOnlyWithinTheNodesRange(GameTestHelper helper) {
        int team = 1;
        BlockPos hub = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerLevel level = helper.getLevel();
        ReconNet.registerNode(level, hub, net(team), LINK_RANGE, 2, true, "hub");
        FakePlayer near = player(helper, team, 1, hub.offset(30, 0, 0));
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    ReconNet.registerNode(level, hub, net(team), LINK_RANGE, 2, true, "hub");
                    Uplink.Link link = Uplink.of(near);
                    if (link != Uplink.Link.GROUND || !Uplink.allows(near, net(team)) || Uplink.allows(near, net(9))) {
                        helper.fail("30 blocks from an own hub (range 64): link " + link + ", allows own "
                                + Uplink.allows(near, net(team)) + ", allows other " + Uplink.allows(near, net(9)));
                    }
                    near.moveTo(hub.getX() + 200.5, hub.getY(), hub.getZ() + 0.5);
                })
                .thenIdle(12)
                .thenExecute(() -> {
                    ReconNet.registerNode(level, hub, net(team), LINK_RANGE, 2, true, "hub");
                    Uplink.Link link = Uplink.of(near);
                    ReconNet.unregisterNode(level, hub, net(team));
                    if (link != Uplink.Link.NONE) {
                        helper.fail("200 blocks from the only hub (range 64): link " + link);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void anotherNetsNodeIsNoLink(GameTestHelper helper) {
        BlockPos hub = helper.absolutePos(new BlockPos(1, 2, 1));
        ServerLevel level = helper.getLevel();
        ReconNet.registerNode(level, hub, net(2), LINK_RANGE, 2, true, "hub");
        FakePlayer stranger = player(helper, 3, 1, hub.offset(5, 0, 0));
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    Uplink.Link link = Uplink.of(stranger);
                    ReconNet.unregisterNode(level, hub, net(2));
                    if (link != Uplink.Link.NONE || Uplink.allows(stranger, net(2))) {
                        helper.fail("beside another net's hub: link " + link + ", allows " + Uplink.allows(stranger,
                                net(2)));
                    }
                })
                .thenSucceed();
    }
}

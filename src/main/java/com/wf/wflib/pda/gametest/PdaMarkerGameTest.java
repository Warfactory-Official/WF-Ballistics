package com.wf.wflib.pda.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.pda.markers.MarkerStore;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Markers are per side: a teammate shares them, another side never sees them, the cap holds. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class PdaMarkerGameTest {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void markersStayOnTheirSide(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        FakePlayer a1 = PdaUplinkGameTest.player(helper, 0x41, 1, at);
        FakePlayer a2 = PdaUplinkGameTest.player(helper, 0x41, 2, at);
        FakePlayer b1 = PdaUplinkGameTest.player(helper, 0x42, 1, at);
        MarkerStore store = MarkerStore.get(helper.getLevel().getServer());
        UUID sideA = MarkerStore.side(a1);
        ResourceLocation dim = helper.getLevel().dimension().location();
        int before = store.of(sideA).size();
        for (int i = 0; i < MarkerStore.CAP + 3; i++) {
            store.add(sideA, dim, at, "M" + i);
        }
        int teammate = store.of(MarkerStore.side(a2)).size();
        int other = store.of(MarkerStore.side(b1)).size();
        for (var m : java.util.List.copyOf(store.of(sideA))) {
            store.remove(sideA, m.id());
        }
        if (before != 0 || teammate != MarkerStore.CAP || other != 0) {
            helper.fail("before " + before + ", teammate sees " + teammate + " (cap " + MarkerStore.CAP + "), other side "
                    + other);
        }
        helper.succeed();
    }
}

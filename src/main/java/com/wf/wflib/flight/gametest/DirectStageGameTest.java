package com.wf.wflib.flight.gametest;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.WFLib;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/** TOW fired at a sky aim point (sight line end, nothing hit): passes it and flies on, never loops back. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class DirectStageGameTest {

    private static final String TEMPLATE = "empty";
    /** Past TV lanes (2600..3240). */
    private static final int LANE_ORIGIN = 3600;
    private static final double ALTITUDE = 40.0;
    private static final int TICKS = 160;

    @GameTest(template = TEMPLATE, timeoutTicks = TICKS + 20)
    public static void anAtgmPastASkyAimPointFliesStraightOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_ORIGIN);
        Vec3 from = new Vec3(lane.getX() + 0.5, lane.getY() + ALTITUDE, lane.getZ() + 0.5);
        Vec3 aim = from.add(60.0, 20.0, 0.0);
        MissileEntity missile = MissilePresetRegistry.get(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "tow"))
                .build(level, aim);
        Vec3 dir = aim.subtract(from).normalize();
        missile.moveTo(from.x, from.y, from.z, (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)),
                (float) Math.toDegrees(-Math.asin(dir.y)));
        int cx = SectionPos.blockToSectionCoord(lane.getX()), cz = SectionPos.blockToSectionCoord(lane.getZ());
        for (int ox = -1; ox <= 2; ox++) {
            ForcedChunks.set(level, cx + ox, cz, true);
        }
        level.addFreshEntity(missile);
        double[] worst = {Double.POSITIVE_INFINITY};
        int[] tick = {0};
        helper.onEachTick(() -> {
            if (!missile.isRemoved() && tick[0]++ > 5) {
                Vec3 v = missile.getDeltaMovement();
                worst[0] = Math.min(worst[0], v.lengthSqr() < 1.0E-8 ? 1.0 : v.normalize().dot(dir));
            }
        });
        helper.runAfterDelay(TICKS, () -> {
            double x = missile.getX() - from.x;
            boolean removed = missile.isRemoved();
            missile.discard();
            for (int ox = -1; ox <= 2; ox++) {
                ForcedChunks.set(level, cx + ox, cz, false);
            }
            if (removed) {
                helper.fail("round gone before " + TICKS + " ticks in open sky");
            } else if (x < 120.0) {
                helper.fail(String.format(Locale.ROOT, "round at +%.1f, short of 2x the aim point (+60)", x));
            } else if (worst[0] < 0.5) {
                helper.fail(String.format(Locale.ROOT, "round turned back: heading . launch line %.2f", worst[0]));
            } else {
                helper.succeed();
            }
        });
    }
}

package com.wf.wflib.mine.gametest;

import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.WFLib;
import com.wf.wflib.item.MinePreset;
import com.wf.wflib.item.MinePresetRegistry;
import com.wf.wflib.mine.MineEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Does a naval mine come to rest moored, whichever way it arrived in the water? */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class NavalMineGameTest {

    private static final String TEMPLATE = "empty";

    private static final int TANK_DEPTH = 12;
    /** Shallower than one tick of a drone's drop, which is the whole point of the third test. */
    private static final int SHALLOW_DEPTH = 3;
    private static final int TANK_HALF = 3;
    private static final int LANE_ORIGIN = 448;
    private static final int LANE_SPACING = 32;
    private static final int SETTLE_TICKS = 120;

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aDroppedNavalMineMoors(GameTestHelper helper) {
        run(helper, 0, 10.0, mine -> {
            if (!mine.isMoored()) {
                helper.fail("dropped: moored=" + mine.isMoored() + " wet=" + mine.isWet()
                        + " y=" + String.format("%.3f", mine.getY())
                        + " motion=" + mine.getDeltaMovement());
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aPlacedNavalMineMoors(GameTestHelper helper) {
        run(helper, 1, 0.0, mine -> {
            if (!mine.isMoored()) {
                helper.fail("placed: moored=" + mine.isMoored() + " wet=" + mine.isWet()
                        + " y=" + String.format("%.3f", mine.getY())
                        + " motion=" + mine.getDeltaMovement());
                return;
            }
            helper.succeed();
        });
    }

    /** A drone drops from a height and adds the run's own speed to it, so its mines arrive fast. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aMineThrownIntoShallowWaterStillMoors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos tank = helper.absolutePos(BlockPos.ZERO)
                .offset(0, 0, LANE_ORIGIN + 2 * LANE_SPACING);
        force(level, tank, true);
        int bed = buildTank(level, tank, SHALLOW_DEPTH);

        MinePreset preset = MinePresetRegistry.get(MinePresetRegistry.rl("naval"));
        MineEntity mine = preset.build(level, 0.0f);
        mine.moveTo(tank.getX() + 0.5, bed + SHALLOW_DEPTH + 1.0, tank.getZ() + 0.5, 0.0f, 0.0f);
        level.addFreshEntity(mine);
        // Straight down, faster than the water is deep.
        mine.scatter(new Vec3(0.0, -4.0, 0.0), level.getRandom(), 0.0f);

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            force(level, tank, false);
            if (!mine.isAlive() || mine.isRemoved()) {
                helper.fail("the mine did not survive");
                return;
            }
            if (!mine.isMoored()) {
                helper.fail("thrown: moored=" + mine.isMoored() + " wet=" + mine.isWet()
                        + " y=" + String.format("%.3f", mine.getY()) + " bed=" + bed
                        + " motion=" + mine.getDeltaMovement());
                return;
            }
            helper.succeed();
        });
    }

    private interface Check {
        void run(MineEntity mine);
    }

    private static void run(GameTestHelper helper, int lane, double dropHeight, Check check) {
        ServerLevel level = helper.getLevel();
        BlockPos tank = helper.absolutePos(BlockPos.ZERO)
                .offset(0, 0, LANE_ORIGIN + lane * LANE_SPACING);
        force(level, tank, true);
        int bed = buildTank(level, tank, TANK_DEPTH);
        double surface = bed + TANK_DEPTH;

        MinePreset preset = MinePresetRegistry.get(MinePresetRegistry.rl("naval"));
        MineEntity mine = preset.build(level, 0.0f);
        mine.moveTo(tank.getX() + 0.5, surface + dropHeight, tank.getZ() + 0.5, 0.0f, 0.0f);
        level.addFreshEntity(mine);

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            force(level, tank, false);
            if (!mine.isAlive() || mine.isRemoved()) {
                helper.fail("the mine did not survive");
                return;
            }
            check.run(mine);
        });
    }

    private static int buildTank(ServerLevel level, BlockPos tank, int depth) {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        int floorY = tank.getY() - 1;
        int wall = TANK_HALF + 1;
        for (int x = -wall; x <= wall; x++) {
            for (int z = -wall; z <= wall; z++) {
                boolean rim = x == -wall || x == wall || z == -wall || z == wall;
                BlockPos column = tank.offset(x, 0, z);
                level.setBlock(column.atY(floorY), stone, 2);
                for (int y = 0; y <= depth + 16; y++) {
                    level.setBlock(column.atY(floorY + 1 + y),
                            rim && y < depth ? stone : (y < depth ? water : air), 2);
                }
            }
        }
        return floorY + 1;
    }

    private static void force(ServerLevel level, BlockPos tank, boolean forced) {
        int cx = SectionPos.blockToSectionCoord(tank.getX());
        int cz = SectionPos.blockToSectionCoord(tank.getZ());
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                ForcedChunks.set(level, cx + ox, cz + oz, forced);
            }
        }
    }
}

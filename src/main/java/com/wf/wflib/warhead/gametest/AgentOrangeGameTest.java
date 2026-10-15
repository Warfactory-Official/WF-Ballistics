package com.wf.wflib.warhead.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.entity.AgentOrangeEntity;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.AgentOrangeWarhead;
import com.wf.wflib.warhead.WarheadCarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Spray from above a tree: canopy + trunk gone inside the footprint; ground, buried log, outside log, mob intact. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class AgentOrangeGameTest {

    private static final String TEMPLATE = "empty";
    /** Past BlastAhfGameTest (5200..5520). */
    private static final int LANE_ORIGIN = 5700;
    private static final int RADIUS = 4;

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aSprayStripsTheTreeDownToTheGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).offset(0, 20, LANE_ORIGIN);
        force(level, base, true);
        BlockPos buried = base.below(2);
        BlockPos outside = base.east(RADIUS + 3);
        Cow[] cow = new Cow[1];
        float[] full = new float[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(base), "lane loading"))
                .thenExecute(() -> {
                    for (int dx = -RADIUS - 4; dx <= RADIUS + 4; dx++) {
                        for (int dz = -RADIUS - 4; dz <= RADIUS + 4; dz++) {
                            level.setBlockAndUpdate(base.offset(dx, -2, dz), Blocks.STONE.defaultBlockState());
                            level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState());
                        }
                    }
                    level.setBlockAndUpdate(buried, Blocks.OAK_LOG.defaultBlockState());
                    for (int dy = 3; dy <= 7; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.OAK_LEAVES.defaultBlockState());
                            }
                        }
                    }
                    for (int dy = 0; dy <= 5; dy++) {
                        level.setBlockAndUpdate(base.above(dy), Blocks.OAK_LOG.defaultBlockState());
                        level.setBlockAndUpdate(outside.above(dy), Blocks.OAK_LOG.defaultBlockState());
                    }
                    cow[0] = EntityType.COW.create(level);
                    cow[0].moveTo(base.getX() + 3.5, base.getY(), base.getZ() + 0.5, 0.0f, 0.0f);
                    cow[0].setNoAi(true);
                    level.addFreshEntity(cow[0]);
                    full[0] = cow[0].getHealth();
                    AgentOrangeWarhead.detonate(carrier(level), Vec3.atBottomCenterOf(base.above(12)));
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getEntitiesOfClass(AgentOrangeEntity.class,
                        new AABB(base).inflate(32)).isEmpty(), "spray still falling"))
                .thenExecute(() -> {
                    force(level, base, false);
                    for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                            if (dx * dx + dz * dz > RADIUS * RADIUS) {
                                continue;
                            }
                            for (int dy = 0; dy <= 12; dy++) {
                                BlockPos p = base.offset(dx, dy, dz);
                                helper.assertTrue(!level.getBlockState(p).is(AgentOrangeWarhead.TARGETS),
                                        "target left at " + p.subtract(base));
                            }
                            helper.assertTrue(level.getBlockState(base.offset(dx, -1, dz)).is(Blocks.DIRT),
                                    "ground turned at " + dx + "," + dz);
                        }
                    }
                    helper.assertTrue(level.getBlockState(buried).is(Blocks.OAK_LOG), "log under the dirt turned");
                    helper.assertTrue(level.getBlockState(outside).is(Blocks.OAK_LOG), "log outside the footprint turned");
                    helper.assertTrue(cow[0].isAlive() && cow[0].getHealth() >= full[0], "cow hurt");
                })
                .thenSucceed();
    }

    private static WarheadCarrier carrier(Level level) {
        return new WarheadCarrier() {
            @Override
            public Level level() {
                return level;
            }

            @Override
            public int getFragmentCount() {
                return 0;
            }

            @Override
            public Vec3 angle() {
                return new Vec3(0.0, -1.0, 0.0);
            }

            @Override
            public float blastSize() {
                return RADIUS;
            }
        };
    }

    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        int cx = SectionPos.blockToSectionCoord(at.getX());
        int cz = SectionPos.blockToSectionCoord(at.getZ());
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                ForcedChunks.set(level, cx + ox, cz + oz, forced);
            }
        }
    }
}

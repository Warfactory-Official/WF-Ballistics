package com.wf.wflib.warhead.gametest;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.WFLib;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.WarheadCarrier;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/** Shaped charge vs a mob the jet passes through: contact anywhere on the box, and a TV round flown into it. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class ShapedChargeGameTest {

    private static final String TEMPLATE = "empty";
    /** Past DirectStage (3600). */
    private static final int LANE_ORIGIN = 4400;
    private static final int LANE_SPACING = 160;
    private static final double ALTITUDE = 20.0;

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void contactAtTheBoxCentreHeightHurts(GameTestHelper helper) {
        contact(helper, 0, 0.5);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void contactNearTheTopOfTheBoxHurts(GameTestHelper helper) {
        contact(helper, 1, 0.9);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void contactNearTheBottomOfTheBoxHurts(GameTestHelper helper) {
        contact(helper, 2, 0.1);
    }

    /** 45 deg off a 22 deg jet, nearest box edge ~3.5 blocks off axis vs cone radius ~1.6 there. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aMobBesideTheJetIsSpared(GameTestHelper helper) {
        Lane lane = new Lane(helper, 4);
        lane.whenTicking(() -> {
            Cow cow = lane.cow(4.0);
            float full = cow.getHealth();
            Vec3 at = cow.getBoundingBox().getCenter().add(-4.0, 0.0, -4.0);
            WarheadRegistry.SHAPED_CHARGE.detonate(carrier(lane.level, new Vec3(1.0, 0.0, 0.0)), at);
            helper.runAfterDelay(2, () -> {
                lane.release();
                helper.assertTrue(cow.isAlive() && cow.getHealth() >= full,
                        "cow beside the jet hurt, hp " + cow.getHealth());
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aTvRoundFlownIntoAMobHurtsIt(GameTestHelper helper) {
        Lane lane = new Lane(helper, 3);
        Cow cow = lane.cow(30.0);
        float full = cow.getHealth();
        Vec3 aim = cow.getBoundingBox().getCenter();
        MissileEntity missile = MissilePresetRegistry.get(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "tv"))
                .build(lane.level, aim);
        missile.moveTo(lane.origin.getX() + 0.5, aim.y, aim.z, -90.0f, 0.0f);
        missile.setDeltaMovement(new Vec3(2.0, 0.0, 0.0));
        lane.level.addFreshEntity(missile);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(missile.isRemoved(), "round still flying"))
                .thenIdle(2)
                .thenExecute(() -> lane.assertHurt(cow, full, "TV round"))
                .thenSucceed();
    }

    /** Detonate on the cow's -X face at {@code height} (fraction of the box), jet along +X. */
    private static void contact(GameTestHelper helper, int index, double height) {
        Lane lane = new Lane(helper, index);
        lane.whenTicking(() -> {
            Cow cow = lane.cow(4.0);
            float full = cow.getHealth();
            AABB box = cow.getBoundingBox();
            Vec3 at = new Vec3(box.minX, box.minY + box.getYsize() * height, (box.minZ + box.maxZ) * 0.5);
            WarheadRegistry.SHAPED_CHARGE.detonate(carrier(lane.level, new Vec3(1.0, 0.0, 0.0)), at);
            helper.runAfterDelay(2, () -> {
                lane.assertHurt(cow, full, String.format(Locale.ROOT, "contact at %.0f%% height", height * 100));
                helper.succeed();
            });
        });
    }

    private static WarheadCarrier carrier(Level level, Vec3 axis) {
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
                return axis;
            }

            @Override
            public boolean breaksBlocks() {
                return false;
            }
        };
    }

    private static final class Lane {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos origin;

        Lane(GameTestHelper helper, int index) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.origin = helper.absolutePos(BlockPos.ZERO).offset(0, (int) ALTITUDE, LANE_ORIGIN + index * LANE_SPACING);
            force(true);
        }

        /** Forced lane chunks load async; a blast before they tick finds no entities. */
        void whenTicking(Runnable then) {
            BlockPos at = this.origin.offset(4, 0, 0);
            this.helper.startSequence()
                    .thenWaitUntil(() -> this.helper.assertTrue(this.level.isPositionEntityTicking(at), "lane loading"))
                    .thenExecute(then);
        }

        Cow cow(double dx) {
            Cow cow = EntityType.COW.create(this.level);
            cow.moveTo(this.origin.getX() + 0.5 + dx, this.origin.getY(), this.origin.getZ() + 0.5, 0.0f, 0.0f);
            cow.setNoAi(true);
            cow.setNoGravity(true);
            this.level.addFreshEntity(cow);
            return cow;
        }

        void release() {
            force(false);
        }

        void assertHurt(Cow cow, float full, String what) {
            release();
            if (cow.isAlive() && cow.getHealth() >= full) {
                this.helper.fail(what + ": cow untouched, hp " + cow.getHealth() + " of " + full);
            }
        }

        private void force(boolean forced) {
            int cx = SectionPos.blockToSectionCoord(this.origin.getX());
            int cz = SectionPos.blockToSectionCoord(this.origin.getZ());
            for (int ox = -1; ox <= 3; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    ForcedChunks.set(this.level, cx + ox, cz + oz, forced);
                }
            }
        }
    }
}

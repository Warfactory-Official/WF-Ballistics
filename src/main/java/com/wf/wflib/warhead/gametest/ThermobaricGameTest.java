package com.wf.wflib.warhead.gametest;

import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.ammo.AmmoItems;
import com.wf.wflib.api.StrappedExplosive;
import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.warhead.ThermobaricWarhead;
import com.wf.wflib.warhead.WarheadCarrier;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/** Thermobaric reach vs STANDARD at one size; strapped cartridge mass; released cartridge falls onto a victim. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class ThermobaricGameTest {

    private static final String TEMPLATE = "empty";
    /** Past AgentOrangeGameTest (5700). */
    private static final int LANE_ORIGIN = 6400;
    private static final int LANE_SPACING = 160;
    private static final double ALTITUDE = 20.0;
    private static final float SIZE = 5f;
    private static final double[] DISTANCES = {3, 5, 7, 9, 11, 13};

    private static ResourceLocation wflib(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
    }

    /** Golem fan at {@link #DISTANCES}: thermobaric hurts strictly more of them than STANDARD, never fewer. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void thermobaricOutreachesStandard(GameTestHelper helper) {
        Lane standard = new Lane(helper, 0);
        Lane thermobaric = new Lane(helper, 1);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(standard.ticking() && thermobaric.ticking(), "lanes loading"))
                .thenExecute(() -> {
                    IronGolem[] a = standard.fan();
                    IronGolem[] b = thermobaric.fan();
                    WarheadRegistry.STANDARD.detonate(carrier(standard.level), standard.centre());
                    ThermobaricWarhead.detonate(carrier(thermobaric.level), thermobaric.centre());
                    helper.runAfterDelay(2, () -> {
                        standard.release();
                        thermobaric.release();
                        StringBuilder table = new StringBuilder("d std tbx:");
                        int hitA = 0;
                        int hitB = 0;
                        for (int i = 0; i < DISTANCES.length; i++) {
                            float da = a[i].getMaxHealth() - a[i].getHealth();
                            float db = b[i].getMaxHealth() - b[i].getHealth();
                            hitA += da > 0 ? 1 : 0;
                            hitB += db > 0 ? 1 : 0;
                            table.append(String.format(Locale.ROOT, " [%.0f %.1f %.1f]", DISTANCES[i], da, db));
                        }
                        LogUtils.getLogger().info("ThermobaricGameTest {}", table);
                        helper.assertTrue(hitB > hitA && hitA > 0, table.toString());
                        helper.succeed();
                    });
                });
    }

    /** Strapped mass = calibre - default projectile + variant's; tag covers rocket + mortar + VOG. */
    @GameTest(template = TEMPLATE)
    public static void strappedMassFollowsTheVariant(GameTestHelper helper) {
        String[][] cases = {{"rpg7/heat", "2.6"}, {"rpg7/thermobaric", "4.5"}, {"rpg7/frag", "1.75"},
                {"81mm/he", "4.1"}, {"60mm/he", "1.72"}, {"vog25/he", "0.25"}, {"30x29/he", "0.35"}};
        for (String[] c : cases) {
            StrappedExplosive s = StrappedExplosive.of(AmmoItems.stack(wflib(c[0]), 1));
            helper.assertTrue(s != null, c[0] + " not strappable");
            helper.assertTrue(Math.abs(s.massKg() - Float.parseFloat(c[1])) < 0.005f,
                    c[0] + " mass " + s.massKg() + ", want " + c[1]);
        }
        helper.assertTrue(StrappedExplosive.of(AmmoItems.stack(wflib("40x46/he"), 1)) == null,
                "40x46 strappable");
        helper.succeed();
    }

    /** OG-7V let go 6 blocks above a golem: falls, hits, fires its warhead. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aReleasedChargeFallsOntoItsTarget(GameTestHelper helper) {
        Lane lane = new Lane(helper, 2);
        IronGolem[] golem = new IronGolem[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(lane.ticking(), "lane loading"))
                .thenExecute(() -> {
                    golem[0] = lane.golem(lane.centre());
                    StrappedExplosive.of(AmmoItems.stack(wflib("rpg7/frag"), 1)).release(lane.level,
                            golem[0].position().add(0, 6, 0), Vec3.ZERO, null, null);
                })
                .thenWaitUntil(() -> helper.assertTrue(golem[0].getHealth() < golem[0].getMaxHealth(),
                        "golem untouched"))
                .thenExecute(lane::release)
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
                return new Vec3(0, -1, 0);
            }

            @Override
            public float blastSize() {
                return SIZE;
            }

            @Override
            public boolean breaksBlocks() {
                return false;
            }
        };
    }

    private static final class Lane {
        private final ServerLevel level;
        private final BlockPos origin;

        Lane(GameTestHelper helper, int index) {
            this.level = helper.getLevel();
            this.origin = helper.absolutePos(BlockPos.ZERO).offset(0, (int) ALTITUDE,
                    LANE_ORIGIN + index * LANE_SPACING);
            force(true);
        }

        boolean ticking() {
            return this.level.isPositionEntityTicking(this.origin);
        }

        Vec3 centre() {
            return Vec3.atCenterOf(this.origin);
        }

        /** One golem per distance, 60 deg apart: none shadows another. */
        IronGolem[] fan() {
            IronGolem[] out = new IronGolem[DISTANCES.length];
            for (int i = 0; i < out.length; i++) {
                double a = Math.toRadians(60.0 * i);
                out[i] = golem(centre().add(Math.cos(a) * DISTANCES[i], -0.5, Math.sin(a) * DISTANCES[i]));
            }
            return out;
        }

        IronGolem golem(Vec3 at) {
            IronGolem g = EntityType.IRON_GOLEM.create(this.level);
            g.moveTo(at.x, at.y, at.z, 0.0f, 0.0f);
            g.setNoAi(true);
            g.setNoGravity(true);
            this.level.addFreshEntity(g);
            return g;
        }

        void release() {
            force(false);
        }

        private void force(boolean forced) {
            int cx = SectionPos.blockToSectionCoord(this.origin.getX());
            int cz = SectionPos.blockToSectionCoord(this.origin.getZ());
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    ForcedChunks.set(this.level, cx + ox, cz + oz, forced);
                }
            }
        }
    }
}

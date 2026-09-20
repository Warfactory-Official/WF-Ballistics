package com.wf.wflib.flight.gametest;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.WFLib;
import com.wf.wflib.item.MissilePreset;
import com.wf.wflib.item.MissilePresetRegistry;
import com.wf.wflib.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Does a torpedo stay in the water, and at the depth it was set to? */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class TorpedoGameTest {

    private static final String TEMPLATE = "empty";

    /** Blocks of water in the tank, over a stone bed. Deeper than most ocean, so the corridor is roomy. */
    private static final int TANK_DEPTH = 24;
    /** Half-width of the tank across the run. */
    private static final int TANK_HALF_WIDTH = 5;
    /** How far the tank runs along +X, which is the direction everything here is aimed. */
    private static final int TANK_LENGTH = 56;
    /** How far off the arena grid the tanks are dug, in +Z, before the per-test lane spacing. */
    private static final int LANE_ORIGIN = 192;
    private static final int LANE_SPACING = 48;
    /** Chunk radius forced around a tank so the torpedo keeps being ticked while it runs. */
    private static final int FORCED_CHUNK_RADIUS = 2;

    /** How close to its commanded depth a settled torpedo has to be. Generous: the control is deadbanded. */
    private static final double DEPTH_TOLERANCE = 2.5;
    /** Ticks given to sink from just under the surface to the running depth and settle there. */
    private static final int SETTLE_TICKS = 60;
    private static final int DEEP_SETTLE_TICKS = 150;

    /** A torpedo preset that is not actually a torpedo is a missile that dives into the sea and stops. */
    @GameTest(template = TEMPLATE)
    public static void torpedoPresetsAreSubmerged(GameTestHelper helper) {
        for (MissilePreset preset : MissilePresetRegistry.all()) {
            boolean named = preset.id().getPath().startsWith("torpedo");
            MissileEntity missile = preset.build(helper.getLevel(), Vec3.ZERO);
            boolean submerged = missile.getMedium() == MissileEntity.Medium.WATER;
            missile.discard();
            if (named != submerged) {
                helper.fail(preset.id() + " is " + (named ? "named a torpedo but travels in air"
                        : "not named a torpedo but travels submerged"));
                return;
            }
            if (ModItems.missileItem(preset.id()).isEmpty()) {
                helper.fail("no item for preset " + preset.id());
                return;
            }
        }
        helper.succeed();
    }

    /** The client is told, and is told the medium rather than where the thing is standing. */
    @GameTest(template = TEMPLATE)
    public static void theMediumIsSynced(GameTestHelper helper) {
        MissileEntity torpedo = MissilePresetRegistry.get(MissilePresetRegistry.rl("torpedo"))
                .build(helper.getLevel(), Vec3.ZERO);
        MissileEntity cruise = MissilePresetRegistry.get(MissilePresetRegistry.rl("cruise"))
                .build(helper.getLevel(), Vec3.ZERO);
        boolean wrong = !torpedo.isSubmergedMedium() || cruise.isSubmergedMedium();
        torpedo.discard();
        cruise.discard();
        if (wrong) {
            helper.fail("the synced submerged flag does not match the medium");
            return;
        }
        helper.succeed();
    }

    /**
     * A torpedo set to a running depth holds it: eight blocks under the surface, not eight above the bed and not
     * wherever it happened to enter.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aSetDepthIsHeld(GameTestHelper helper) {
        runInTank(helper, 0, "torpedo", SETTLE_TICKS, (missile, bed, surface) -> {
            // The preset is highAltitude(8.0), which underwater is a depth below the surface.
            double want = surface - 8.0;
            double error = Math.abs(missile.getY() - want);
            if (error > DEPTH_TOLERANCE) {
                helper.fail("a torpedo set to run 8 under a surface at y=" + surface + " settled at y="
                        + String.format("%.2f", missile.getY()) + ", " + String.format("%.2f", error)
                        + " off the " + want + " it was asked for");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * A bottom-follower holds its clearance over the bed instead, which is the other cruise mode reflected into the
     * same medium.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aBottomFollowerHoldsTheBed(GameTestHelper helper) {
        runInTank(helper, 1, "torpedo_bottom", DEEP_SETTLE_TICKS, (missile, bed, surface) -> {
            // The preset is terrainFollow(3.0): three blocks over the first free block above the bed.
            double want = bed + 3.0;
            double error = Math.abs(missile.getY() - want);
            if (error > DEPTH_TOLERANCE) {
                helper.fail("a bottom-runner set 3 over a bed at y=" + bed + " settled at y="
                        + String.format("%.2f", missile.getY()) + ", " + String.format("%.2f", error)
                        + " off the " + want + " it was asked for");
                return;
            }
            helper.succeed();
        });
    }

    /** Whatever it is doing vertically, it does it under the water rather than through it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theRunStaysUnderTheSurface(GameTestHelper helper) {
        runInTank(helper, 2, "torpedo", SETTLE_TICKS, (missile, bed, surface) -> {
            if (missile.getY() > surface) {
                helper.fail("the torpedo is at y=" + missile.getY() + ", above a surface at y=" + surface);
                return;
            }
            if (!missile.isSubmerged()) {
                helper.fail("the torpedo is not in the water at the end of its run");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * And one that is taken out of the water for good is written off, rather than carrying on through the air,
     * which is what the whole medium would otherwise be: a suggestion.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aBroachedTorpedoIsWrittenOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos tank = tankOrigin(helper, 3);
        force(level, tank, true);
        int bed = buildTank(level, tank);
        double surface = bed + TANK_DEPTH;

        MissileEntity torpedo = launch(level, tank, bed, "torpedo");
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            if (!torpedo.isAlive() || torpedo.getPhase() == MissileEntity.Phase.ASCEND) {
                force(level, tank, false);
                helper.fail("the torpedo never reached its run, so there is nothing to broach");
                return;
            }
            torpedo.setPos(torpedo.getX(), surface + 40.0, torpedo.getZ());
            torpedo.setDeltaMovement(Vec3.ZERO);
            helper.runAfterDelay(40, () -> {
                boolean gone = torpedo.isRemoved() || !torpedo.isAlive();
                force(level, tank, false);
                if (!gone) {
                    helper.fail("a torpedo held clear of the water for 40 ticks is still running, at y="
                            + String.format("%.2f", torpedo.getY()));
                    return;
                }
                helper.succeed();
            });
        });
    }

    private interface Check {
        void run(MissileEntity missile, double bed, double surface);
    }

    /**
     * Builds the tank, launches the named preset just under the surface at one end aimed far past the other, and
     * hands the settled torpedo to {@code check}.
     */
    private static void runInTank(GameTestHelper helper, int lane, String presetPath, int settleTicks,
                                  Check check) {
        ServerLevel level = helper.getLevel();
        BlockPos tank = tankOrigin(helper, lane);
        force(level, tank, true);
        int bed = buildTank(level, tank);
        double surface = bed + TANK_DEPTH;

        MissileEntity missile = launch(level, tank, bed, presetPath);
        helper.runAfterDelay(settleTicks, () -> {
            force(level, tank, false);
            if (!missile.isAlive() || missile.isRemoved()) {
                helper.fail("the torpedo did not survive its run");
                return;
            }
            check.run(missile, bed, surface);
        });
    }

    /** This test's own stretch of water, far enough off the arena grid that nothing shares it. */
    private static BlockPos tankOrigin(GameTestHelper helper, int lane) {
        return helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_ORIGIN + lane * LANE_SPACING);
    }

    /** Spawns the preset two blocks under the surface at the near end, aimed down the tank. */
    private static MissileEntity launch(ServerLevel level, BlockPos tank, int bed, String presetPath) {
        ResourceLocation id = MissilePresetRegistry.rl(presetPath);
        Vec3 target = new Vec3(tank.getX() + 400.5, bed + 4.0, tank.getZ() + 0.5);
        MissileEntity missile = MissilePresetRegistry.get(id).build(level, target);
        missile.moveTo(tank.getX() + 0.5, bed + TANK_DEPTH - 2.0, tank.getZ() + 0.5, 90.0f, 0.0f);
        level.addFreshEntity(missile);
        return missile;
    }

    /**
     * A closed stone box with {@link #TANK_DEPTH} of water in it.
     *
     * @return the y of the first free block above the bed, which is what an {@code OCEAN_FLOOR} scan
     *      measures clearance from.
     */
    private static int buildTank(ServerLevel level, BlockPos tank) {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        int floorY = tank.getY() - 1;
        int wall = TANK_HALF_WIDTH + 1;

        for (int x = -2; x <= TANK_LENGTH + 1; x++) {
            for (int z = -wall; z <= wall; z++) {
                boolean rim = x == -2 || x == TANK_LENGTH + 1 || z == -wall || z == wall;
                BlockPos column = tank.offset(x, 0, z);
                level.setBlock(column.atY(floorY), stone, 2);
                for (int y = 0; y <= TANK_DEPTH; y++) {
                    level.setBlock(column.atY(floorY + 1 + y),
                            rim ? stone : (y < TANK_DEPTH ? water : air), 2);
                }
            }
        }
        return floorY + 1;
    }

    /** The arena ticks its own chunk and no others, and the tanks are not in it at all. */
    private static void force(ServerLevel level, BlockPos tank, boolean forced) {
        int cx = SectionPos.blockToSectionCoord(tank.getX());
        int cz = SectionPos.blockToSectionCoord(tank.getZ());
        for (int ox = -1; ox <= FORCED_CHUNK_RADIUS + 2; ox++) {
            for (int oz = -FORCED_CHUNK_RADIUS; oz <= FORCED_CHUNK_RADIUS; oz++) {
                level.setChunkForced(cx + ox, cz + oz, forced);
            }
        }
    }
}

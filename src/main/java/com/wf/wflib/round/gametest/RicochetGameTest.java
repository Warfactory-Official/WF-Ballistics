package com.wf.wflib.round.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.Rounds;
import com.wf.wflib.round.pen.BlockPen;
import com.wf.wflib.round.pen.PenTable;
import com.wf.wflib.round.pen.Ricochet;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/**
 * Ball (4 g, 200 m/s, blockPen 10, no drag / gravity) at a 12-block floor 30 up, {@code a} deg below level, one step:
 * skipped iff {@link Ricochet#bounce} with the round's seed says so. Floor placed and removed in one call: no other
 * test sees it. Stone c = 20: 3 deg P = 0.925, 30 / 60 deg P = 0.
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class RicochetGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation BALL = KineticPresetRegistry.rl("test_ricochet_ball");
    private static final double SPEED = 10.0;
    private static final int SHOTS = 24;
    private static final int FLOOR = 12;
    private static final int UP = 30;

    static {
        KineticPresetRegistry.register(KineticPreset.builder(BALL, null, WarheadRegistry.rl("inert"))
                .speed(SPEED).drag(0.0).gravity(0.0).life(100).mass(0.004).energyDamage(0.01).caliber(5.56)
                .blockPen(10.0).noChunkLoading().build());
    }

    @GameTest(template = TEMPLATE)
    public static void grazingRoundsSkipOffStoneSteepOnesStop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO).above(UP);
        floor(level, o, Blocks.STONE);
        try {
            int shallow = shots(helper, o, 3.0, Blocks.STONE, false);
            helper.assertTrue(shallow >= SHOTS / 2, "3 deg skipped " + shallow + " of " + SHOTS);
            helper.assertTrue(shots(helper, o, 30.0, Blocks.STONE, false) == 0, "30 deg skipped");
            helper.assertTrue(shots(helper, o, 60.0, Blocks.STONE, false) == 0, "60 deg skipped");
        } finally {
            floor(level, o, Blocks.AIR);
        }
        helper.succeed();
    }

    /** Water c = 7: 3 deg P = 0.79; 30 deg never. */
    @GameTest(template = TEMPLATE)
    public static void shallowRoundsSkimWaterSteepOnesDive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO).above(UP);
        floor(level, o, Blocks.WATER);
        try {
            int shallow = shots(helper, o, 3.0, Blocks.WATER, true);
            helper.assertTrue(shallow >= SHOTS / 3, "3 deg skimmed " + shallow + " of " + SHOTS);
            helper.assertTrue(shots(helper, o, 30.0, Blocks.WATER, true) == 0, "30 deg skimmed");
        } finally {
            floor(level, o, Blocks.AIR);
        }
        helper.succeed();
    }

    /** Armour owner answers {@code RICOCHET}: no damage, flies on at the given velocity, tumbling. */
    @GameTest(template = TEMPLATE)
    public static void armourGlanceFliesOnUnhurt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        Husk husk = new Husk(EntityType.HUSK, level);
        husk.setNoAi(true);
        husk.moveTo(o.getX() + 0.5, o.getY() + UP, o.getZ() + 0.5);
        level.addFreshEntity(husk);
        Vec3 glance = new Vec3(2.0, 8.0, 0.0);
        boolean[] allowed = new boolean[1];
        Consumer<ProjectileStrikeEvent> armour = e -> {
            if (e.target() == husk) {
                allowed[0] = e.canRicochet();
                e.ricochet(glance);
            }
        };
        NeoForge.EVENT_BUS.addListener(ProjectileStrikeEvent.class, armour);
        try {
            float health = husk.getHealth();
            long key = Rounds.launch(level, KineticPresetRegistry.get(BALL),
                    new Vec3(o.getX() + 0.5, o.getY() + UP - 3.0, o.getZ() + 0.5), new Vec3(0.0, SPEED, 0.0), null,
                    null);
            helper.assertTrue(Rounds.step(level, key), "round ended on the glance");
            helper.assertTrue(allowed[0], "round offered no ricochet");
            helper.assertTrue(Rounds.ricochets(level, key) == 1, "ricochets " + Rounds.ricochets(level, key));
            helper.assertTrue(glance.equals(Rounds.velocity(level, key)), "velocity " + Rounds.velocity(level, key));
            helper.assertTrue(husk.getHealth() == health, "glance hurt " + husk.getHealth());
            Rounds.destroy(level, key);
        } finally {
            NeoForge.EVENT_BUS.unregister(armour);
            husk.discard();
        }
        helper.succeed();
    }

    /**
     * {@link #SHOTS} at {@code deg} below level onto the floor top, each checked against {@link Ricochet#bounce}.
     * @return skipped
     */
    private static int shots(GameTestHelper helper, BlockPos o, double deg, Block surface, boolean water) {
        ServerLevel level = helper.getLevel();
        double a = Math.toRadians(deg);
        Vec3 v = new Vec3(Math.cos(a), -Math.sin(a), 0.0).scale(SPEED);
        float critical = water ? Ricochet.WATER : PenTable.ricochet(surface.defaultBlockState());
        int skipped = 0;
        for (int k = 0; k < SHOTS; k++) {
            // Voxel above the floor: a start inside the water voxel counts as wet.
            Vec3 from = new Vec3(o.getX() + 0.5, o.getY() + 1.05 + 0.01 * k, o.getZ() + 0.5);
            ClipContext clip = new ClipContext(from, from.add(v), ClipContext.Block.COLLIDER,
                    water ? ClipContext.Fluid.WATER : ClipContext.Fluid.NONE, CollisionContext.empty());
            BlockHitResult hit = level.clip(clip);
            helper.assertTrue(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().getY() == o.getY(),
                    deg + " deg shot " + k + " misses the floor: " + hit.getLocation());
            long key = Rounds.launch(level, KineticPresetRegistry.get(BALL), from, v, null, null);
            Vec3 want = Ricochet.bounce(v, Ricochet.UP, critical, water, BlockPen.seed(key, hit.getBlockPos()));
            boolean alive = Rounds.step(level, key);
            boolean skippedNow = alive && Rounds.ricochets(level, key) == 1;
            helper.assertTrue(skippedNow == (want != null), deg + " deg shot " + k + ": skipped " + skippedNow
                    + ", predicted " + want);
            if (skippedNow) {
                helper.assertTrue(Rounds.velocity(level, key).y > 0.0, "departure " + Rounds.velocity(level, key));
                skipped++;
            }
            Rounds.destroy(level, key);
        }
        return skipped;
    }

    private static void floor(ServerLevel level, BlockPos o, Block block) {
        for (int x = 0; x < FLOOR; x++) {
            level.setBlock(o.offset(x, 0, 0), block.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}

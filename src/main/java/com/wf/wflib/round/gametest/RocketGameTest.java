package com.wf.wflib.round.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import com.wf.wflib.recon.source.EntityTargetSource;
import com.wf.wflib.round.RocketEntity;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Rockets: motor then coast, contact fuze, air defence and the sensor net see them. */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = WFLib.MODID)
public class RocketGameTest {

    private static final String TEMPLATE = "empty";

    private static final ResourceLocation WARHEAD = WarheadRegistry.rl("test_rocket_warhead");
    private static final List<Vec3> DETONATIONS = new CopyOnWriteArrayList<>();
    private static final ResourceLocation ROCKET = KineticPresetRegistry.rl("test_rocket");
    /** No motor, no drag, 4 blocks/tick. */
    private static final ResourceLocation FAST = KineticPresetRegistry.rl("test_fast_rocket");
    private static final int BURN = 10;
    private static final double ACCEL = 0.3;
    private static final String WAVED = "wflib_test_waved";

    static {
        WarheadRegistry.register(WARHEAD, (source, pos) -> DETONATIONS.add(pos));
        KineticPresetRegistry.register(KineticPreset.builder(ROCKET, null, WARHEAD)
                .speed(1.0).motor(ACCEL, BURN).quadraticDrag(0.002).life(400).durability(6.0).build());
        KineticPresetRegistry.register(KineticPreset.builder(FAST, null, WARHEAD)
                .speed(4.0).drag(0.0).gravity(0.0).life(40).build());
    }

    /** Strikes on tagged bodies pass (a hull's miss, say). */
    @SubscribeEvent
    public static void onStrike(ProjectileStrikeEvent event) {
        if (event.projectile() instanceof RocketEntity && event.target().getTags().contains(WAVED)) {
            event.setOutcome(ProjectileStrikeEvent.Outcome.PASS);
        }
    }

    /** Template 3^3 in a barrier shell: flight tests stay above height 5. */
    private static Vec3 centre(GameTestHelper helper, double height) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return new Vec3(origin.getX() + 0.5, origin.getY() + height, origin.getZ() + 0.5);
    }

    private static RocketEntity fire(GameTestHelper helper, Vec3 from, Vec3 direction) {
        ServerLevel level = helper.getLevel();
        return RocketEntity.fire(level, KineticPresetRegistry.get(ROCKET), from, direction, 0.0f, Vec3.ZERO, null,
                null, null);
    }

    /** Faster than the muzzle after the burn; nose fixed while burning, then on the velocity. */
    @GameTest(template = TEMPLATE)
    public static void aRocketBurnsThenCoasts(GameTestHelper helper) {
        Vec3 up = new Vec3(0.3, 1.0, 0.0).normalize();
        RocketEntity rocket = fire(helper, centre(helper, 10.0), up);
        for (int i = 0; i < BURN; i++) {
            rocket.tick();
        }
        double burnt = rocket.getDeltaMovement().length();
        Vec3 noseBurning = rocket.getLookAngle();
        for (int i = 0; i < 5; i++) {
            rocket.tick();
        }
        Vec3 v = rocket.getDeltaMovement();
        double align = rocket.getLookAngle().dot(v.normalize());
        rocket.discard();
        if (!(burnt > 1.0 + ACCEL * BURN * 0.5) || noseBurning.dot(up) < 0.9999 || align < 0.999) {
            helper.fail(String.format("burn-out speed %.3f, burning nose . launch %.5f, coast nose . v %.5f",
                    burnt, noseBurning.dot(up), align));
            return;
        }
        helper.succeed();
    }

    /** Contact fuze on a block. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aRocketDetonatesOnTheGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = BlockPos.containing(centre(helper, 8.0));
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
        Vec3 start = Vec3.atBottomCenterOf(target).add(0.0, 12.0, 0.0);
        RocketEntity rocket = fire(helper, start, new Vec3(0.0, -1.0, 0.0));
        helper.runAfterDelay(20, () -> {
            Vec3 hit = null;
            for (Vec3 p : DETONATIONS) {
                if (Math.abs(p.x - start.x) < 1.0 && Math.abs(p.z - start.z) < 1.0) {
                    hit = p;
                }
            }
            level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
            if (hit == null || Math.abs(hit.y - (target.getY() + 1.0)) > 0.5 || !rocket.isRemoved()) {
                helper.fail("contact: detonation " + hit + ", removed " + rocket.isRemoved());
                return;
            }
            helper.succeed();
        });
    }

    /** A body passing the strike in the same tick's segment as a wall: the rocket still bursts on the wall. */
    @GameTest(template = TEMPLATE)
    public static void aRocketPastABodyStillMeetsTheWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos wall = BlockPos.containing(centre(helper, 8.0));
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        Husk husk = new Husk(EntityType.HUSK, level);
        husk.moveTo(wall.getX() + 0.5, wall.getY() + 1.0, wall.getZ() + 0.5);
        husk.setNoAi(true);
        husk.setNoGravity(true);
        husk.addTag(WAVED);
        level.addFreshEntity(husk);
        Vec3 start = new Vec3(wall.getX() + 0.5, wall.getY() + 4.5, wall.getZ() + 0.5);
        RocketEntity rocket = RocketEntity.fire(level, KineticPresetRegistry.get(FAST), start, new Vec3(0.0, -1.0, 0.0),
                0.0f, Vec3.ZERO, null, null, null);
        rocket.tick();
        boolean removed = rocket.isRemoved();
        Vec3 at = rocket.position();
        rocket.discard();
        husk.discard();
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        Vec3 hit = DETONATIONS.stream().filter(p -> p.distanceTo(start) < 5.0).findFirst().orElse(null);
        if (!removed || hit == null || Math.abs(hit.y - (wall.getY() + 1.0)) > 1.0e-6) {
            helper.fail("removed " + removed + " at " + at + ", detonation " + hit + " (wall top " + (wall.getY() + 1)
                    + ")");
            return;
        }
        helper.succeed();
    }

    /** Air defence: engageable, damage past durability sets the warhead off in the air; radar sees a missile. */
    @GameTest(template = TEMPLATE)
    public static void airDefenceSeesAndKillsARocket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = centre(helper, 20.0);
        RocketEntity rocket = fire(helper, at, new Vec3(1.0, 0.0, 0.0));
        List<TargetSnapshot> seen = new ArrayList<>();
        new EntityTargetSource().collect(level, new AABB(at, at).inflate(4.0), seen::add);
        boolean onRadar = seen.stream()
                .anyMatch(s -> s.sourceId() == SourceIds.of(rocket) && s.kind() == ContactClass.MISSILE);
        boolean engageable = rocket.interceptEngageable();
        Vec3 where = rocket.position();
        rocket.interceptDamage(4.0f);
        boolean survivedChip = !rocket.isRemoved();
        rocket.interceptDamage(4.0f);
        boolean burst = DETONATIONS.stream().anyMatch(p -> p.distanceTo(where) < 0.01);
        if (!onRadar || !engageable || !survivedChip || !rocket.isRemoved() || !burst
                || rocket.interceptEngageable()) {
            helper.fail(String.format("radar %s, engageable %s, survived chip %s, removed %s, burst %s",
                    onRadar, engageable, survivedChip, rocket.isRemoved(), burst));
            return;
        }
        helper.succeed();
    }
}

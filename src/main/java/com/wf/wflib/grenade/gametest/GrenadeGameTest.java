package com.wf.wflib.grenade.gametest;

import com.wf.wflib.WFLib;
import com.wf.wflib.api.ChargeSpec;
import com.wf.wflib.entity.FireLingeringEntity;
import com.wf.wflib.entity.MistEntity;
import com.wf.wflib.fire.FireType;
import com.wf.wflib.grenade.Dazed;
import com.wf.wflib.grenade.GrenadeWarheads;
import com.wf.wflib.grenade.ThrownCharge;
import com.wf.wflib.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hand charges: flight, contact, warheads. Each test on its own floor 26 apart in height (template lid ~5): frag
 * (20) and flash (24) reach would cross arenas sharing a height.
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class GrenadeGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation PROBE = WarheadRegistry.rl("test_grenade_probe");
    private static final List<Vec3> DETONATIONS = new CopyOnWriteArrayList<>();

    static {
        WarheadRegistry.register(PROBE, (source, pos) -> DETONATIONS.add(pos));
    }

    private static ChargeSpec spec(ResourceLocation warhead, int fuse, float bounce, boolean sticky, boolean impact) {
        return new ChargeSpec(warhead, fuse, bounce, sticky, impact, 0, 0, 0.8f);
    }

    /** 7x7 stone floor at {@code height}; returns its top-centre. */
    private static Vec3 floor(GameTestHelper helper, int height) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO).above(height);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlockAndUpdate(o.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        return new Vec3(o.getX() + 0.5, o.getY() + 1.0, o.getZ() + 0.5);
    }

    private static void clear(GameTestHelper helper, int height) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO).above(height);
        for (BlockPos p : BlockPos.betweenClosed(o.offset(-3, 0, -3), o.offset(3, 4, 3))) {
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        }
        for (var e : level.getEntities((net.minecraft.world.entity.Entity) null,
                new AABB(o).inflate(12), e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
            e.discard();
        }
    }

    private static Husk husk(ServerLevel level, Vec3 at, boolean ai) {
        Husk husk = new Husk(EntityType.HUSK, level);
        husk.moveTo(at.x, at.y, at.z, 0, 0);
        husk.setNoAi(!ai);
        husk.setPersistenceRequired();
        level.addFreshEntity(husk);
        return husk;
    }

    private static ThrownCharge drop(ServerLevel level, Vec3 at, Vec3 velocity, ChargeSpec spec) {
        ThrownCharge c = ThrownCharge.create(level, at, velocity, ItemStack.EMPTY, spec, spec.fuseTicks(), null);
        level.addFreshEntity(c);
        return c;
    }

    /** Dropped onto stone: bounces, then lies still on the floor, fuse still running. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aChargeBouncesThenSettles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 top = floor(helper, 8);
        ThrownCharge c = drop(level, top.add(0, 3, 0), new Vec3(0.08, 0, 0), spec(PROBE, 1000, 0.45f, false, false));
        boolean rose = false;
        for (int i = 0; i < 100; i++) {
            c.tick();
            rose |= c.getDeltaMovement().y > 0.01;
        }
        double rest = c.getY() - top.y;
        boolean still = c.getDeltaMovement().lengthSqr() < 1.0E-6;
        boolean live = c.phase() == ThrownCharge.Phase.LIVE;
        clear(helper, 8);
        if (!rose || !still || Math.abs(rest) > 0.1 || !live) {
            helper.fail(String.format("bounced %s, still %s, rest %.3f above floor, live %s", rose, still, rest,
                    live));
            return;
        }
        helper.succeed();
    }

    /** Sticky: first contact with a wall pins it there. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aStickyChargeSticksToTheWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 top = floor(helper, 34);
        BlockPos wall = BlockPos.containing(top.add(2, 1, 0));
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        ThrownCharge c = drop(level, top.add(-1, 1.5, 0), new Vec3(0.6, 0, 0), spec(PROBE, 1000, 0.45f, true, false));
        for (int i = 0; i < 30; i++) {
            c.tick();
        }
        double gap = wall.getX() - c.getX();
        boolean attached = c.attached();
        boolean still = c.getDeltaMovement().lengthSqr() < 1.0E-8;
        clear(helper, 34);
        if (!attached || !still || gap < 0 || gap > 0.2) {
            helper.fail(String.format("attached %s, still %s, gap to wall %.3f", attached, still, gap));
            return;
        }
        helper.succeed();
    }

    /** Impact fuze: detonates on the first hard contact, long before its fuse. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void anImpactChargeGoesOffOnContact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 top = floor(helper, 60);
        DETONATIONS.clear();
        ThrownCharge c = drop(level, top.add(0, 2, 0), new Vec3(0, -0.5, 0), spec(PROBE, 1000, 0.45f, false, true));
        for (int i = 0; i < 10 && !c.isRemoved(); i++) {
            c.tick();
        }
        Vec3 hit = DETONATIONS.isEmpty() ? null : DETONATIONS.get(0);
        clear(helper, 60);
        if (hit == null || Math.abs(hit.y - top.y) > 0.3) {
            helper.fail("detonation " + hit + ", floor " + top.y);
            return;
        }
        helper.succeed();
    }

    /** Sticky onto a body: rides it when it moves. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aStickyChargeRidesItsHost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 top = floor(helper, 86);
        Husk host = husk(level, top.add(1.5, 0, 0), false);
        ThrownCharge c = drop(level, top.add(-1, 1.0, 0), new Vec3(0.5, 0, 0), spec(PROBE, 1000, 0.45f, true, false));
        for (int i = 0; i < 20; i++) {
            c.tick();
        }
        boolean onHost = c.host() == host;
        Vec3 offset = c.position().subtract(host.position());
        host.setPos(host.getX(), host.getY(), host.getZ() + 1.0);
        c.tick();
        Vec3 after = c.position().subtract(host.position());
        clear(helper, 86);
        if (!onHost || after.distanceTo(offset) > 0.05) {
            helper.fail("on host " + onHost + ", offset " + offset + " -> " + after);
            return;
        }
        helper.succeed();
    }

    /** Frag rays: a husk in the open dies at 2.5 blocks; one behind a wall at the same range takes no fragment. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void fragKillsInTheOpenNotBehindCover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 top = floor(helper, 112);
        Husk open = husk(level, top.add(2.5, 0, 0), false);
        Husk covered = husk(level, top.add(-2.5, 0, 0), false);
        for (int y = 0; y < 3; y++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(BlockPos.containing(top.add(-1.5, y, z)), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        float full = covered.getHealth();
        ThrownCharge c = drop(level, top.add(0, 0.2, 0), Vec3.ZERO,
                spec(GrenadeWarheads.FRAG, 1, 0.3f, false, false));
        c.tick();
        boolean dead = !open.isAlive() || open.getHealth() <= 0;
        float coveredHealth = covered.getHealth();
        clear(helper, 112);
        // Blast pressure wraps the wall; one fragment would be >= 5.
        if (!dead || coveredHealth < full - 2) {
            helper.fail(String.format("open dead %s (hp %.1f), covered hp %.1f of %.1f", dead, open.getHealth(),
                    coveredHealth, full));
            return;
        }
        helper.succeed();
    }

    /** Cooked off in a hand: goes off where the holder stands. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aCookedChargeGoesOffInTheHand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 top = floor(helper, 138);
        Husk holder = husk(level, top, false);
        DETONATIONS.clear();
        ThrownCharge.cookOff(holder, ItemStack.EMPTY, spec(PROBE, 80, 0.3f, false, false),
                holder.getEyePosition().add(0, -0.4, 0));
        Vec3 hit = DETONATIONS.isEmpty() ? null : DETONATIONS.get(0);
        clear(helper, 138);
        if (hit == null || hit.distanceTo(holder.position()) > 2.0) {
            helper.fail("detonation " + hit + " vs holder " + holder.position());
            return;
        }
        helper.succeed();
    }

    /** Coloured smoke: cells carry the dye tint; a mob cannot see through 2+ blocks of it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void smokeIsTintedAndHidesFromMobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 top = floor(helper, 164);
        Husk watcher = husk(level, top.add(-3, 0, 0), false);
        Husk target = husk(level, top.add(3, 0, 0), false);
        boolean before = watcher.hasLineOfSight(target);
        ResourceLocation red = GrenadeWarheads.smoke(DyeColor.RED);
        ThrownCharge c = drop(level, top.add(0, 0.1, 0), Vec3.ZERO, spec(red, 1, 0.3f, false, false));
        c.tick();
        c.tick();
        List<MistEntity> cells = level.getEntitiesOfClass(MistEntity.class, new AABB(BlockPos.containing(top))
                .inflate(8));
        int tint = DyeColor.RED.getTextureDiffuseColor() & 0xFFFFFF;
        boolean tinted = !cells.isEmpty() && cells.stream().allMatch(m -> m.tint() == tint && m.blocksSight());
        boolean after = watcher.hasLineOfSight(target);
        boolean reacting = c.phase() == ThrownCharge.Phase.REACTING;
        clear(helper, 164);
        if (!before || after || !tinted || !reacting) {
            helper.fail(String.format("sight before %s after %s, %d cells tinted %s, reacting %s", before, after,
                    cells.size(), tinted, reacting));
            return;
        }
        helper.succeed();
    }

    /** Thermite: thermite fire laid, a body in contact burns, no block broken, shell reacts then is spent. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void thermiteBurnsBodiesNotBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 top = floor(helper, 190);
        Husk victim = husk(level, top.add(0.6, 0, 0), false);
        float full = victim.getHealth();
        ThrownCharge c = drop(level, top.add(0, 0.1, 0), Vec3.ZERO,
                spec(GrenadeWarheads.THERMITE, 1, 0.3f, false, false));
        helper.runAfterDelay(30, () -> {
            boolean fire = !level.getEntitiesOfClass(FireLingeringEntity.class, new AABB(BlockPos.containing(top))
                    .inflate(4), f -> f.getFireType() == FireType.THERMITE).isEmpty();
            boolean hurt = victim.getHealth() < full || !victim.isAlive();
            boolean floorIntact = level.getBlockState(BlockPos.containing(top.add(0, -0.5, 0))).is(Blocks.STONE);
            boolean reacting = c.phase() == ThrownCharge.Phase.REACTING;
            helper.runAfterDelay(GrenadeWarheads.THERMITE_BURN, () -> {
                boolean spent = c.phase() == ThrownCharge.Phase.SPENT;
                clear(helper, 190);
                if (!fire || !hurt || !floorIntact || !reacting || !spent) {
                    helper.fail(String.format("fire %s, hurt %s, floor %s, reacting %s, spent %s", fire, hurt,
                            floorIntact, reacting, spent));
                    return;
                }
                helper.succeed();
            });
        });
    }

    /** Flashbang: a mob in sight is blinded and dazed; one behind a wall is not. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void flashbangDazesMobsInSight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 top = floor(helper, 216);
        Husk seen = husk(level, top.add(3, 0, 0), true);
        Husk hidden = husk(level, top.add(-3, 0, 0), true);
        for (int y = 0; y < 3; y++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(BlockPos.containing(top.add(-1.5, y, z)), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        ThrownCharge c = drop(level, top.add(0, 0.2, 0), Vec3.ZERO,
                spec(GrenadeWarheads.FLASHBANG, 1, 0.3f, false, false));
        c.tick();
        boolean dazed = Dazed.dazed(seen) && seen.hasEffect(MobEffects.BLINDNESS);
        boolean spared = !Dazed.dazed(hidden) && !hidden.hasEffect(MobEffects.BLINDNESS);
        clear(helper, 216);
        if (!dazed || !spared) {
            helper.fail("seen dazed " + dazed + ", hidden spared " + spared);
            return;
        }
        helper.succeed();
    }
}

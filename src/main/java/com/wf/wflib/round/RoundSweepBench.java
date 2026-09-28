package com.wf.wflib.round;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.norwood.ahf.lagcomp.EntityHistory;
import com.wf.wflib.WFLib;
import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Sweep cost per step across a crowd (20 players, 200 husks, 24x24): block clip + entity hits only, no launch or
 * network. Baseline = 1.2.0 sweep (every entity, AABB + 0.3). Median of {@link #REPS}. Logs only; own batch (the
 * crowd spills over neighbouring arenas).
 */
@GameTestHolder(WFLib.MODID)
@PrefixGameTestTemplate(false)
public class RoundSweepBench {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PLAYERS = 20;
    private static final int MOBS = 200;
    private static final double SPREAD = 24.0;
    private static final double HEIGHT = 10.0;
    private static final int N = 20000;
    private static final int REPS = 7;
    private static final String[] CASES = {"1.2.0 AABB", "live", "rewound 3"};
    private static final String[] LANES = {"straight 40", "diagonal", "empty air"};
    private static long sink;

    @GameTest(template = "empty", required = false, batch = "wflib_bench", timeoutTicks = 400)
    public static void roundSweepCost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        Vec3 base = new Vec3(o.getX() + 1.5, o.getY() + HEIGHT, o.getZ() + 1.5);
        RandomSource random = RandomSource.create(7);
        forceArea(level, base, true);
        List<Entity> crowd = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "wflib_bench"));
            p.moveTo(base.x + random.nextDouble() * SPREAD, base.y, base.z + random.nextDouble() * SPREAD,
                    random.nextFloat() * 360.0F, 0.0F);
            level.addFreshEntity(p);
            crowd.add(p);
        }
        for (int i = 0; i < MOBS; i++) {
            Husk h = new Husk(EntityType.HUSK, level);
            h.moveTo(base.x + random.nextDouble() * SPREAD, base.y, base.z + random.nextDouble() * SPREAD);
            h.setNoAi(true);
            h.setNoGravity(true);
            h.setInvulnerable(true);
            level.addFreshEntity(h);
            crowd.add(h);
        }
        Predicate<Entity> canHit = e -> e.isAlive() && !e.isSpectator() && e.canBeHitByProjectile();
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            try {
                long when = EntityHistory.broadcastTick(level) - 3;
                for (int lane = 0; lane < LANES.length; lane++) {
                    double[][] us = new double[CASES.length][REPS];
                    long[] hits = new long[CASES.length];
                    for (int r = 0; r < REPS + 2; r++) {
                        for (int c = 0; c < CASES.length; c++) {
                            RandomSource rs = RandomSource.create(11);
                            long h = 0;
                            long t0 = System.nanoTime();
                            for (int i = 0; i < N; i++) {
                                Vec3 from;
                                Vec3 to;
                                if (lane == 0) {
                                    from = base.add(-8.0, 1.0, rs.nextDouble() * SPREAD);
                                    to = from.add(40.0, 0.0, 0.0);
                                } else if (lane == 1) {
                                    from = base.add(-4.0 + rs.nextDouble() * 2.0, 1.0, -4.0 + rs.nextDouble() * 2.0);
                                    to = from.add(SPREAD + 4.0, 0.0, SPREAD + 4.0);
                                } else {
                                    from = base.add(-8.0, 40.0, rs.nextDouble() * SPREAD);
                                    to = from.add(40.0, 0.0, 0.0);
                                }
                                h += c == 0 ? baseline(level, from, to, canHit)
                                        : sweep(level, from, to, canHit, c == 1 ? RoundDamageSource.LIVE : when);
                            }
                            long dt = System.nanoTime() - t0;
                            sink += h;
                            if (r >= 2) {
                                us[c][r - 2] = dt / 1000.0 / N;
                                hits[c] = h;
                            }
                        }
                    }
                    StringBuilder sb = new StringBuilder("round sweep bench, " + LANES[lane] + ":");
                    for (int c = 0; c < CASES.length; c++) {
                        Arrays.sort(us[c]);
                        sb.append(String.format(Locale.ROOT, " | %s %.2f us (min %.2f), %d hits", CASES[c],
                                us[c][REPS / 2], us[c][0], hits[c]));
                    }
                    LOGGER.info(sb.toString());
                }
                double[] capture = new double[REPS];
                for (int r = 0; r < REPS + 2; r++) {
                    long t0 = System.nanoTime();
                    for (int i = 0; i < 200; i++) {
                        EntityHistory.capture(level);
                    }
                    if (r >= 2) {
                        capture[r - 2] = (System.nanoTime() - t0) / 1000.0 / 200;
                    }
                }
                Arrays.sort(capture);
                LOGGER.info(String.format(Locale.ROOT, "round sweep bench: AHF capture %.1f us per tick (%d living)",
                        capture[REPS / 2], PLAYERS + MOBS));
            } finally {
                crowd.forEach(Entity::discard);
                forceArea(level, base, false);
            }
        }).thenSucceed();
    }

    private static int sweep(ServerLevel level, Vec3 from, Vec3 to, Predicate<Entity> canHit, long when) {
        BlockHitResult block = Rounds.clipLoaded(level, from, to);
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        return Rounds.entityHits(level, from, end, canHit, when).size();
    }

    private static int baseline(ServerLevel level, Vec3 from, Vec3 to, Predicate<Entity> canHit) {
        BlockHitResult block = Rounds.clipLoaded(level, from, to);
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        int n = 0;
        for (Entity e : level.getEntities((Entity) null, new AABB(from, end).inflate(1.0), canHit)) {
            AABB box = e.getBoundingBox().inflate(0.3);
            if (box.contains(from) || box.clip(from, end).isPresent()) {
                n++;
            }
        }
        return n;
    }

    /** Segment ends must be loaded or the sweep reads air. */
    private static void forceArea(ServerLevel level, Vec3 base, boolean forced) {
        for (int cx = ((int) Math.floor(base.x - 8.0)) >> 4; cx <= ((int) Math.floor(base.x + 40.0)) >> 4; cx++) {
            for (int cz = ((int) Math.floor(base.z - 8.0)) >> 4; cz <= ((int) Math.floor(base.z + 40.0)) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, forced);
            }
        }
    }
}

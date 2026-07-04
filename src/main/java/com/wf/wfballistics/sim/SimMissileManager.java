package com.wf.wfballistics.sim;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;

import java.util.*;

/**
 * Drives all off-world missiles for one level each server tick: advances their positions from
 * gametime deltas, resolves simulated interceptions, and respawns real entities when a missile
 * nears its target or a listener. All logic runs on the server thread.
 * TODO: Async simulation constrained by game time every now and then
 */
public final class SimMissileManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SimMissileManager() {
    }

    public static void tick(ServerLevel level) {
        SimMissileRegistry reg = SimMissileRegistry.get(level);
        List<SimMissile> all = new ArrayList<>(reg.view());
        if (all.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        Map<UUID, SimMissile> byId = new HashMap<>();
        for (SimMissile sm : all) {
            byId.put(sm.id, sm);
        }
        Set<SimMissile> dead = new HashSet<>();
        Map<SimMissile, Vec3> oldPos = new HashMap<>();

        //1) Simulate
        for (SimMissile sm : all) {
            oldPos.put(sm, sm.pos);
            long raw = now - sm.lastGameTime;
            long dt = raw <= 0L ? 0L : Math.min(raw, MissileSimConfig.MAX_SIM_STEP);
            sm.lastGameTime = now;
            if (dt > 0) {
                advance(sm, dt, byId);
                // Powered missiles burn fuel off-world too; one that runs dry over unloaded terrain is
                // treated as having crashed (interceptors are short-lived and resolved separately).
                if (sm.role == SimMissile.Role.NORMAL) {
                    sm.fuel -= (int) dt;
                    if (sm.fuel <= 0) {
                        LOGGER.debug("[wfballistics] simulated missile {} ran out of fuel and crashed near {}",
                                sm.id, sm.pos);
                        dead.add(sm);
                    }
                }
            }
        }

        // 2) Resolve interceptions
        for (SimMissile sm : all) {
            if (sm.role != SimMissile.Role.INTERCEPTOR || dead.contains(sm)) {
                continue;
            }
            SimMissile target = sm.interceptTarget == null ? null : byId.get(sm.interceptTarget);
            if (target == null || dead.contains(target)) {
                dead.add(sm);
                continue;
            }
            if (MissileSimConfig.INTERCEPT_MODE == MissileSimConfig.InterceptResolution.CHANCE_ROLL) {
                resolveByChance(level, sm, target, dead);
            } else {
                resolveInWorld(level, sm, target, dead);
            }
        }

        // 3) Terminal / listener respawns
        for (SimMissile sm : all) {
            if (dead.contains(sm) || sm.role == SimMissile.Role.INTERCEPTOR) {
                continue;
            }
            double dx = sm.target.x - sm.pos.x;
            double dz = sm.target.z - sm.pos.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDist <= MissileSimConfig.DESTINATION_RANGE) {
                respawn(level, sm, sm.pos);
                dead.add(sm);
                continue;
            }
            Vec3 spawnPos = firstListenerSpawnPos(level, oldPos.get(sm), sm.pos);
            if (spawnPos != null) {
                respawn(level, sm, spawnPos);
                dead.add(sm);
            }
        }

        // 4) Commit removals
        if (!dead.isEmpty()) {
            for (SimMissile sm : dead) {
                reg.remove(sm);
            }
        }
        reg.setDirty();
    }

    private static void advance(SimMissile sm, long dt, Map<UUID, SimMissile> byId) {
        if (sm.role == SimMissile.Role.INTERCEPTOR) {
            SimMissile target = sm.interceptTarget == null ? null : byId.get(sm.interceptTarget);
            if (target == null) {
                return;
            }
            Vec3 to = target.pos.subtract(sm.pos);
            double dist = to.length();
            double step = Math.min(dt * sm.speed, dist);
            if (dist > 1.0E-6) {
                sm.pos = sm.pos.add(to.scale(step / dist));
            }
        } else {
            double dx = sm.target.x - sm.pos.x;
            double dz = sm.target.z - sm.pos.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            double step = Math.min(dt * sm.speed, horiz);
            if (horiz > 1.0E-6) {
                sm.pos = new Vec3(sm.pos.x + dx / horiz * step, sm.simY, sm.pos.z + dz / horiz * step);
            }
        }
    }

    /**
     * CHANCE_ROLL: when the two tracks are within intercept distance, roll for the kill.
     */
    private static void resolveByChance(ServerLevel level, SimMissile interceptor, SimMissile target, Set<SimMissile> dead) {
        double d = MissileSimConfig.INTERCEPT_DISTANCE;
        if (interceptor.pos.distanceToSqr(target.pos) <= d * d) {
            // Higher-tier (evasive) targets can boost clear, but only to the extent they can out-run the
            // interceptor: escape scales with the boosted-speed / interceptor-speed ratio, so a much faster
            // interceptor still connects (matches the in-world evadeBoost math; cruising, so no dive bonus).
            double boosted = target.speed * 2.0; // approximates the evasive boost burst
            double speedFactor = Math.min(1.0, boosted / Math.max(1.0E-3, interceptor.speed));
            float escape = (float) (target.evasion * speedFactor);
            float chance = interceptor.interceptChance * (1.0f - escape);
            boolean hit = level.getRandom().nextFloat() < chance;
            dead.add(interceptor); // spent whether it hits or misses
            if (hit) {
                dead.add(target);
                LOGGER.debug("[wfballistics] simulated interception SUCCESS on {}", target.id);
            } else {
                LOGGER.debug("[wfballistics] simulated interception MISS on {}", target.id);
            }
        }
    }

    /**
     * IN_WORLD: predict the collision area and, once it is imminent, respawn <em>both</em> the target and the
     * interceptor as real entities a short distance back from it, and let the real interceptor's in-world
     * closest-approach roll ({@link MissileEntity#tryIntercept}) play out the kill in the loaded world.
     */
    private static void resolveInWorld(ServerLevel level, SimMissile interceptor, SimMissile target, Set<SimMissile> dead) {
        CollisionPredictor.Result p = CollisionPredictor.predict(interceptor, target);
        if (p == null || p.ticks() > MissileSimConfig.INTERCEPT_LEAD_TICKS) {
            return; // no imminent collision yet; keep simulating
        }
        Vec3 c = p.point();
        double dist = MissileSimConfig.INTERCEPT_SPAWN_DISTANCE;

        // Target: a short way back along its heading toward the meeting point.
        Vec3 tHeading = horizontalUnit(target.pos, target.target);
        Vec3 tSpawn = new Vec3(c.x - tHeading.x * dist, target.simY, c.z - tHeading.z * dist);
        respawn(level, target, tSpawn);

        // Interceptor: a real interceptor entity (its LOCK on the target rides in SimMissile.toEntity),
        // spawned back along its own approach so it flies in and rolls for the kill.
        Vec3 toC = c.subtract(interceptor.pos);
        double len = toC.length();
        Vec3 iUnit = len > 1.0E-6 ? toC.scale(1.0 / len) : new Vec3(0.0, 0.0, 1.0);
        Vec3 iSpawn = c.subtract(iUnit.scale(dist));
        respawn(level, interceptor, iSpawn);

        dead.add(interceptor);
        dead.add(target);
        LOGGER.debug("[wfballistics] in-world interception staged around {} ({} ticks out)", c, p.ticks());
    }

    private static Vec3 horizontalUnit(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        return h > 1.0E-6 ? new Vec3(dx / h, 0.0, dz / h) : new Vec3(0.0, 0.0, 1.0);
    }


    private static Vec3 firstListenerSpawnPos(ServerLevel level, Vec3 p0, Vec3 p1) {
        double bestT = Double.MAX_VALUE;

        for (IMissileListener l : MissileListenerRegistry.get(level).valid()) {
            double t = RayMath.segmentSphereEntry(p0, p1, l.listenerCenter(), l.listenerRange());
            if (!Double.isNaN(t) && t < bestT) {
                bestT = t;
            }
        }
        for (ServerPlayer p : level.players()) {
            double t = RayMath.segmentSphereEntry(p0, p1, p.position(), MissileSimConfig.PLAYER_LISTENER_RANGE);
            if (!Double.isNaN(t) && t < bestT) {
                bestT = t;
            }
        }

        if (bestT == Double.MAX_VALUE) {
            return null;
        }

        Vec3 d = p1.subtract(p0);
        double len = d.length();
        if (len < 1.0E-6) {
            return p0;
        }
        double entryDist = bestT * len;
        double spawnDist = Math.max(0.0, entryDist - MissileSimConfig.LISTENER_SPAWN_MARGIN);
        return p0.add(d.scale(spawnDist / len));
    }

    public static boolean nearAnyListener(ServerLevel level, Vec3 pos) {
        for (IMissileListener l : MissileListenerRegistry.get(level).valid()) {
            double r = l.listenerRange() + MissileSimConfig.LISTENER_SPAWN_MARGIN;
            if (l.listenerCenter().distanceToSqr(pos) <= r * r) {
                return true;
            }
        }
        for (ServerPlayer p : level.players()) {
            double r = MissileSimConfig.PLAYER_LISTENER_RANGE + MissileSimConfig.LISTENER_SPAWN_MARGIN;
            if (p.position().distanceToSqr(pos) <= r * r) {
                return true;
            }
        }
        return false;
    }

    public static void startSim(MissileEntity missile) {
        if (!(missile.level() instanceof ServerLevel level)) {
            return;
        }
        SimMissile sm = SimMissile.fromEntity(missile);
        SimMissileRegistry.get(level).add(sm);
        LOGGER.debug("[wfballistics] missile {} offloaded to simulation at {}", sm.id, sm.pos);
        missile.discard(); // triggers chunk release via MissileEntity#remove
    }

    private static void respawn(ServerLevel level, SimMissile sm, Vec3 spawnPos) {
        MissileEntity m = sm.toEntity(level, spawnPos);
        ChunkPos cp = m.chunkPosition();
        // Force the spawn chunk (ticking) before adding so there is a loaded chunk to place into; the
        // entity's own MissileChunkLoader takes over from its first tick and eventually releases it.
        ForgeChunkManager.forceChunk(level, WFBallistics.MODID, m, cp.x, cp.z, true, true);
        level.addFreshEntity(m);
        LOGGER.debug("[wfballistics] simulated missile {} respawned at {}", sm.id, spawnPos);
    }


    public static void launchInterceptor(ServerLevel level, Vec3 start, UUID targetId) {
        launchInterceptor(level, start, targetId, MissileSimConfig.DEFAULT_INTERCEPT_CHANCE);
    }

    public static void launchInterceptor(ServerLevel level, Vec3 start, UUID targetId, float chance) {
        SimMissile sm = new SimMissile();
        sm.id = UUID.randomUUID();
        sm.pos = start;
        sm.target = start;
        sm.simY = start.y;
        sm.speed = MissileSimConfig.INTERCEPTOR_SPEED;
        sm.lastGameTime = level.getGameTime();
        sm.cruiseMode = MissileEntity.CruiseMode.HIGH_ALTITUDE;
        sm.cruiseAltitude = start.y;
        sm.modelId = MissileModels.DEFAULT;
        sm.role = SimMissile.Role.INTERCEPTOR;
        sm.interceptTarget = targetId;
        sm.interceptChance = chance;
        SimMissileRegistry.get(level).add(sm);
        LOGGER.debug("[wfballistics] interceptor {} launched at target {}", sm.id, targetId);
    }
}

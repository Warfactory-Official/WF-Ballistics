package com.wf.wflib.sim;

import com.mojang.logging.LogUtils;
import com.wf.wflib.MissileEntity;
import com.wf.wflib.WFLib;
import com.wf.wflib.MissileModels;
import com.wf.wflib.api.WFEventType;
import com.wf.wflib.api.WFTelemetryService;
import com.wf.wflib.chunk.DetonationChunkGuard;
import com.wf.wflib.debug.MissileDebug;
import com.wf.wflib.network.MissileFlightAudioPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;

/**
 * Off-world missiles. Advance (worker): positions from gametime deltas, fuel. Resolve (tick thread): listener
 * bookkeeping, audio, fuel-out, interceptions, respawn near the target or a listener.
 */
public final class SimMissileManager implements SimKind<SimMissile> {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final SimMissileManager KIND = new SimMissileManager();

    private SimMissileManager() {
    }

    public static SimTier<SimMissile> tier(ServerLevel level) {
        return SimWorld.get(level).tier(KIND);
    }

    @Nullable
    public static SimMissile find(ServerLevel level, UUID id) {
        return tier(level).find(sm -> sm.id.equals(id));
    }

    @Override
    public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "missile");
    }

    @Override
    public CompoundTag save(SimMissile record) {
        return record.save();
    }

    @Override
    public SimMissile load(CompoundTag tag) {
        return SimMissile.load(tag);
    }

    @Override
    public boolean async() {
        return true;
    }

    @Override
    public void advance(SimTier<SimMissile> tier, long now) {
        List<SimMissile> all = tier.passRecords();
        Map<UUID, SimMissile> byId = new HashMap<>();
        for (SimMissile sm : all) {
            byId.put(sm.id, sm);
        }
        for (SimMissile sm : all) {
            sm.prevPos = sm.pos;
            long raw = now - sm.lastGameTime;
            long dt = raw <= 0L ? 0L : Math.min(raw, MissileSimConfig.MAX_SIM_STEP);
            sm.lastGameTime = now;
            if (dt <= 0) {
                continue;
            }
            advance(sm, dt, byId);
            if (sm.role == SimMissile.Role.NORMAL) {
                sm.fuel -= (int) dt;
                if (!sm.swarmMembers.isEmpty()) {
                    for (SimMissile mem : sm.swarmMembers) {
                        mem.fuel -= (int) dt;
                    }
                    sm.swarmMembers.removeIf(mem -> mem.fuel <= 0);
                }
            }
        }
    }

    @Override
    public void resolve(ServerLevel level, SimTier<SimMissile> tier) {
        long now = level.getGameTime();
        List<SimMissile> all = new ArrayList<>(tier.view());
        MissileListenerRegistry listeners = MissileListenerRegistry.get(level);
        for (SimMissile sm : all) {
            listeners.noteThreat(sm.pos, now);
        }
        listeners.tickWakeups(level, now);
        DetonationChunkGuard.tick(level, now);
        MissileDebug.tickLatest(level, now);
        if (all.isEmpty()) {
            return;
        }
        Map<UUID, SimMissile> byId = new HashMap<>();
        for (SimMissile sm : all) {
            byId.put(sm.id, sm);
        }
        Set<SimMissile> dead = new HashSet<>();

        // 1) Fuel out, audio
        for (SimMissile sm : all) {
            Vec3 prev = sm.prevPos == null ? sm.pos : sm.prevPos;
            if (sm.role == SimMissile.Role.NORMAL && sm.fuel <= 0) {
                sm.fuel = 0;
                WFTelemetryService.record(sm.id, WFEventType.FUEL_OUT, now, sm.pos, true, "ballistic respawn");
                respawn(level, sm, sm.pos);
                dead.add(sm);
                continue;
            }
            if (now % MissileFlightAudioPacket.UPDATE_INTERVAL == 0 && sm.pos != prev) {
                MissileFlightAudioPacket.broadcastSim(level, sm, sm.pos.subtract(prev));
            }
        }

        // 2) Interceptions
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
            if (Math.sqrt(dx * dx + dz * dz) <= MissileSimConfig.DESTINATION_RANGE) {
                respawn(level, sm, sm.pos);
                dead.add(sm);
                continue;
            }
            Vec3 spawnPos = firstListenerSpawnPos(level, sm.prevPos == null ? sm.pos : sm.prevPos, sm.pos);
            if (spawnPos != null) {
                respawn(level, sm, spawnPos);
                dead.add(sm);
            }
        }
        for (SimMissile sm : dead) {
            tier.remove(sm);
        }
        tier.setDirty();
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
            double boosted = target.speed * 2.0; // approximates the evasive boost burst
            double speedFactor = Math.min(1.0, boosted / Math.max(1.0E-3, interceptor.speed));
            float escape = (float) (target.evasion * speedFactor);
            float chance = interceptor.interceptChance * (1.0f - escape);
            boolean hit = level.getRandom().nextFloat() < chance;
            dead.add(interceptor); // spent whether it hits or misses
            if (hit) {
                dead.add(target);
                WFTelemetryService.record(target.id, WFEventType.INTERCEPTED, level.getGameTime(),
                        target.pos, true, "sim intercept");
                LOGGER.debug("[wflib] simulated interception SUCCESS on {}", target.id);
            } else {
                LOGGER.debug("[wflib] simulated interception MISS on {}", target.id);
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

        Vec3 toC = c.subtract(interceptor.pos);
        double len = toC.length();
        Vec3 iUnit = len > 1.0E-6 ? toC.scale(1.0 / len) : new Vec3(0.0, 0.0, 1.0);
        Vec3 iSpawn = c.subtract(iUnit.scale(dist));
        respawn(level, interceptor, iSpawn);

        dead.add(interceptor);
        dead.add(target);
        LOGGER.debug("[wflib] in-world interception staged around {} ({} ticks out)", c, p.ticks());
    }

    private static Vec3 horizontalUnit(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        return h > 1.0E-6 ? new Vec3(dx / h, 0.0, dz / h) : new Vec3(0.0, 0.0, 1.0);
    }

    private static Vec3 firstListenerSpawnPos(ServerLevel level, Vec3 p0, Vec3 p1) {
        double bestT = Double.MAX_VALUE;

        for (MissileListenerRegistry.ListenerView l : MissileListenerRegistry.get(level).views()) {
            double t = RayMath.segmentSphereEntry(p0, p1, l.center(), l.range());
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
        for (MissileListenerRegistry.ListenerView l : MissileListenerRegistry.get(level).views()) {
            double r = l.range() + MissileSimConfig.LISTENER_SPAWN_MARGIN;
            if (l.center().distanceToSqr(pos) <= r * r) {
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
        tier(level).add(sm);
        missile.recordEvent(WFEventType.OFFLOAD, "to sim");
        LOGGER.debug("[wflib] missile {} offloaded to simulation at {}", sm.id, sm.pos);
        missile.leaveWorld();
    }

    /**
     * Offload a coordinated swarm (a commander plus its formation members) as a single simulated object: the
     * commander drives the track; each member rides along as a snapshot at its offset from the commander, and the
     * whole formation rematerializes together (see {@link #respawn}).
     */
    public static void startSimSwarm(MissileEntity commander, List<MissileEntity> subordinates) {
        if (!(commander.level() instanceof ServerLevel level)) {
            return;
        }
        SimMissile lead = SimMissile.fromEntity(commander);
        Vec3 cpos = commander.position();
        for (MissileEntity sub : subordinates) {
            SimMissile member = SimMissile.fromEntity(sub);
            member.formationOffset = sub.position().subtract(cpos);
            lead.swarmMembers.add(member);
        }
        tier(level).add(lead);
        commander.recordEvent(WFEventType.OFFLOAD, "swarm to sim");
        LOGGER.debug("[wflib] swarm {} ({} members) offloaded as one object at {}",
                commander.swarm().getSwarmId(), lead.swarmMembers.size() + 1, lead.pos);
        for (MissileEntity sub : subordinates) {
            sub.leaveWorld();
        }
        commander.leaveWorld();
    }

    /** Lift a respawn point clear of the ground under it, and only ever upward. */
    private static Vec3 clearOfGround(ServerLevel level, SimMissile sm, Vec3 spawnPos) {
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(spawnPos.x), Mth.floor(spawnPos.z));
        double safe = ground + sm.terrainClearance;
        return spawnPos.y >= safe ? spawnPos : new Vec3(spawnPos.x, safe, spawnPos.z);
    }

    private static void respawn(ServerLevel level, SimMissile sm, Vec3 spawnPos) {
        spawnOne(level, sm, spawnPos);
        for (SimMissile member : sm.swarmMembers) {
            spawnOne(level, member, spawnPos.add(member.formationOffset));
        }
    }

    private static void spawnOne(ServerLevel level, SimMissile sm, Vec3 spawnPos) {
        MissileEntity m = sm.toEntity(level, clearOfGround(level, sm, spawnPos));
        ChunkPos cp = m.chunkPosition();
        MissileListenerRegistry.CHUNK_TICKET.forceChunk(level, m, cp.x, cp.z, true, true);
        level.getChunk(cp.x, cp.z);
        level.addFreshEntity(m);
        WFTelemetryService.record(sm.id, WFEventType.ONLOAD, level.getGameTime(), spawnPos, false, "from sim");
        LOGGER.debug("[wflib] simulated missile {} respawned at {}", sm.id, spawnPos);
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
        tier(level).add(sm);
        LOGGER.debug("[wflib] interceptor {} launched at target {}", sm.id, targetId);
    }
}

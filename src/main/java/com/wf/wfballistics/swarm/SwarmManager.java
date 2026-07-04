package com.wf.wfballistics.swarm;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks missile <em>swarms</em>: families of missiles (typically a {@link com.wf.wfballistics.warhead.RecursiveFrag}
 * cluster) that share a non-zero {@code swarmId}. Members of one swarm never collide with each other, spread
 * apart in flight (separation steering), and share a colour in the debug overlay.
 *
 * <p>Membership is maintained server-side from Forge join/leave events, mirroring {@link
 * com.wf.wfballistics.entity.OBBEntityTracker}: an O(1) per-swarm set so a member can find its neighbours
 * without scanning every entity in the level. {@code swarmId == 0} means "not in a swarm" and is never tracked.
 */
@Mod.EventBusSubscriber(modid = WFBallistics.MODID)
public final class SwarmManager {

    // level -> (swarmId -> members). Server-side only; the client never queries membership.
    private static final Map<Level, Map<Long, Set<MissileEntity>>> BY_LEVEL = new ConcurrentHashMap<>();
    /**
     * Lateral and trailing spacing (blocks) between slots in the wedge formation.
     */
    private static final double FORMATION_LATERAL = 6.0;
    private static final double FORMATION_TRAIL = 6.0;

    private SwarmManager() {
    }

    /**
     * @return a fresh, non-zero swarm id.
     */
    public static long newId(Level level) {
        long id = level.random.nextLong();
        return id != 0L ? id : 1L;
    }

    /**
     * @return true if both missiles are in the same (non-zero) swarm.
     */
    public static boolean sameSwarm(MissileEntity a, MissileEntity b) {
        long id = a.getSwarmId();
        return id != 0L && id == b.getSwarmId();
    }

    /**
     * @return the live members of {@code swarmId} in {@code level} (empty for id 0 / unknown).
     */
    public static Set<MissileEntity> members(Level level, long swarmId) {
        if (swarmId == 0L) {
            return Set.of();
        }
        Map<Long, Set<MissileEntity>> byId = BY_LEVEL.get(level);
        if (byId == null) {
            return Set.of();
        }
        Set<MissileEntity> set = byId.get(swarmId);
        return set != null ? set : Set.of();
    }

    // --- commander / formation ---

    /**
     * @return how many live members {@code swarmId} has in {@code level}.
     */
    public static int count(Level level, long swarmId) {
        return members(level, swarmId).size();
    }

    /**
     * @return same-swarm members within {@code radius} of {@code missile} (excluding itself). Allocates a
     * small list, so call it at most once per member per tick.
     */
    public static List<MissileEntity> nearby(MissileEntity missile, double radius) {
        Set<MissileEntity> set = members(missile.level(), missile.getSwarmId());
        if (set.size() <= 1) {
            return List.of();
        }
        double r2 = radius * radius;
        Vec3 pos = missile.position();
        List<MissileEntity> out = new ArrayList<>();
        for (MissileEntity other : set) {
            if (other == missile || !other.isAlive()) {
                continue;
            }
            if (other.position().distanceToSqr(pos) <= r2) {
                out.add(other);
            }
        }
        return out;
    }

    /**
     * @return the live commander of {@code swarmId} in {@code level}, or null if the swarm has none.
     */
    public static MissileEntity commander(Level level, long swarmId) {
        for (MissileEntity m : members(level, swarmId)) {
            if (m.isCommander() && m.isAlive() && !m.isRemoved()) {
                return m;
            }
        }
        return null;
    }

    /**
     * @return the world position of {@code subordinate}'s slot in a wedge (V) formation trailing the commander:
     * alternating left/right and stepping back one rank every two slots, held at the commander's altitude.
     * Slots are assigned by a stable id ordering so a missile doesn't swap slots tick to tick.
     */
    public static Vec3 formationSlot(MissileEntity commander, MissileEntity subordinate) {
        int idx = formationIndex(commander, subordinate); // 1-based slot among the subordinates
        Vec3 vel = commander.getDeltaMovement();
        double h = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        Vec3 fwd = h > 1.0E-4 ? new Vec3(vel.x / h, 0.0, vel.z / h) : new Vec3(0.0, 0.0, 1.0);
        Vec3 right = new Vec3(fwd.z, 0.0, -fwd.x);
        int rank = (idx + 1) / 2;
        double lateral = (idx % 2 == 1 ? -1 : 1) * FORMATION_LATERAL * rank;
        double trail = -FORMATION_TRAIL * rank;
        return new Vec3(commander.getX() + right.x * lateral + fwd.x * trail,
                commander.getY(),
                commander.getZ() + right.z * lateral + fwd.z * trail);
    }

    private static int formationIndex(MissileEntity commander, MissileEntity subordinate) {
        List<MissileEntity> subs = new ArrayList<>();
        for (MissileEntity m : members(commander.level(), commander.getSwarmId())) {
            if (!m.isCommander() && m.isAlive() && !m.isRemoved()) {
                subs.add(m);
            }
        }
        subs.sort(Comparator.comparingInt(MissileEntity::getId));
        int i = subs.indexOf(subordinate);
        return i < 0 ? 1 : i + 1;
    }

    /**
     * Promotes a successor when a commander is lost: the surviving swarm member nearest the fallen commander
     * becomes the new commander (inheriting its mission target); the rest re-form on it next tick.
     */
    public static void promoteSuccessor(Level level, long swarmId, MissileEntity dying) {
        MissileEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (MissileEntity m : members(level, swarmId)) {
            if (m == dying || m.isCommander() || !m.isAlive() || m.isRemoved()) {
                continue;
            }
            double d = m.position().distanceToSqr(dying.position());
            if (d < bestSq) {
                bestSq = d;
                best = m;
            }
        }
        if (best != null) {
            best.setCommander(true);
            best.setTarget(dying.getTarget());
        }
    }

    // --- membership maintenance ---

    private static void add(MissileEntity missile) {
        long id = missile.getSwarmId();
        if (id == 0L || missile.level().isClientSide) {
            return;
        }
        BY_LEVEL.computeIfAbsent(missile.level(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet())
                .add(missile);
    }

    private static void remove(MissileEntity missile, long id, Level level) {
        if (id == 0L || level.isClientSide) {
            return;
        }
        Map<Long, Set<MissileEntity>> byId = BY_LEVEL.get(level);
        if (byId == null) {
            return;
        }
        Set<MissileEntity> set = byId.get(id);
        if (set != null) {
            set.remove(missile);
            if (set.isEmpty()) {
                byId.remove(id);
            }
        }
        if (byId.isEmpty()) {
            BY_LEVEL.remove(level);
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof MissileEntity missile) {
            // swarmId is set on the entity (builder or NBT) before it is added to the level, so it is
            // already correct here.
            add(missile);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof MissileEntity missile) {
            remove(missile, missile.getSwarmId(), event.getLevel());
        }
    }
}

package com.wf.wfballistics.swarm;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.WFBallistics;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private SwarmManager() {
    }

    /** @return a fresh, non-zero swarm id. */
    public static long newId(Level level) {
        long id = level.random.nextLong();
        return id != 0L ? id : 1L;
    }

    /** @return true if both missiles are in the same (non-zero) swarm. */
    public static boolean sameSwarm(MissileEntity a, MissileEntity b) {
        long id = a.getSwarmId();
        return id != 0L && id == b.getSwarmId();
    }

    /** @return the live members of {@code swarmId} in {@code level} (empty for id 0 / unknown). */
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

    /** @return how many live members {@code swarmId} has in {@code level}. */
    public static int count(Level level, long swarmId) {
        return members(level, swarmId).size();
    }

    /**
     * @return same-swarm members within {@code radius} of {@code missile} (excluding itself). Allocates a
     *         small list, so call it at most once per member per tick.
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

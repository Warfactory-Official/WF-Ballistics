package com.wf.wfballistics.drone;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Flights still going up: one drone leaves the pad, then the next, then the next.
 *
 * <p>A squad used to appear all at once, which is the one moment in a mission when every drone in it wants
 * the same piece of sky. Three-block hulls are solid to one another, so what actually happened was a
 * scrimmage on the pad: the ones shoved aside spent the first part of the flight recovering from the shove
 * rather than forming up, and a wide formation made it worse, because the slots they were being told to fly
 * to were further away. Releasing them in series gives each the climb-out to itself, and
 * {@code DroneState#MUSTER} is what stops the first one leaving without the rest.
 *
 * <p>Persistent, and that is not incidental. A launch is a promise to put a specific number of drones in the
 * air, and the flight already up is <em>waiting</em> on it: if a world reload lost the queue, the drones
 * that made it would hold over the pad until the muster timeout and then fly the mission short-handed. So
 * the whole mission rides along, including the dispatch-time fields (the drawn-once dogleg, the exchange it
 * belongs to) because a drone that came up after a restart has to fly the same route as the one that came
 * up before it.
 *
 * <p>Everything here runs on the world thread, from {@code WFServerEvents}' level tick.
 */
public final class DroneLaunchQueue extends SavedData {

    public static final String NAME = "wfballistics_drone_launches";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<Launch> launches = new ArrayList<>();

    public static DroneLaunchQueue get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DroneLaunchQueue::new, (tag, reg) -> DroneLaunchQueue.load(tag)),
                NAME);
    }

    /**
     * Release whatever is due this tick.
     *
     * <p>Only one drone per launch per tick even if several are overdue: a server that stalled for a second
     * should resume the stagger, not dump the backlog into the air at once, which is the exact pile-up the
     * stagger exists to prevent.
     */
    public static void tick(ServerLevel level) {
        DroneLaunchQueue queue = level.getDataStorage().get(
                new SavedData.Factory<>(DroneLaunchQueue::new, (tag, reg) -> DroneLaunchQueue.load(tag)),
                NAME);
        if (queue == null || queue.launches.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        boolean changed = false;
        for (Iterator<Launch> it = queue.launches.iterator(); it.hasNext(); ) {
            Launch launch = it.next();
            if (now < launch.dueTick) {
                continue;
            }
            try {
                launch.mission.spawn(level, launch.origin, launch.squadId, launch.next, launch.total);
            } catch (Exception e) {
                LOGGER.error("[wfballistics] queued drone launch {} of {} failed", launch.next, launch.total, e);
                it.remove();
                changed = true;
                continue;
            }
            launch.next++;
            launch.dueTick = now + launch.interval;
            if (launch.next >= launch.total) {
                it.remove();
            }
            changed = true;
        }
        if (changed) {
            queue.setDirty();
        }
    }

    /**
     * Queue the rest of a flight. The leader is already in the air, see {@code DroneMission#dispatch}, so
     * this starts at index 1.
     *
     * @param total how many the mission ordered, leader included
     * @param first the tick the second drone is due
     */
    public void enqueue(DroneMission mission, Vec3 origin, long squadId, int total, long first) {
        if (total <= 1) {
            return;
        }
        Launch launch = new Launch();
        launch.mission = DroneMission.load(mission.save());
        launch.origin = origin;
        launch.squadId = squadId;
        launch.next = 1;
        launch.total = total;
        launch.interval = Math.max(1, DroneMission.clampInterval(mission.launchInterval));
        launch.dueTick = first;
        launches.add(launch);
        setDirty();
    }

    /**
     * @return how many drones are still waiting to go up across all flights. Reported by
     * {@code /wfballistics drone list} so a squad that looks short-handed can be told apart from one that is
     * still launching.
     */
    public int pending() {
        int total = 0;
        for (Launch launch : launches) {
            total += launch.total - launch.next;
        }
        return total;
    }

    /**
     * Abandon everything still queued.
     *
     * @return how many drones will now never launch
     */
    public int clear() {
        int dropped = pending();
        launches.clear();
        setDirty();
        return dropped;
    }

    public static DroneLaunchQueue load(CompoundTag tag) {
        DroneLaunchQueue queue = new DroneLaunchQueue();
        ListTag list = tag.getList("Launches", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            Launch launch = new Launch();
            launch.mission = DroneMission.load(one.getCompound("Mission"));
            launch.origin = new Vec3(one.getDouble("X"), one.getDouble("Y"), one.getDouble("Z"));
            launch.squadId = one.getLong("SquadId");
            launch.next = one.getInt("Next");
            launch.total = one.getInt("Total");
            launch.interval = Math.max(1, one.getInt("Interval"));
            launch.dueTick = one.getLong("DueTick");
            if (launch.next < launch.total) {
                queue.launches.add(launch);
            }
        }
        return queue;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Launch launch : launches) {
            CompoundTag one = new CompoundTag();
            one.put("Mission", launch.mission.save());
            one.putDouble("X", launch.origin.x);
            one.putDouble("Y", launch.origin.y);
            one.putDouble("Z", launch.origin.z);
            one.putLong("SquadId", launch.squadId);
            one.putInt("Next", launch.next);
            one.putInt("Total", launch.total);
            one.putInt("Interval", launch.interval);
            one.putLong("DueTick", launch.dueTick);
            list.add(one);
        }
        tag.put("Launches", list);
        return tag;
    }

    /**
     * One flight part-way off the ground.
     */
    private static final class Launch {
        private DroneMission mission;
        private Vec3 origin;
        private long squadId;
        private int next;
        private int total;
        private int interval;
        private long dueTick;
    }
}

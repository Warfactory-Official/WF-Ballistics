package com.wf.wflib.rail.build;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The machines currently building railway in a world, and the tick that drives them.
 *
 * <p>Deliberately not saved. A bore train is a machine somebody is standing next to watching; restoring
 * one on server start would have it cutting a tunnel with nobody there and no way to call it off, and
 * the tunnel it had already dug is in the world regardless. Everything durable a drive produces - the
 * tunnel, the track, the route's build record - is written as it goes, so losing the machine loses only
 * the rest of the shift.</p>
 */
public final class RailWorks {

    private static final Map<ResourceKey<Level>, List<BoreTrain>> TRAINS = new HashMap<>();
    private static final Map<ResourceKey<Level>, List<TrackJob>> JOBS = new HashMap<>();

    private RailWorks() {
    }

    /**
     * @return false when a machine is already on one of these routes, which is the only thing refused.
     *
     * <p>Against the whole journey and not only the leg being cut. Two machines told to build the same
     * line are two machines walling each other's tunnel off, and a train three junctions away that is
     * coming to this one later is still going to be on it.</p>
     */
    public static boolean start(ServerLevel level, BoreTrain train) {
        List<BoreTrain> running = TRAINS.computeIfAbsent(level.dimension(), key -> new ArrayList<>());
        for (BoreTrain other : running) {
            for (UUID route : other.routes()) {
                if (train.routes().contains(route)) {
                    return false;
                }
            }
        }
        running.add(train);
        return true;
    }

    public static void lay(ServerLevel level, TrackJob job) {
        JOBS.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(job);
    }

    public static List<BoreTrain> trains(ServerLevel level) {
        return List.copyOf(TRAINS.getOrDefault(level.dimension(), List.of()));
    }

    public static List<TrackJob> jobs(ServerLevel level) {
        return List.copyOf(JOBS.getOrDefault(level.dimension(), List.of()));
    }

    /** @return the machine driving this route, or null. */
    public static BoreTrain on(ServerLevel level, UUID routeId) {
        for (BoreTrain train : TRAINS.getOrDefault(level.dimension(), List.of())) {
            if (train.routeId().equals(routeId)) {
                return train;
            }
        }
        return null;
    }

    /** @return how many machines were called off. */
    public static int stopAll(ServerLevel level, String why) {
        List<BoreTrain> running = TRAINS.remove(level.dimension());
        int stopped = 0;
        if (running != null) {
            for (BoreTrain train : running) {
                train.halt(level, why);
                stopped++;
            }
        }
        List<TrackJob> laying = JOBS.remove(level.dimension());
        return stopped + (laying == null ? 0 : laying.size());
    }

    public static void tick(ServerLevel level) {
        drive(TRAINS.get(level.dimension()), train -> train.tick(level));
        drive(JOBS.get(level.dimension()), job -> job.tick(level));
    }

    /** Tick everything in a list, dropping whatever says it is finished. */
    private static <T> void drive(List<T> work, java.util.function.Predicate<T> tick) {
        if (work == null || work.isEmpty()) {
            return;
        }
        Iterator<T> it = work.iterator();
        while (it.hasNext()) {
            if (!tick.test(it.next())) {
                it.remove();
            }
        }
    }

    /** Server shutting down: the world stops existing, so the machines do too. */
    public static void clear() {
        TRAINS.clear();
        JOBS.clear();
    }
}

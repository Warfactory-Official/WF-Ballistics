package com.wf.wflib.build;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.compat.TerritoryVerdict;
import com.wf.wflib.drone.DroneEntity;
import com.wf.wflib.exchange.StationRecord;
import com.wf.wflib.exchange.StationRegistry;
import com.wf.wflib.exchange.StationRole;
import com.wf.wflib.work.WorkAssignment;
import com.wf.wflib.work.WorkJob;
import com.wf.wflib.work.WorkOrder;
import com.wf.wflib.work.WorkQueue;
import com.wf.wflib.work.WorkRegistry;
import com.wf.wflib.work.WorkStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/** The world-thread half of working a job: deciding what a drone does next, and doing it. */
public final class BuildPilot {

    /** How far down the pending queue a resupply looks when deciding what to load. */
    private static final int SHOPPING_HORIZON = 512;

    private BuildPilot() {
    }

    /**
     * Keep this drone pointed at something useful. Called once per tick per drone that is on a job.
     */
    public static void advance(ServerLevel level, DroneEntity drone) {
        WorkAssignment work = drone.assignment();
        if (work == null) {
            return;
        }
        WorkJob job = WorkRegistry.get(level).byId(work.jobId());
        if (job == null || job.over()) {
            standDown(level, drone, job == null ? "job is gone" : "job finished");
            return;
        }
        if (!worksJobs(drone.getDroneState())) {
            standDown(level, drone, "off the job: " + drone.getDroneState());
            return;
        }
        boolean open = job.workable();
        if (open != work.siteOpen()) {
            work = work.withSiteOpen(open);
            drone.setAssignment(work);
        }
        if (!open) {
            // Give the block back unpenalised: the order did nothing wrong, the ground changed under it.
            standDown(level, drone, "standing down: " + job.suspended());
            return;
        }
        if (work.hasOrder() || work.hasStation()) {
            return;
        }
        assign(level, drone, job);
    }

    /**
     * Pick this drone's next piece of work, or send it to a station, or send it home.
     */
    private static void assign(ServerLevel level, DroneEntity drone, WorkJob job) {
        boolean building = job.kind().equals(BuildJobs.CONSTRUCT);
        if (!building && drone.cargoFull()) {
            // Nowhere to put anything else. Everything recovered so far is worth more delivered than carried.
            sendToStation(level, drone, job, "cargo full");
            return;
        }
        WorkOrder order = job.queue().claim(drone.getUUID(), drone.position(), drone.getCruiseSpeed(),
                level.getGameTime());
        if (order == null) {
            if (job.queue().finished()) {
                detach(drone, "nothing left to do");
                return;
            }
            sendToStation(level, drone, job, "waiting for the next layer");
            return;
        }
        if (building) {
            BlockState state = stateFor(level, job, order.data());
            Item item = state == null ? null : WorkPlan.itemFor(state);
            if (item == null) {
                job.queue().release(drone.getUUID(), order.id(), true);
                return;
            }
            if (!drone.hasItem(item)) {
                job.queue().release(drone.getUUID(), order.id(), false);
                sendToStation(level, drone, job, "out of " + item.getDescription().getString());
                return;
            }
        }
        drone.setAssignment(drone.assignment().withOrder(order.at(), order.id(), order.data())
                .withStation(null));
        drone.setDestination(Vec3.atCenterOf(order.at()));
    }

    /**
     * Point the drone at the job's station, or home if it has not got one.
     */
    private static void sendToStation(ServerLevel level, DroneEntity drone, WorkJob job, String why) {
        Vec3 station = stationFor(level, drone, job);
        if (station == null) {
            detach(drone, why + ", and no station to go to");
            return;
        }
        drone.setAssignment(drone.assignment().withOrder(null, -1, 0).withStation(station));
        drone.setDestination(station);
        drone.recordEvent(WFEventType.EXFIL, why + ", heading for the station");
    }

    /**
     * @return where this job's supplier or depot is, or null if there is not one to be had.
     */
    @Nullable
    private static Vec3 stationFor(ServerLevel level, DroneEntity drone, WorkJob job) {
        StationRegistry stations = StationRegistry.get(level);
        String named = BuildJobs.stationOf(job);
        if (named != null) {
            StationRecord record = stations.byCode(named);
            if (record != null && record.dimension().equals(level.dimension())) {
                return record.launchPoint();
            }
        }
        StationRole wanted = job.kind().equals(BuildJobs.CONSTRUCT)
                ? StationRole.MATERIALS : StationRole.DEPOT;
        var candidates = stations.withRole(level.dimension(), BlockPos.containing(drone.position()), wanted);
        return candidates.isEmpty() ? null : candidates.get(0).launchPoint();
    }

    /** Do the work at the block the drone says it is over. */
    public static void finish(ServerLevel level, DroneEntity drone, BlockPos at) {
        WorkAssignment work = drone.assignment();
        if (work == null || !work.hasOrder() || !work.order().equals(at)) {
            return;
        }
        WorkJob job = WorkRegistry.get(level).byId(work.jobId());
        if (job == null) {
            detach(drone, "job is gone");
            return;
        }
        WorkQueue.Claim claim = job.queue().claimOf(work.orderId());
        if (claim == null || !claim.worker().equals(drone.getUUID())) {
            clearOrder(drone);
            return;
        }
        TerritoryVerdict verdict = SiteRules.mayTouch(level, BuildJobs.factionOf(job), at);
        if (!verdict.allowed()) {
            job.queue().release(drone.getUUID(), work.orderId(), false);
            if (job.suspend(verdict.reason())) {
                drone.recordEvent(WFEventType.MISSION_COMPLETE, "stood down: " + verdict.reason());
            }
            clearOrder(drone);
            return;
        }

        boolean done;
        if (job.kind().equals(BuildJobs.CONSTRUCT)) {
            BlockState state = stateFor(level, job, work.data());
            done = state != null && drone.placeFromCargo(level, at, state);
        } else {
            done = drone.breakIntoCargo(level, at);
        }
        if (done) {
            job.queue().complete(drone.getUUID(), work.orderId());
        } else {
            job.queue().release(drone.getUUID(), work.orderId(), true);
        }
        clearOrder(drone);
    }

    /**
     * Move items between the drone and the station it is sitting over.
     */
    public static void exchange(ServerLevel level, DroneEntity drone) {
        WorkAssignment work = drone.assignment();
        if (work == null || !work.hasStation()) {
            return;
        }
        WorkJob job = WorkRegistry.get(level).byId(work.jobId());
        if (job == null) {
            detach(drone, "job is gone");
            return;
        }
        int moved;
        if (job.kind().equals(BuildJobs.CONSTRUCT)) {
            moved = drone.loadFromStation(level, work.station(), shoppingList(level, job));
            drone.recordEvent(WFEventType.CARGO_PICKUP, "loaded " + moved + " item(s)");
        } else {
            moved = drone.unloadToStation(level, work.station());
            drone.recordEvent(WFEventType.CARGO_DROP, "handed over " + moved + " item(s)");
        }
        drone.setAssignment(work.withStation(null));
        if (moved == 0 && job.kind().equals(BuildJobs.CONSTRUCT)) {
            detach(drone, "the supplier has nothing this job needs");
        }
    }

    /**
     * @return the items the next stretch of this job will need, commonest first is irrelevant: what matters
     *      is that they are the ones coming up.
     */
    private static Set<Item> shoppingList(ServerLevel level, WorkJob job) {
        Set<Item> wanted = new LinkedHashSet<>();
        if (!job.kind().equals(BuildJobs.CONSTRUCT)) {
            return wanted;
        }
        WorkQueue queue = job.queue();
        int looked = 0;
        for (int id = 0; id < queue.size() && looked < SHOPPING_HORIZON; id++) {
            if (queue.statusOf(id) != WorkStatus.PENDING) {
                continue;
            }
            looked++;
            BlockState state = stateFor(level, job, queue.order(id).data());
            Item item = state == null ? null : WorkPlan.itemFor(state);
            if (item != null) {
                wanted.add(item);
            }
        }
        return wanted;
    }

    /**
     * @return the block state a construction order's palette index refers to, or null if the blueprint cannot
     *      be read any more.
     */
    @Nullable
    private static BlockState stateFor(ServerLevel level, WorkJob job, int paletteIndex) {
        String name = BuildJobs.blueprintOf(job);
        if (name == null) {
            return null;
        }
        try {
            Blueprint blueprint = BlueprintLibrary.load(level.getServer(), name);
            return paletteIndex > 0 && paletteIndex < blueprint.palette().size()
                    ? blueprint.palette().get(paletteIndex) : null;
        } catch (BlueprintFormat.BlueprintException e) {
            return null;
        }
    }

    private static void clearOrder(DroneEntity drone) {
        WorkAssignment work = drone.assignment();
        if (work != null) {
            drone.setAssignment(work.withOrder(null, -1, 0));
        }
    }

    /**
     * @return true while a drone in this state is still able to work. Everything else is on its way home,
     *      on the ground, or falling
     */
    private static boolean worksJobs(com.wf.wflib.drone.DroneState state) {
        return switch (state) {
            case TAKEOFF, MUSTER, TRANSIT, WORK, SUPPLY -> true;
            default -> false;
        };
    }

    /** Take this drone off the job, giving back whatever it was holding, unpenalised. */
    public static void standDown(ServerLevel level, DroneEntity drone, String why) {
        WorkJob job = drone.assignment() == null ? null
                : WorkRegistry.get(level).byId(drone.assignment().jobId());
        if (job != null) {
            job.queue().abandon(drone.getUUID());
        }
        drone.setAssignment(null);
        drone.recordEvent(WFEventType.MISSION_COMPLETE, why);
    }

    /** Take this drone off the job and stop it going anywhere. */
    public static void detach(DroneEntity drone, String why) {
        drone.setAssignment(null);
        drone.setDestination(null);
        drone.recordEvent(WFEventType.MISSION_COMPLETE, why);
    }
}

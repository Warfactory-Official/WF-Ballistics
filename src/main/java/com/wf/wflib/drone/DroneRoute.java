package com.wf.wflib.drone;

import com.wf.wflib.drone.ai.DroneNav;
import com.wf.wflib.drone.nav.DroneNavigation;
import com.wf.wflib.drone.nav.DronePath;
import com.wf.wflib.drone.nav.TerrainField;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Where the drone is going: destination, home (exfil), staging legs, egress dogleg, the planned path. */
public final class DroneRoute {

    /**
     * "No route has ever been asked for", distinct from any real game time.
     */
    private static final long NEVER = Long.MIN_VALUE;

    private final DroneEntity drone;
    @Nullable
    private Vec3 destination;
    private Vec3 exfil = Vec3.ZERO;
    /** Staging waypoints still to be flown before the current destination. */
    private final ArrayDeque<Vec3> legs = new ArrayDeque<>();
    /** The dogleg to fly <em>after</em> the drop, installed into {@link #legs} the moment the cargo is released. */
    private final List<Vec3> egressPlan = new ArrayList<>();
    /** The route it is following over the terrain, planned off-thread. */
    @Nullable
    private DronePath path;
    /**
     * When the last route search was ordered, so one already running is not ordered again every tick.
     */
    private long pathRequestedAt = NEVER;

    DroneRoute(DroneEntity drone) {
        this.drone = drone;
    }

    @Nullable
    public DronePath getPath() {
        return this.path;
    }

    void adoptPath(DronePath path) {
        this.path = path;
    }

    @Nullable
    public Vec3 getDestination() {
        return this.destination;
    }

    public void setDestination(@Nullable Vec3 destination) {
        this.destination = destination;
        this.path = null;
    }

    public Vec3 getExfil() {
        return this.exfil;
    }

    public void setExfil(Vec3 exfil) {
        this.exfil = exfil;
    }

    @Nullable
    Vec3 nextLeg() {
        return this.legs.peek();
    }

    void advanceLeg() {
        this.legs.poll();
    }

    /**
     * @return the staging waypoints still to fly, for diagnostics.
     */
    public int remainingLegs() {
        return this.legs.size();
    }

    void setLegs(List<Vec3> approach, List<Vec3> egress) {
        this.legs.clear();
        this.legs.addAll(approach);
        this.egressPlan.clear();
        this.egressPlan.addAll(egress);
    }

    /**
     * Swap the remaining route for the egress dogleg. Does nothing on a direct mission, which has none.
     */
    void beginEgress() {
        this.legs.clear();
        this.legs.addAll(this.egressPlan);
        this.egressPlan.clear();
    }

    /** Everything the planner needs to know about the ground, read here because a worker cannot read it itself. */
    DroneNav sampleNav(ServerLevel level, Vec3 pos, double groundY, long gameTime) {
        DroneFlight flight = this.drone.flight();
        Vec3 goal = DroneNavigation.goal(flight.getDroneState(), this.legs.peek(), this.destination, this.exfil);
        boolean overdue = this.pathRequestedAt == NEVER
                || gameTime - this.pathRequestedAt > DroneNavigation.PLAN_RETRY_TICKS;
        boolean due = overdue && DroneNavigation.needsPlan(this.path, pos, goal, gameTime);
        TerrainField field = due ? DroneNavigation.field(level, pos, goal, groundY) : null;
        if (field != null) {
            this.pathRequestedAt = gameTime;
        }
        double planningSpeed = Math.max(flight.getCruiseSpeed(), flight.getReleaseSpeed());
        double required = flight.getDroneState().followsTerrain()
                ? DroneNavigation.requiredAltitude(level, pos, this.drone.getDeltaMovement(), planningSpeed,
                flight.getClimbRate(), groundY)
                : Double.NEGATIVE_INFINITY;
        return new DroneNav(this.path, field, required);
    }

    private static List<Vec3> readLegs(CompoundTag tag, String key) {
        List<Vec3> out = new ArrayList<>();
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag leg = list.getCompound(i);
            out.add(new Vec3(leg.getDouble("X"), leg.getDouble("Y"), leg.getDouble("Z")));
        }
        return out;
    }

    private static void writeLegs(CompoundTag tag, String key, Iterable<Vec3> legs) {
        ListTag list = new ListTag();
        for (Vec3 leg : legs) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("X", leg.x);
            entry.putDouble("Y", leg.y);
            entry.putDouble("Z", leg.z);
            list.add(entry);
        }
        tag.put(key, list);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (this.destination != null) {
            tag.putDouble("DestX", this.destination.x);
            tag.putDouble("DestY", this.destination.y);
            tag.putDouble("DestZ", this.destination.z);
        }
        tag.putDouble("ExfilX", this.exfil.x);
        tag.putDouble("ExfilY", this.exfil.y);
        tag.putDouble("ExfilZ", this.exfil.z);
        writeLegs(tag, "Legs", this.legs);
        writeLegs(tag, "Egress", this.egressPlan);
        return tag;
    }

    void load(CompoundTag tag) {
        this.destination = tag.contains("DestX")
                ? new Vec3(tag.getDouble("DestX"), tag.getDouble("DestY"), tag.getDouble("DestZ")) : null;
        this.exfil = new Vec3(tag.getDouble("ExfilX"), tag.getDouble("ExfilY"), tag.getDouble("ExfilZ"));
        this.setLegs(readLegs(tag, "Legs"), readLegs(tag, "Egress"));
    }
}

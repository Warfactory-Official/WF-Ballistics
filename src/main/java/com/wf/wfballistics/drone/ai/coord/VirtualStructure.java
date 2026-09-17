package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.ai.DroneStateRegistry;
import com.wf.wfballistics.drone.ai.SquadView;
import com.wf.wfballistics.drone.ai.Steering;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Slots measured off a point that is not a drone. */
public final class VirtualStructure implements CoordinationModel {

    public static final VirtualStructure INSTANCE = new VirtualStructure();

    /**
     * How much of the gap between what the frame is doing and what the mission is asking for it to do is closed
     * each tick.
     */
    public static final double STEER_SMOOTHING = 0.12;
    /** How hard the frame is pulled back toward the squad actually flying it, per tick. */
    public static final double LEASH_GAIN = 0.05;
    /** The same correction on the vertical, where it can afford to be five times harder. */
    public static final double VERTICAL_LEASH_GAIN = 0.25;
    /**
     * How far the frame may end up from where the squad implies it should be before it is simply put back on the
     * leader and started again.
     */
    public static final double MAX_DRIFT = 64.0;
    /**
     * How far the worst-placed member may be from its station before the frame starts easing off to let it catch
     * up.
     */
    public static final double LAG_TOLERANCE = 8.0;
    /** Slowest the frame will be throttled to while it waits, as a fraction of what the mission asked for. */
    public static final double MIN_LAG_THROTTLE = 0.2;

    private VirtualStructure() {
    }

    @Override
    public String id() {
        return "virtual_structure";
    }

    @Override
    public boolean stationsLeader() {
        return true;
    }

    @Override
    public SquadAnchor advance(SquadView squad, @Nullable SquadAnchor previous) {
        DroneSnapshot leader = squad.leader();
        if (previous == null || !leader.state().powered()) {
            return SquadAnchor.on(leader);
        }

        Vec3 intent = DroneStateRegistry.get(leader.state()).guide(leader, squad)
                .scale(throttle(squad, previous));
        Vec3 velocity = previous.velocity().add(intent.subtract(previous.velocity()).scale(STEER_SMOOTHING));
        Vec3 acceleration = velocity.subtract(previous.velocity());
        Vec3 pos = previous.pos().add(velocity);

        Vec3 implied = impliedAnchor(squad, previous);
        Vec3 pull = implied.subtract(pos);
        if (pull.lengthSqr() > MAX_DRIFT * MAX_DRIFT) {
            return SquadAnchor.on(leader);
        }
        pos = pos.add(pull.x * LEASH_GAIN, pull.y * VERTICAL_LEASH_GAIN, pull.z * LEASH_GAIN);

        float yaw = velocity.horizontalDistance() < leader.cruiseSpeed() * Steering.HEADING_MIN_SPEED
                ? leader.yaw()
                : Steering.faceTravel(velocity, previous.yaw(),
                        leader.cruiseSpeed() * Steering.HEADING_MIN_SPEED);
        return new SquadAnchor(pos, velocity, acceleration, yaw, Steering.wrap(yaw - previous.yaw()));
    }

    @Override
    public SquadCommand guide(DroneSnapshot self, SquadView squad, SquadAnchor anchor) {
        return Slots.hold(self, Slots.track(self, squad, anchor));
    }

    /**
     * @return how much of the mission's asking speed the frame may use, given how far behind the worst-placed
     *      member of the squad is.
     */
    private static double throttle(SquadView squad, SquadAnchor anchor) {
        double worst = Slots.worstError(squad, anchor);
        if (worst <= LAG_TOLERANCE) {
            return 1.0;
        }
        return Mth.clamp(LAG_TOLERANCE / worst, MIN_LAG_THROTTLE, 1.0);
    }

    /**
     * @return the average of where every member implies the frame ought to be. See {@link #LEASH_GAIN} for
     *      why this is not the squad's centroid.
     */
    private static Vec3 impliedAnchor(SquadView squad, SquadAnchor anchor) {
        Vec3 sum = Vec3.ZERO;
        for (DroneSnapshot member : squad.slots()) {
            sum = sum.add(Slots.impliedAnchor(member, squad, anchor));
        }
        return squad.size() == 0 ? anchor.pos() : sum.scale(1.0 / squad.size());
    }
}

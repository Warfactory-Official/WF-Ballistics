package com.wf.wflib.rail.build.ir;

import cam72cam.immersiverailroading.thirdparty.trackapi.ITrack;
import cam72cam.immersiverailroading.util.VecUtil;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.world.World;
import com.wf.wflib.rail.align.AlignmentProgress;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How far a train would get from here, asked of Immersive Railroading's own track graph.
 *
 * <p>Coverage says there is track under the surveyed line. It does not say a train can run along it, and
 * the two are different questions with different answers: a railway is a chain in which each piece knows
 * the pieces either side of it, and a line of sleepers with a break in that chain looks identical from
 * every angle a person can stand at. This walks the graph the way stock moves along it, one short step
 * at a time, and reports where it stops.</p>
 *
 * <p>It is {@link ITrack#getNextPosition} and nothing else, which is the call
 * {@code EntityMoveableRollingStock} moves on. So at a flat crossing it takes whichever of the stacked
 * paths matches the direction it is already going, exactly as a train does, and at a break it stops
 * exactly where a train would stop. This is not a model of the answer. It is the answer.</p>
 */
public final class IrRoll {

    /** How far above a track block the rail sits, matching IR's own placement. */
    private static final double RAIL_HEIGHT = 0.7;

    /**
     * How far each step carries.
     *
     * <p>Over a quarter of a block on purpose, which is the threshold at which
     * {@code getNextPosition} hands the walk to IR's own iterative pathing: that substeps at a quarter
     * block, re-finds the track every time it crosses into a new block, and picks between stacked
     * tracks by the direction of travel, which is precisely what a train does and precisely what this
     * has to reproduce. Asking for less than a quarter block instead consults one piece with one
     * nearest-point calculation, and that calculation cannot reliably tell forwards from backwards over
     * a fifth of a block.</p>
     */
    private static final double STEP = 1.0;

    /** How far off the end of a piece to look for the next one before giving up. */
    private static final double REACH = 1.5;

    /** Blocks between asking which route the walk is on. Fine enough to catch a short run. */
    private static final double SAMPLE = 4.0;

    private IrRoll() {
    }

    /** One stretch of the run, on one route. */
    public record Leg(String route, double from, double to, double blocks) {

        public String describe() {
            return String.format(Locale.ROOT, "%s %.0f to %.0f (%.0f blocks)",
                    this.route, this.from, this.to, this.blocks);
        }
    }

    /**
     * @param blocks how far a train would get
     * @param end where it would come to a stand
     * @param stopped why, phrased for chat
     * @param legs the routes it ran over, in order
     * @param sharpest the worst turn the railway asked of it, in degrees
     * @param sharpestAt where that turn is
     */
    public record Ran(double blocks, Vec3 end, String stopped, List<Leg> legs, double sharpest,
                      Vec3 sharpestAt) {

        /** A turn worth mentioning: no surveyed curve here bends anything like this in one step. */
        public static final double NOTABLE_TURN = 15.0;

        public boolean kinked() {
            return this.sharpest >= NOTABLE_TURN;
        }

        /** The kink, phrased for chat, or an empty string when the line is smooth. */
        public String kink() {
            return kinked() ? String.format(Locale.ROOT, ", turning %.0f degrees at %.0f, %.0f",
                    this.sharpest, this.sharpestAt.x, this.sharpestAt.z) : "";
        }

        public String describe() {
            StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                    "%.0f blocks to %.0f, %.0f, then %s%s", this.blocks, this.end.x, this.end.z,
                    this.stopped, kink()));
            for (Leg leg : this.legs) {
                out.append("\n  over ").append(leg.describe());
            }
            return out.toString();
        }

        /** The routes it ran over, in order, which is the thing a reachability table is made of. */
        public List<String> routes() {
            List<String> out = new ArrayList<>();
            for (Leg leg : this.legs) {
                out.add(leg.route());
            }
            return out;
        }
    }

    /**
     * Roll from a position, in a direction, until the railway runs out.
     *
     * @param at    where to start, at track level rather than rail level
     * @param yaw   Minecraft's yaw, which is what a player is facing and what IR stock carries
     * @param limit how far to keep going before calling it far enough
     */
    public static Ran roll(ServerLevel level, Vec3 at, float yaw, double limit) {
        World world = World.get(level);
        Vec3d pos = new Vec3d(at.x, at.y + RAIL_HEIGHT, at.z);
        Vec3d motion = VecUtil.fromWrongYaw(STEP, yaw);
        ITrack track = ITrack.get(world, pos, true);
        if (track == null) {
            return new Ran(0.0, at, "there is no track here to start from", List.of(), 0.0, at);
        }
        pos = onTheRail(track, pos, motion);

        Legs legs = new Legs(level);
        double travelled = 0.0;
        double sinceSample = SAMPLE;
        double sharpest = 0.0;
        Vec3d sharpestAt = pos;
        String stopped = "far enough";
        while (travelled < limit) {
            Vec3d next = step(world, pos, motion);
            if (next == null) {
                stopped = "the rail ends there";
                break;
            }
            double moved = next.distanceTo(pos);
            // The direction the track just took, so a curve is followed rather than cut across.
            Vec3d went = next.subtract(pos).normalize().scale(STEP);
            double turned = turn(motion, went);
            if (turned > 90.0) {
                // The railway handed back the way it came. That is a dead end, and a train stops at
                // one rather than setting off back down the line; a walk that took it would run up and
                // down a stub until it ran out of patience and call the total a distance travelled.
                stopped = "the rail ends there and turns it back";
                break;
            }
            // The first step is not a kink in the railway, it is the gap between where the train was
            // stood and where the rails actually are. Only the steps after it are the track's own doing.
            if (turned > sharpest && travelled > 0.0) {
                sharpest = turned;
                sharpestAt = pos;
            }
            motion = went;
            pos = next;
            travelled += moved;
            sinceSample += moved;
            if (sinceSample >= SAMPLE) {
                sinceSample = 0.0;
                legs.at(pos, travelled);
            }
        }
        legs.at(pos, travelled);
        legs.close();
        return new Ran(travelled, new Vec3(pos.x, pos.y - RAIL_HEIGHT, pos.z), stopped, legs.done(),
                sharpest, new Vec3(sharpestAt.x, sharpestAt.y - RAIL_HEIGHT, sharpestAt.z));
    }

    /** The angle between two steps, in degrees, which on a surveyed curve is a fraction of one. */
    private static double turn(Vec3d was, Vec3d now) {
        double lengths = was.length() * now.length();
        if (lengths <= 1.0E-9) {
            return 0.0;
        }
        double cos = (was.x * now.x + was.z * now.z) / lengths;
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cos))));
    }

    /**
     * The same place, at the height the rails are actually at.
     *
     * <p>Worth the extra call. IR finds the next position by taking the point on the piece nearest to
     * where the motion would put you, and a start position half a block above or below the rail makes
     * that distance mostly vertical: the arithmetic then picks the nearer of the two candidates either
     * side, which for a short step is as likely to be the one behind as the one in front. The symptom
     * is a walk that ignores the direction it was asked for and always sets off the same way, which
     * reads as a perfectly good railway that trains can only run down one way.</p>
     */
    private static Vec3d onTheRail(ITrack track, Vec3d pos, Vec3d motion) {
        Vec3d probe = track.getNextPosition(pos, motion);
        return probe.distanceTo(pos) < 1.0E-4 ? pos : new Vec3d(pos.x, probe.y, pos.z);
    }

    /**
     * One step along the railway, or null where it ends.
     *
     * <p>Two lookups rather than one, and the second is the point. A piece answers only for its own
     * curve, so at a join the piece under the wheels has nothing left to give while the piece a block
     * ahead has the whole of the rest of the line. Asking only the first is how a continuous railway
     * reads as a broken one.</p>
     */
    private static Vec3d step(World world, Vec3d pos, Vec3d motion) {
        ITrack here = ITrack.get(world, pos, true);
        if (here != null) {
            Vec3d next = here.getNextPosition(pos, motion);
            if (next.distanceTo(pos) > 1.0E-4) {
                return next;
            }
        }
        Vec3d ahead = pos.add(motion.normalize().scale(REACH));
        ITrack beyond = ITrack.get(world, ahead, true);
        if (beyond == null || beyond == here) {
            return null;
        }
        Vec3d next = beyond.getNextPosition(pos, motion);
        return next.distanceTo(pos) > 1.0E-4 ? next : null;
    }

    /** Which route the walk is on, gathered as it goes. */
    private static final class Legs {

        private final ServerLevel level;
        private final List<Leg> legs = new ArrayList<>();
        private String current;
        private double from;
        private double last;

        private Legs(ServerLevel level) {
            this.level = level;
        }

        private void at(Vec3d pos, double travelled) {
            AlignmentProgress.Hit hit = AlignmentProgress.nearest(this.level, null, pos.x, pos.z, 6.0);
            String name = hit == null ? null : hit.alignment().name();
            if (name != null && name.equals(this.current)) {
                this.last = travelled;
                return;
            }
            close();
            this.current = name;
            this.from = travelled;
            this.last = travelled;
        }

        private void close() {
            if (this.current == null || this.last <= this.from) {
                this.current = null;
                return;
            }
            // Folded into the leg before it when it is the same route. A sample taken while the walk
            // is briefly nearer a line it is crossing than the one it is on would otherwise split one
            // run into three, and read as a train changing route twice and coming back.
            if (!this.legs.isEmpty() && this.legs.get(this.legs.size() - 1).route().equals(this.current)) {
                Leg previous = this.legs.remove(this.legs.size() - 1);
                this.legs.add(new Leg(previous.route(), previous.from(), this.last,
                        this.last - previous.from()));
            } else {
                this.legs.add(new Leg(this.current, this.from, this.last, this.last - this.from));
            }
            this.current = null;
        }

        private List<Leg> done() {
            return List.copyOf(this.legs);
        }
    }
}

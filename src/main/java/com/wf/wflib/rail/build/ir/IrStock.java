package com.wf.wflib.rail.build.ir;

import cam72cam.immersiverailroading.entity.EntityBuildableRollingStock;
import cam72cam.immersiverailroading.entity.EntityMoveableRollingStock;
import cam72cam.immersiverailroading.entity.EntityRollingStock;
import cam72cam.immersiverailroading.library.Gauge;
import cam72cam.immersiverailroading.registry.DefinitionManager;
import cam72cam.immersiverailroading.registry.EntityRollingStockDefinition;
import cam72cam.immersiverailroading.thirdparty.trackapi.ITrack;
import cam72cam.immersiverailroading.util.VecUtil;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.world.World;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Puts a piece of Immersive Railroading stock on a railway, from a command.
 *
 * <p>Tooling rather than a feature, and the only honest way to finish the job: stock is normally placed
 * by holding the item and clicking the track, and the one thing that proves a line a machine just built
 * can be driven is a locomotive standing on it.</p>
 *
 * <p>It goes through the same gate IR's own placement does - {@link ITrack#get} at the position, which
 * is a question about the <b>track graph</b> and not about blocks - so "there is no track here" is a
 * real answer rather than a guess, and the gauge comes from the rail rather than from a setting that
 * might disagree with it.</p>
 */
public final class IrStock {

    /** How far above a track block IR looks for the rail, matching its own placement. */
    private static final double RAIL_HEIGHT = 0.7;

    private IrStock() {
    }

    /**
     * @param name what was placed, or null when nothing was
     * @param problem why not, phrased for chat, or null when it worked
     */
    public record Placed(String name, String problem, double gauge) {
    }

    /** Every piece of stock this install knows about, which depends on the packs loaded. */
    public static List<String> names() {
        Set<String> names = DefinitionManager.getDefinitionNames();
        return names == null ? List.of() : names.stream().sorted().toList();
    }

    /**
     * Place stock on the track under a position.
     *
     * @param yaw Minecraft's yaw, which is also the one IR stock carries
     */
    public static Placed spawn(ServerLevel level, Vec3 at, float yaw, String defId) {
        EntityRollingStockDefinition definition = DefinitionManager.getDefinition(defId);
        if (definition == null) {
            return new Placed(null, "no stock called '" + defId + "'", 0.0);
        }
        World world = World.get(level);
        Vec3d pos = new Vec3d(at.x, at.y, at.z);
        ITrack track = ITrack.get(world, pos.add(new Vec3d(0.0, RAIL_HEIGHT, 0.0)), true);
        if (track == null) {
            return new Placed(null, "there is no track here to stand it on", 0.0);
        }
        // The rail's own gauge, not a configured one: stock on the wrong gauge is stock that will not
        // move, and the track is the thing that knows.
        Gauge gauge = Gauge.from(track.getTrackGauge());
        EntityRollingStock stock = definition.spawn(world, pos, yaw, gauge, null);
        align(track, stock, definition, gauge, yaw);
        // A piece of IR stock is assembled from parts, and one spawned without them is a bare frame
        // that renders as nothing at all: present in the world, listed by IR's own debug command, and
        // invisible. Everything the definition lists, which is what a crafted item carries.
        if (stock instanceof EntityBuildableRollingStock buildable) {
            buildable.setComponents(definition.getItemComponents());
        }
        world.spawnEntity(stock);
        return new Placed(definition.name(), null, gauge.value());
    }

    /**
     * Sit the stock on the rails rather than at the position it was asked for.
     *
     * <p>A locomotive is not a point. Its two bogies are metres apart and each has to be on the track,
     * which on a curve are two different places: IR works out where the body goes by walking the graph
     * to each bogie and putting the body between them. Skipping this leaves stock that looks placed and
     * derails on the first tick.</p>
     */
    private static void align(ITrack track, EntityRollingStock stock,
                              EntityRollingStockDefinition definition, Gauge gauge, float yaw) {
        if (!(stock instanceof EntityMoveableRollingStock moveable)) {
            return;
        }
        float frontDistance = definition.getBogeyFront(gauge);
        float rearDistance = definition.getBogeyRear(gauge);
        if (frontDistance == rearDistance) {
            return;
        }
        Vec3d pos = stock.getPosition();
        Vec3d front = track.getNextPosition(pos, VecUtil.fromWrongYaw(frontDistance, yaw));
        Vec3d rear = track.getNextPosition(pos, VecUtil.fromWrongYaw(rearDistance, yaw));
        Vec3d along = front.subtract(rear);
        moveable.setRotationYaw(VecUtil.toWrongYaw(along));
        moveable.setPosition(rear.add(along.scale(frontDistance / (frontDistance - rearDistance))));
        moveable.newlyPlaced = true;
    }

    /** @param gauge the gauge the track reports, so a mismatch shows up as a number rather than a guess. */
    public record Coverage(int onTrack, int sampled, double firstGap, double gauge) {

        public boolean complete() {
            return this.sampled > 0 && this.onTrack == this.sampled;
        }

        public String describe() {
            if (this.sampled == 0) {
                return "nothing to check";
            }
            // Floored rather than rounded, deliberately. A route one sample short of whole rounds to
            // a hundred per cent, and a check that can print 100% beside a gap is worse than no check.
            return String.format(Locale.ROOT, "%d of %d point(s) are on track (%.0f%%)%s%s",
                    this.onTrack, this.sampled,
                    Math.floor(this.onTrack * 100.0 / this.sampled),
                    this.gauge > 0.0 ? String.format(Locale.ROOT, ", %.3fm gauge", this.gauge) : "",
                    this.firstGap < 0.0 ? ""
                            : String.format(Locale.ROOT, ", first gap at %.0f blocks", this.firstGap));
        }
    }

    /**
     * Walk a route and ask the track graph whether there is a railway on it.
     *
     * <p>This is the check that means something. A tunnel with sleepers drawn down the middle of it
     * looks finished from any angle a person stands at, and a train can only run where the graph says
     * there is track: asking the graph, point by point along the surveyed line, is the difference
     * between a railway and a picture of one.</p>
     */
    public static Coverage check(ServerLevel level, com.wf.wflib.rail.align.Centreline line, int floorY,
                                 double step) {
        return check(level, line, floorY, step, 0.0, line.length());
    }

    /**
     * The same, over one stretch of a route.
     *
     * <p>Which is what a route on a network wants asked of it. The first and last stretch of a line that
     * meets another one belongs to the <em>junction</em> rather than to the line: the line deliberately
     * lays no track there, so counting those blocks against it reports a hole in a railway that is
     * exactly as it was meant to be built. See {@code TrackLines.leftToTheJunction}.</p>
     */
    public static Coverage check(ServerLevel level, com.wf.wflib.rail.align.Centreline line, int floorY,
                                 double step, double from, double to) {
        World world = World.get(level);
        double spacing = Math.max(0.5, step);
        double first = Math.max(0.0, from);
        double last = Math.min(line.length(), to);
        java.util.List<Double> at = new java.util.ArrayList<>();
        for (double s = first; s < last; s += spacing) {
            at.add(s);
        }
        // The far end, always, and not left to whether the route happens to be a whole number of steps
        // long. It almost never is, so the last stretch of every route would otherwise go unasked, and
        // it is the stretch most likely to be missing: the end is where the layer trims its piece
        // against whatever it found there.
        at.add(last);

        int on = 0;
        double firstGap = -1.0;
        double gauge = 0.0;
        for (double s : at) {
            var sample = line.at(s);
            ITrack track = ITrack.get(world,
                    new Vec3d(sample.x(), floorY + RAIL_HEIGHT, sample.z()), true);
            if (track != null) {
                on++;
                gauge = track.getTrackGauge();
            } else if (firstGap < 0.0) {
                firstGap = s;
            }
        }
        return new Coverage(on, at.size(), firstGap, gauge);
    }
}

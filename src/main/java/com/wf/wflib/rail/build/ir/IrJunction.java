package com.wf.wflib.rail.build.ir;

import cam72cam.immersiverailroading.items.nbt.RailSettings;
import cam72cam.immersiverailroading.library.SwitchState;
import cam72cam.immersiverailroading.library.TrackDirection;
import cam72cam.immersiverailroading.tile.TileRail;
import cam72cam.immersiverailroading.tile.TileRailBase;
import cam72cam.immersiverailroading.track.BuilderBase;
import cam72cam.immersiverailroading.track.TrackBase;
import cam72cam.immersiverailroading.util.PlacementInfo;
import cam72cam.immersiverailroading.util.RailInfo;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.world.World;
import com.mojang.logging.LogUtils;
import com.wf.wflib.rail.RailConfig;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.build.JunctionCurve;
import com.wf.wflib.rail.build.TrackPieces;
import com.wf.wflib.rail.excavate.CarvePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The curve that turns two routes meeting end to end into one railway.
 *
 * <p>Two surveyed routes that end at the same point do not make a railway a train can run along, and
 * the reason is geometry rather than anything the builder does wrong. Where they meet at an angle, the
 * rails <b>kink</b>: one line's last sleeper and the other's first sit in the same block pointing in
 * different directions. A real railway never does that, and neither does Immersive Railroading's
 * pathing, which asks each piece of track under a train for the point on it nearest to where the train
 * is going and therefore always prefers to <em>carry straight on</em>. At a kink, carrying straight on
 * leaves both railways. Measured: of six joints in a test network, only the one whose two routes were
 * in line carried a train, and the other five stopped it dead at the junction with perfect track on
 * both sides.</p>
 *
 * <p>So a joint gets the same treatment a real junction gets: a <b>connecting curve</b>, tangent to
 * both routes, taking the whole of the deflection at a known radius. Each route leaves its last
 * {@code lead} blocks to it, exactly as a branch leaves its first blocks to a turnout, so the curve
 * carries the junction and neither route has an anchor block in it.</p>
 *
 * <p>It is one {@code CUSTOM} cubic, which is the same thing {@link IrTrackLine} lays a few thousand of
 * to build a route, and that is deliberate: a switch is the thing that does not work on this version of
 * IR, and a joint needs no switch because a train is not being <em>sent</em> anywhere. There is only one
 * road through it.</p>
 */
public final class IrJunction {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Sharpest connection worth building, in degrees.
     *
     * <p>Past a right angle the two routes are not joining, they are two lines that happen to stop in
     * the same place, and the curve that would connect them is tighter than the chamber they meet in.
     * Saying so names the survey fix - move the stop, or put a curve on one of the routes - instead of
     * building something nothing can run on.</p>
     */
    public static final double MAX_DEFLECTION = 100.0;

    /** Cells above the rail kept clear, so something can actually run through the junction. */
    private static final int HEADROOM = 3;

    /** How far off the centreline two curve ends may be and still be the same curve, in blocks. */
    private static final double SAME_PIECE = 1.5;

    /** Update and notify, so a block walled against water tells the water it is there. */
    private static final int LINING_FLAGS = 3;

    private IrJunction() {
    }

    /**
     * @param problem why it was not built, phrased for chat, or null when it was
     * @param deflection how far the two routes are off being in line, in degrees
     * @param radius the radius of the connecting curve, in blocks; infinite when the two are in line
     * @param cut blocks taken out to fit the curve in
     */
    public record Built(boolean done, String problem, double deflection, double radius, int cut) {

        static Built no(String problem) {
            return new Built(false, problem, 0.0, 0.0, 0);
        }

        /** The curve, phrased for chat. A joint in line is a straight and has no radius to quote. */
        public String describe() {
            return this.radius > 4000.0
                    ? String.format(Locale.ROOT, "in line, %.0f blocks of connecting rail",
                            RailConfig.TURNOUT_LEAD.get() * 2.0)
                    : String.format(Locale.ROOT, "%.0f degrees at radius %.0f", this.deflection,
                            this.radius);
        }
    }

    /**
     * Put a connecting curve where two routes join end to end.
     *
     * @param a the centreline of the meeting's first route, b the second's
     * @param lead how far back along each route the curve starts, in blocks
     */
    public static Built build(ServerLevel level, RouteMeeting meeting, Centreline a, Centreline b,
                              int floorY, String track, double gauge, double lead) {
        if (meeting.kind() != RouteMeeting.Kind.JOINT) {
            return Built.no("that meeting is a " + meeting.kind().lowerName() + ", not a joint");
        }
        double reach = Math.max(4.0, lead);
        JunctionCurve curve = JunctionCurve.between(a, b, meeting, reach);
        if (curve == null) {
            return Built.no(meeting.a().label() + " and " + meeting.b().label()
                    + " join, but one of them is too short to give the junction its curve");
        }
        double deflection = curve.deflection();
        if (Math.abs(deflection) > MAX_DEFLECTION) {
            return Built.no(String.format(Locale.ROOT,
                    "%s and %s meet at %.0f degrees, which is a corner rather than a junction;"
                            + " move the stop, or give one of the routes a curve into it",
                    meeting.a().label(), meeting.b().label(), Math.abs(deflection)));
        }

        int bx = (int) Math.floor(curve.x1());
        int bz = (int) Math.floor(curve.z1());
        if (!level.isLoaded(new BlockPos(bx, floorY, bz))
                || !level.isLoaded(BlockPos.containing(curve.x2(), floorY, curve.z2()))) {
            return Built.no("the ground at the junction is not loaded");
        }

        RailSettings settings = IrTrackLine.settings(track, gauge);
        PlacementInfo near = new PlacementInfo(relative(curve.x1(), curve.z1(), bx, bz),
                TrackDirection.NONE, TrackPieces.Piece.yawOf(curve.heading1()),
                relative(curve.c1x(), curve.c1z(), bx, bz));
        PlacementInfo far = new PlacementInfo(relative(curve.x2(), curve.z2(), bx, bz),
                TrackDirection.NONE, (TrackPieces.Piece.yawOf(curve.heading2()) + 180.0f) % 360.0f,
                relative(curve.c2x(), curve.c2z(), bx, bz));

        World world = World.get(level);
        RailInfo info = new RailInfo(settings, near, far, SwitchState.NONE, SwitchState.NONE, 0.0);
        BuilderBase builder = info.getBuilder(world, new Vec3i(bx, floorY, bz));
        if (builder == null) {
            return Built.no("Immersive Railroading has no builder for a curve of this shape");
        }
        // Lay the curve's sleepers over both routes' own and keep theirs underneath, which is how a
        // junction shares its ground: IR offers a train every path it finds in a block.
        builder.overrideFlexible = true;
        List<TrackBase> laid = builder.getTracksForRender();

        int relaid = clearOwn(level, world, builder, laid);
        String anchored = foreignAnchor(world, laid);
        if (anchored != null) {
            return Built.no(anchored);
        }
        int cut = clear(level, laid);
        if (!builder.canBuild()) {
            return Built.no(blockage(level, laid));
        }
        builder.build();
        String orphan = orphaned(world, laid);
        if (orphan != null) {
            return Built.no("Immersive Railroading laid the junction curve and left it unanchored ("
                    + orphan + "), so it takes it back out again within a second");
        }
        LOGGER.info("[wflib] junction curve at {}, {}: {} to {}, {} degrees, radius {}, {} cell(s),"
                        + " {} cut, {} of its own relaid", bx, bz, meeting.a().label(),
                meeting.b().label(), String.format(Locale.ROOT, "%.1f", deflection),
                String.format(Locale.ROOT, "%.0f", curve.radius()), laid.size(), cut, relaid);
        return new Built(true, null, Math.abs(deflection), curve.radius(), cut);
    }

    /**
     * Take out this junction's own earlier curve, so the command can be run twice.
     *
     * <p>Only a piece whose two ends are this curve's two ends, compared in world coordinates. Anything
     * else in the footprint is one of the two routes, or somebody else's railway, and both of those are
     * refused below rather than removed.</p>
     */
    private static int clearOwn(ServerLevel level, World world, BuilderBase builder,
                                List<TrackBase> laid) {
        int removed = 0;
        for (TrackBase track : laid) {
            Vec3i at = track.getPos();
            TileRail rail = world.getBlockEntity(at, TileRail.class);
            if (rail == null || rail.info == null || !samePiece(rail, at, builder)) {
                continue;
            }
            level.destroyBlock(new BlockPos(at.x, at.y, at.z), false);
            removed++;
        }
        return removed;
    }

    private static boolean samePiece(TileRail rail, Vec3i at, BuilderBase builder) {
        Vec3d here = new Vec3d(at);
        Vec3d ours = new Vec3d(builder.pos);
        return rail.info.placementInfo.placementPosition.add(here)
                .distanceTo(builder.info.placementInfo.placementPosition.add(ours)) < SAME_PIECE
                && rail.info.customInfo.placementPosition.add(here)
                .distanceTo(builder.info.customInfo.placementPosition.add(ours)) < SAME_PIECE;
    }

    /**
     * Whether this curve would put its own anchor on another railway's.
     *
     * <p>Only that case. A <em>sleeper</em> over another line's anchor is what a shared junction block
     * is made of and IR keeps both: {@code BuilderBase.build} spots it and files this curve's sleeper
     * inside the other tile rather than replacing it. Two anchors in one block is the case IR cannot
     * resolve, and it is the one worth a refusal with a position in it.</p>
     */
    private static String foreignAnchor(World world, List<TrackBase> laid) {
        for (TrackBase track : laid) {
            Vec3i at = track.getPos();
            if (world.getBlockEntity(at, TileRail.class) == null || track.isOverTileRail()) {
                continue;
            }
            return "a piece of track is anchored at " + at.x + ", " + at.y + ", " + at.z
                    + ", and this curve wants its own anchor in that block;"
                    + " lay that route again so its joins fall clear of the junction";
        }
        return null;
    }

    /**
     * Open the ground the curve runs through, sealing anything wet against it first.
     *
     * <p>A connecting curve cuts the corner between two tunnels, and the wedge between them is ground
     * nobody bored. It is a handful of blocks and it is bounded by the curve itself, so it can only ever
     * open the two tunnels into each other. Fluid is walled rather than refused, which is the order the
     * tunnel keeps: line the tube, empty it, then open it.</p>
     */
    private static int clear(ServerLevel level, List<TrackBase> laid) {
        List<BlockPos> opening = new ArrayList<>();
        Set<Long> inside = new HashSet<>();
        for (TrackBase track : laid) {
            if (track.canPlaceTrack()) {
                continue;
            }
            Vec3i at = track.getPos();
            for (int up = 0; up < HEADROOM; up++) {
                BlockPos pos = new BlockPos(at.x, at.y + up, at.z);
                opening.add(pos);
                inside.add(pos.asLong());
            }
        }
        if (opening.isEmpty()) {
            return 0;
        }
        BlockState lining = CarvePlan.stateOf(RailConfig.TUNNEL_LINING.get());
        for (BlockPos pos : opening) {
            for (Direction face : Direction.values()) {
                BlockPos side = pos.relative(face);
                if (inside.contains(side.asLong())
                        || level.getBlockState(side).getFluidState().isEmpty()) {
                    continue;
                }
                level.setBlock(side, lining, LINING_FLAGS);
            }
        }
        for (BlockPos pos : opening) {
            if (!level.getBlockState(pos).getFluidState().isEmpty()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), LINING_FLAGS);
            }
        }
        int blocks = 0;
        for (BlockPos pos : opening) {
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            level.destroyBlock(pos, false);
            blocks++;
        }
        return blocks;
    }

    /** Whether any sleeper of the new curve points at an anchor block that is not there. */
    private static String orphaned(World world, List<TrackBase> laid) {
        for (TrackBase track : laid) {
            Vec3i at = track.getPos();
            TileRailBase tile = world.getBlockEntity(at, TileRailBase.class);
            if (tile == null || tile.getParent() == null
                    || world.getBlockEntity(tile.getParent(), TileRail.class) != null) {
                continue;
            }
            Vec3i parent = tile.getParent();
            return "no anchor at " + parent.x + ", " + parent.y + ", " + parent.z;
        }
        return null;
    }

    private static String blockage(ServerLevel level, List<TrackBase> laid) {
        StringBuilder blocked = new StringBuilder();
        int named = 0;
        for (TrackBase track : laid) {
            if (track.canPlaceTrack() || named >= 3) {
                continue;
            }
            Vec3i at = track.getPos();
            BlockPos pos = new BlockPos(at.x, at.y, at.z);
            blocked.append(named == 0 ? "" : ", ").append(pos.toShortString()).append(" is ")
                    .append(level.getBlockState(pos).getBlock().getName().getString())
                    .append(track.isDownSolid(true) ? "" : " (and nothing solid under it)");
            named++;
        }
        return "the junction curve will not go in: "
                + (blocked.length() == 0 ? "no reason given" : blocked.toString());
    }

    private static Vec3d relative(double x, double z, int bx, int bz) {
        return new Vec3d(x - bx, 0.0, z - bz);
    }
}

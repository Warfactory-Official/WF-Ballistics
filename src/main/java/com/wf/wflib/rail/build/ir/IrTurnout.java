package com.wf.wflib.rail.build.ir;

import cam72cam.immersiverailroading.items.nbt.RailSettings;
import cam72cam.immersiverailroading.library.SwitchState;
import cam72cam.immersiverailroading.library.TrackDirection;
import cam72cam.immersiverailroading.library.TrackItems;
import cam72cam.immersiverailroading.tile.TileRail;
import cam72cam.immersiverailroading.tile.TileRailBase;
import cam72cam.immersiverailroading.track.BuilderBase;
import cam72cam.immersiverailroading.track.TrackBase;
import cam72cam.immersiverailroading.util.PlacementInfo;
import cam72cam.immersiverailroading.util.RailInfo;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.world.World;
import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.RailConfig;
import com.wf.wflib.rail.build.TrackPieces;
import com.wf.wflib.rail.excavate.CarvePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Builds a real Immersive Railroading turnout where a branch leaves a main line.
 *
 * <p>This is the thing that makes a set of routes into a railway. Two lines that cross can share their
 * crossing blocks and need nothing built (see {@link IrTrackLine}); a branch cannot, because a train has
 * to be <em>sent</em> down it. IR models that as a {@code SWITCH}: one piece of track carrying a
 * straight through-route and a diverging curve, with the curve's anchor pointed at the straight's, which
 * is what makes IR treat the pair as one switchable unit and throw it on redstone.</p>
 *
 * <p>Three numbers decide the shape, and only one of them is a choice. The junction position and the
 * main line's heading come from the survey; the diverging curve leaves <b>tangent to the main line</b>,
 * because a turnout that left at an angle would be a kink that derails anything taking it; and the lead,
 * how far along the branch the curve runs before it is the branch, is the one dial. A short lead is a
 * sharp turnout, a long one is a fast one, exactly as on a real railway.</p>
 *
 * <p>It never breaks existing track to build. A switch laid over another line's anchor block would take
 * that whole piece of railway out and IR would not say so, so an anchor anywhere in the footprint is a
 * refusal with the position named in it.</p>
 */
public final class IrTurnout {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Sharpest turnout worth building, in degrees.
     *
     * <p>Beyond this the diverging curve is tighter than any stock can take and the switch is a
     * derailment with a lever on it. A branch surveyed to leave this sharply wants a curve of its own
     * before the junction, not a sharper switch.</p>
     */
    public static final double MAX_DIVERGENCE = 45.0;

    /** Extra blocks of straight beyond the lead, so IR has room to work out its own stub length. */
    private static final int STUB_ALLOWANCE = 4;

    /** Cells above the rail kept clear, so something can actually run through the junction. */
    private static final int HEADROOM = 3;

    /** Update and notify, so a block walled against water tells the water it is there. */
    private static final int LINING_FLAGS = 3;

    /** How far off the branch's centreline one of its own anchors may sit, in blocks. */
    private static final double RECLAIM = 4.0;

    private IrTurnout() {
    }

    /**
     * @param problem why it was not built, phrased for chat, or null when it was
     * @param divergence how far the branch leaves the main line by, in degrees
     * @param cut blocks taken out to fit the diverging curve in
     */
    public record Built(boolean done, String problem, double divergence, int cut) {

        static Built no(String problem) {
            return new Built(false, problem, 0.0, 0);
        }
    }

    /**
     * Put a turnout where a branch meets a main line.
     *
     * @param through the main line's compiled centreline
     * @param branch the branch's, which the turnout's curve ends on
     * @param lead blocks along the branch the diverging curve runs for
     */
    public static Built build(ServerLevel level, RouteMeeting meeting, Centreline through,
                              Centreline branch, int floorY, String track, double gauge, double lead) {
        if (meeting.kind() != RouteMeeting.Kind.TURNOUT) {
            return Built.no("that meeting is a " + meeting.kind().lowerName() + ", not a turnout");
        }
        RouteMeeting.Side main = meeting.through();
        RouteMeeting.Side leaving = meeting.branch();
        boolean fromStart = leaving.end() == RouteMeeting.End.START;

        AlignElement.Sample junction = through.at(main.chainage());
        double mainHeading = junction.heading();

        // Where the diverging curve ends, and which way the branch is running there. A branch that
        // finishes on the main line is the same turnout read backwards, so it is walked from its own far
        // end and its headings are reversed; everything downstream then has one case to handle.
        double reach = Math.max(4.0, Math.min(lead, branch.length()));
        AlignElement.Sample far = branch.at(fromStart ? reach : branch.length() - reach);
        double farHeading = fromStart ? far.heading() : far.heading() + Math.PI;
        AlignElement.Sample leave = branch.at(fromStart ? 0.0 : branch.length());
        double leaveHeading = fromStart ? leave.heading() : leave.heading() + Math.PI;

        // Which way the switch faces. A branch leaving backwards along the main line is a trailing
        // turnout, and the only difference is which way the straight stub runs from the junction.
        double straightHeading = Math.abs(turn(leaveHeading, mainHeading)) > Math.PI / 2.0
                ? mainHeading + Math.PI : mainHeading;
        double divergence = Math.toDegrees(turn(straightHeading, leaveHeading));
        if (Math.abs(divergence) > MAX_DIVERGENCE) {
            return Built.no(String.format(Locale.ROOT,
                    "%s leaves %s at %.0f degrees, which is sharper than a switch can be built (%.0f max);"
                            + " give the branch a curve of its own before the junction",
                    leaving.label(), main.label(), Math.abs(divergence), MAX_DIVERGENCE));
        }

        int bx = (int) Math.floor(junction.x());
        int bz = (int) Math.floor(junction.z());
        BlockPos anchor = new BlockPos(bx, floorY, bz);
        if (!level.isLoaded(anchor) || !level.isLoaded(BlockPos.containing(far.x(), floorY, far.z()))) {
            return Built.no("the ground at the junction is not loaded");
        }

        // The handles. A third of the chord at each end is the cubic whose parameter is closest to arc
        // length, and the near one points along the main line, which is what makes this a turnout at all
        // rather than a corner: a train takes the diverging road without ever changing direction.
        double handle = reach / 3.0;
        Vec3d near = relative(junction.x(), junction.z(), bx, bz);
        Vec3d nearControl = relative(junction.x() + Math.cos(straightHeading) * handle,
                junction.z() + Math.sin(straightHeading) * handle, bx, bz);
        Vec3d end = relative(far.x(), far.z(), bx, bz);
        Vec3d endControl = relative(far.x() - Math.cos(farHeading) * handle,
                far.z() - Math.sin(farHeading) * handle, bx, bz);

        RailSettings settings = IrTrackLine.settings(track, gauge).with(mutable -> {
            mutable.type = TrackItems.SWITCH;
            // IR works out the stub it actually needs from how far the curve stays inside the straight's
            // footprint, and this is only the window it is allowed to look in.
            mutable.length = (int) Math.ceil(reach) + STUB_ALLOWANCE;
        });
        // A switch has a hand, and IR asks for it: which side of the through route the branch leaves
        // on. Everything else about a turnout is symmetric, so getting it wrong is not a wrong shape,
        // it is a switch that does not know which way it is throwing.
        TrackDirection hand = divergence > 0.0 ? TrackDirection.RIGHT : TrackDirection.LEFT;
        PlacementInfo straight = new PlacementInfo(near, hand,
                TrackPieces.Piece.yawOf(straightHeading), nearControl);
        PlacementInfo diverging = new PlacementInfo(end, hand,
                (TrackPieces.Piece.yawOf(farHeading) + 180.0f) % 360.0f, endControl);

        World world = World.get(level);
        Vec3i at = new Vec3i(bx, floorY, bz);

        // Two builders of the same switch, and the second one is never asked what it is made of.
        //
        // This is the whole of why turnouts did not work, and it was never IR's fault. A switch is two
        // builders, a straight and a diverging curve, and IR points the curve's anchor at the
        // straight's so it treats the pair as one throwable unit. But BuilderSwitch.getTracksForRender
        // returns the straight's *live* track list and appends the curve's into it, so a builder that
        // has been asked once builds the curve twice - and the second pass finds a rail block already
        // there, so TrackBase.placeTrack breaks it rather than stacking on it. Breaking any tile of a
        // piece runs TileRailBase.breakParentIfExists, which sets that tile's parent block to air, and
        // the curve's parent is the straight's anchor. The switch then has twenty sleepers pointing at
        // an empty block, and IR takes the lot out a few ticks later.
        //
        // Nothing about that is visible from outside: build() returns, the log agrees, and a second
        // later there is no turnout. So the footprint is measured on a builder that is thrown away.
        BuilderBase probe = new RailInfo(settings, straight, diverging, SwitchState.NONE,
                SwitchState.NONE, 0.0).getBuilder(world, at);
        if (probe == null) {
            return Built.no("Immersive Railroading has no builder for a switch of this shape");
        }
        java.util.List<TrackBase> laid = probe.getTracksForRender();

        // The switch is the branch's own first stretch, carrying both roads. Anything the branch laid
        // there itself is taken back out before the switch goes in, because two anchors in one place is
        // a fight IR settles by refusing to place the second. Only the branch's own pieces: the main
        // line's are somebody else's problem and are refused below rather than removed.
        int relaid = IrTrackClear.remove(level, world, footprint(laid), branch, RECLAIM);
        String anchored = foreignAnchor(world, laid);
        if (anchored != null) {
            return Built.no(anchored);
        }
        Cut cut = clear(level, laid);
        if (cut.problem() != null) {
            return Built.no(cut.problem());
        }

        // The one that actually goes in the ground, untouched.
        BuilderBase builder = new RailInfo(settings, straight, diverging, SwitchState.NONE,
                SwitchState.NONE, 0.0).getBuilder(world, at);
        if (builder == null || !builder.canBuild()) {
            return Built.no(blockage(level, laid));
        }
        java.util.List<BlockPos> cells = footprint(laid);
        int before = 0;
        for (BlockPos cell : cells) {
            if (level.getBlockEntity(cell) != null) {
                before++;
            }
        }
        builder.build();
        int after = 0;
        for (BlockPos cell : cells) {
            if (level.getBlockEntity(cell) != null) {
                after++;
            }
        }
        String orphan = orphaned(world, cells);
        LOGGER.debug("[wflib] turnout at {}, {}: {} cell(s), {} carried track before, {} after{}",
                anchor.getX(), anchor.getZ(), cells.size(), before, after,
                orphan == null ? "" : ", not anchored: " + orphan);
        if (orphan != null) {
            return Built.no("Immersive Railroading laid the switch and left it unanchored (" + orphan
                    + "), which means it takes it back out again within a second."
                    + " Every sleeper of a switch points at one anchor block, so this is a switch that"
                    + " is already gone, whatever build() reported");
        }
        return new Built(true, null, divergence, cut.blocks() + relaid);
    }

    /**
     * Whether the switch would put one of its own anchors on another railway's.
     *
     * <p>Only that case. A <b>sleeper</b> of the switch laid over another line's anchor is what a shared
     * junction block is made of, and {@code BuilderBase.build} files it inside that tile rather than
     * replacing it. Two <b>anchors</b> in one block is the case IR will not do, and it is worth the
     * refusal for the message: it names the position, which turns an invisible stop into somewhere a
     * person can walk to.</p>
     */
    private static String foreignAnchor(World world, java.util.List<TrackBase> laid) {
        for (TrackBase track : laid) {
            Vec3i at = track.getPos();
            if (world.getBlockEntity(at, TileRail.class) == null || track.isOverTileRail()) {
                continue;
            }
            return "a piece of track is anchored at " + at.x + ", " + at.y + ", " + at.z
                    + ", and the switch wants its own anchor in that block;"
                    + " lay that route again so its joins fall clear of the junction";
        }
        return null;
    }

    /** @param problem why the junction cannot be cut in, or null */
    private record Cut(int blocks, String problem) {
    }

    /**
     * Take out the rock the diverging curve runs through.
     *
     * <p>A turnout leaves the main line and meets the branch, and the wedge between the two tunnels is
     * ground nobody bored: the junction has to cut its own way across. It is a handful of blocks and it
     * is bounded by the curve itself, so it can only ever open the two tunnels into each other.</p>
     *
     * <p>Fluid against the wedge is <b>walled rather than refused</b>, which is the same order the
     * tunnel keeps and for the same reason: line the tube, empty it, then open it. Refusing instead
     * sounds safer and is not - it leaves the one junction in a network unbuilt because of a block of
     * water nobody would have to dig by hand, and a junction under an aquifer is exactly the case the
     * lining pass exists for. Nothing is opened until everything against it is lining.</p>
     */
    private static Cut clear(ServerLevel level, java.util.List<TrackBase> laid) {
        java.util.List<BlockPos> opening = new java.util.ArrayList<>();
        java.util.Set<Long> inside = new java.util.HashSet<>();
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
            return new Cut(0, null);
        }
        BlockState lining = CarvePlan.stateOf(RailConfig.TUNNEL_LINING.get());

        // Pass one: the shell. Every face of the wedge that is not itself being opened, and is wet,
        // becomes lining before a single block of the wedge is taken out.
        int walled = 0;
        for (BlockPos pos : opening) {
            for (Direction face : Direction.values()) {
                BlockPos side = pos.relative(face);
                if (inside.contains(side.asLong())
                        || level.getBlockState(side).getFluidState().isEmpty()) {
                    continue;
                }
                level.setBlock(side, lining, LINING_FLAGS);
                walled++;
            }
        }

        // Pass two: the water already standing in the wedge, taken out without telling the neighbours,
        // because the tube is sealed now and there is nothing left for it to flow from.
        for (BlockPos pos : opening) {
            if (!level.getBlockState(pos).getFluidState().isEmpty()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), LINING_FLAGS);
            }
        }

        // Pass three: the rock.
        int blocks = 0;
        for (BlockPos pos : opening) {
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            level.destroyBlock(pos, false);
            blocks++;
        }
        if (walled > 0) {
            LOGGER.debug("[wflib] turnout cut {} block(s) out of the wedge, after lining {} wet face(s)",
                    blocks, walled);
        }
        return new Cut(blocks, null);
    }

    private static String blockage(ServerLevel level, java.util.List<TrackBase> laid) {
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
        return "the switch will not go in: "
                + (blocked.length() == 0 ? "no reason given" : blocked.toString());
    }

    /**
     * Whether any sleeper of the new switch points at an anchor block that is not there.
     *
     * <p>The check that turns a false success into a refusal. Every piece of IR track is one anchor
     * block plus the sleepers that point at it, and a sleeper whose anchor has gone <b>breaks itself</b>
     * a few ticks later, which takes the whole switch with it. Without this the command reports a
     * turnout built, the log agrees, and a second later there is nothing there at all.</p>
     *
     * @return the position of the missing anchor, or null when every sleeper has one
     */
    private static String orphaned(World world, java.util.List<BlockPos> cells) {
        java.util.Map<Vec3i, Integer> missing = new java.util.LinkedHashMap<>();
        int anchors = 0;
        for (BlockPos cell : cells) {
            Vec3i at = new Vec3i(cell.getX(), cell.getY(), cell.getZ());
            TileRailBase tile = world.getBlockEntity(at, TileRailBase.class);
            if (tile == null) {
                continue;
            }
            if (tile instanceof TileRail) {
                anchors++;
            }
            if (tile.getParent() == null || world.getBlockEntity(tile.getParent(), TileRail.class) != null) {
                continue;
            }
            missing.merge(tile.getParent(), 1, Integer::sum);
        }
        if (missing.isEmpty()) {
            LOGGER.debug("[wflib] turnout: {} anchor(s) over {} cell(s), every sleeper anchored",
                    anchors, cells.size());
            return null;
        }
        StringBuilder out = new StringBuilder();
        int named = 0;
        for (java.util.Map.Entry<Vec3i, Integer> entry : missing.entrySet()) {
            Vec3i at = entry.getKey();
            if (named++ > 0) {
                out.append(", ");
            }
            if (named > 3) {
                out.append("and ").append(missing.size() - 3).append(" more");
                break;
            }
            out.append(entry.getValue()).append(" sleeper(s) point at ").append(at.x).append(", ")
                    .append(at.y).append(", ").append(at.z).append(" (")
                    .append(IrTrackLine.isTrack(world, at) ? "a sleeper, not an anchor" : "nothing there")
                    .append(")");
        }
        LOGGER.warn("[wflib] turnout left unanchored: {} anchor(s) over {} cell(s); {}",
                anchors, cells.size(), out);
        return out.toString();
    }

    /** Every block the switch would occupy, which is the ground it has to be given. */
    private static java.util.List<BlockPos> footprint(java.util.List<TrackBase> laid) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (TrackBase track : laid) {
            Vec3i at = track.getPos();
            out.add(new BlockPos(at.x, at.y, at.z));
        }
        return out;
    }

    private static Vec3d relative(double x, double z, int bx, int bz) {
        return new Vec3d(x - bx, 0.0, z - bz);
    }

    /** The signed turn from one heading to another, in radians, taking the short way round. */
    private static double turn(double from, double to) {
        double delta = (to - from) % (Math.PI * 2.0);
        if (delta > Math.PI) {
            delta -= Math.PI * 2.0;
        } else if (delta < -Math.PI) {
            delta += Math.PI * 2.0;
        }
        return delta;
    }
}

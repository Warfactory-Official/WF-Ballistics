package com.wf.wflib.rail.build.ir;

import cam72cam.immersiverailroading.items.nbt.RailSettings;
import cam72cam.immersiverailroading.library.Gauge;
import cam72cam.immersiverailroading.library.SwitchState;
import cam72cam.immersiverailroading.library.TrackDirection;
import cam72cam.immersiverailroading.library.TrackItems;
import cam72cam.immersiverailroading.library.TrackPositionType;
import cam72cam.immersiverailroading.library.TrackSmoothing;
import cam72cam.immersiverailroading.registry.DefinitionManager;
import cam72cam.immersiverailroading.tile.TileRail;
import cam72cam.immersiverailroading.tile.TileRailBase;
import cam72cam.immersiverailroading.track.BuilderBase;
import cam72cam.immersiverailroading.track.TrackBase;
import cam72cam.immersiverailroading.util.PlacementInfo;
import cam72cam.immersiverailroading.util.RailInfo;
import cam72cam.mod.item.ItemStack;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.world.World;
import com.mojang.logging.LogUtils;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeeting;
import com.wf.wflib.rail.build.TrackLine;
import com.wf.wflib.rail.build.TrackPieces;
import com.wf.wflib.rail.excavate.CarveVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Real Immersive Railroading track, built from a surveyed route.
 *
 * <p><b>IR track is not a block.</b> It is a chain of cubic curves registered as tile entities that
 * stock is positioned along, which is why TrackAPI is a separate mod and why anything written with
 * {@code setBlock} would look like a railway and be unrunnable. So this does not place blocks at all:
 * it hands IR the same four numbers its own track tool does - two points and a tangent handle at each -
 * and lets IR's builder lay the ties.</p>
 *
 * <p>The handles are given explicitly rather than left to IR to guess from the end yaws. IR's guess is
 * a fraction of the chord scaled by a "curvosity" dial, which is the right answer for someone dragging
 * a curve out by eye and the wrong one for a curve that already has a radius the surveyor chose: given
 * the handles, the piece IR builds is the arc the alignment specifies, to under a hundredth of a
 * block.</p>
 *
 * <p>A piece is laid only once the face has passed its <em>far</em> end. A cubic needs both of its
 * endpoints to be standing in finished tunnel, and one laid early would be a curve into rock.</p>
 *
 * <p>This class names IR types in its signatures, so it is only ever loaded when IR is installed. See
 * {@link com.wf.wflib.rail.build.TrackLines}, which is the only thing allowed to mention it.</p>
 */
public final class IrTrackLine implements TrackLine {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** IR's own name for its stock track definition, which every install has. */
    public static final String DEFAULT_TRACK = "default";

    /**
     * How much of an end piece may be given up to get the rest of it laid, in blocks.
     *
     * <p>A route's own last block is as likely as not to be part of the wall that seals its portal, and
     * IR will not build into one. Rather than a margin left off every route whether it needs one or not,
     * an end piece that is refused is tried again a little shorter. A route with clear ends loses
     * nothing; one that ends in a wall loses the half block it has to.</p>
     */
    static final double MAX_TRIM = 2.0;

    /** How much shorter each attempt is. Half a block, because a block is where the wall is. */
    static final double TRIM_STEP = 0.5;

    /**
     * How far apart two pieces' endpoints may be and still be the same piece, in blocks.
     *
     * <p>Used to tell "this route is being laid again over itself" from "there is somebody else's
     * railway here", which look identical from the outside and could not be more different.</p>
     */
    private static final double SAME_PIECE = 1.5;

    /** How near a meeting a piece has to be for the crossing rules to be the ones that apply. */
    private static final double MEETING_REACH = 16.0;

    /** Returned by a build attempt when the ground simply is not loaded yet, which is not a refusal. */
    private static final String WAITING = "waiting for the ground";

    private final Centreline centreline;
    private final TrackPieces pieces;
    private final int floorY;
    /**
     * The inside of the tunnel, or null off the surface.
     *
     * <p>What the layer is allowed to clear out of its own way. Gravel falls into a finished bore after
     * the machine has passed, and IR refuses to lay track into it, which is the correct answer to the
     * wrong question: the spoil is the tunneller's to shift. Bounded by the bore so that clearing can
     * never take out a course of lining and open the tunnel to whatever it was holding back.</p>
     */
    private final CarveVolume inside;
    /** Corridor chainage to centreline chainage, because the tunnel and the survey measure differently. */
    private final double scale;
    private final RailSettings settings;
    /** Where other routes meet this one, so a refusal there can say whose railway is in the way. */
    private final List<RouteMeeting> meetings;
    private final UUID routeId;

    private int cursor;
    private int built;
    /** Pieces taken as already on the ground, which a resumed route must not try to lay again. */
    private int skipped;
    private int refused;
    private int cleared;
    private int crossed;
    private double trimmed;
    /** What the line is currently stuck on, so it is counted and logged once rather than every tick. */
    private String stuck;

    public IrTrackLine(Centreline centreline, TrackPieces pieces, int floorY, double corridorLength,
                       String track, double gauge, CarveVolume inside, List<RouteMeeting> meetings,
                       UUID routeId) {
        this.centreline = centreline;
        this.pieces = pieces;
        this.floorY = floorY;
        this.inside = inside;
        this.scale = corridorLength <= 0.0 ? 1.0 : pieces.length() / corridorLength;
        this.settings = settings(track, gauge);
        this.meetings = meetings == null ? List.of() : List.copyOf(meetings);
        this.routeId = routeId;
    }

    /**
     * The fixed half of every piece: what the track is made of, rather than where it goes.
     *
     * <p>{@code CUSTOM} for all of it, straights included. IR has a {@code STRAIGHT} type that takes a
     * length and a yaw, but it snaps to the block grid it was placed on, and a surveyed line runs at
     * whatever angle the surveyor chose. A cubic whose handles happen to be collinear <em>is</em> a
     * straight, and one code path that is always right beats two that disagree at the join.</p>
     */
    static RailSettings settings(String track, double gauge) {
        return new RailSettings(
                Gauge.from(gauge),
                known(track),
                TrackItems.CUSTOM,
                0,
                0.0f,
                1.0f,
                TrackPositionType.SMOOTH,
                TrackSmoothing.BOTH,
                TrackDirection.NONE,
                // No ballast bed and no fill: the tunnel floor is already the lining, and a machine
                // that placed gravel over it would be undoing the thing that keeps the water out.
                ItemStack.EMPTY, ItemStack.EMPTY,
                false,
                false,
                1, 1);
    }

    /**
     * Resolve a track definition against what this install actually has.
     *
     * <p>IR's ids are resource paths - {@code immersiverailroading:track/default.json} - and nobody
     * types that into a config file. An exact id wins; otherwise the shortest id that ends in the name
     * given, so {@code default} finds the default track and {@code concrete} finds the concrete one.
     * A name that matches nothing falls back to whatever this install lists first, with a line in the
     * log naming the alternatives, because a track definition that does not exist is a railway that
     * never gets built.</p>
     */
    static String known(String track) {
        List<String> ids = DefinitionManager.getTrackIDs();
        if (ids == null || ids.isEmpty()) {
            return track == null ? DEFAULT_TRACK : track;
        }
        if (track != null && ids.contains(track)) {
            return track;
        }
        String want = track == null ? DEFAULT_TRACK : track;
        String best = null;
        for (String id : ids) {
            if (matches(id, want) && (best == null || id.length() < best.length())) {
                best = id;
            }
        }
        if (best != null) {
            return best;
        }
        LOGGER.warn("[wflib] no Immersive Railroading track definition matching '{}'; using '{}'."
                + " This install has: {}", want, ids.get(0), ids);
        return ids.get(0);
    }

    /** Whether an IR track id is the one this name is asking for, ignoring its path and extension. */
    private static boolean matches(String id, String want) {
        int slash = id.lastIndexOf('/');
        String leaf = slash < 0 ? id : id.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        if (dot > 0) {
            leaf = leaf.substring(0, dot);
        }
        return leaf.equalsIgnoreCase(want);
    }

    @Override
    public int layTo(ServerLevel level, double chainage) {
        double reach = chainage >= Double.MAX_VALUE / 2.0 ? Double.MAX_VALUE : chainage * this.scale;
        World world = World.get(level);
        List<TrackPieces.Piece> all = this.pieces.pieces();
        int done = 0;
        while (this.cursor < all.size() && all.get(this.cursor).to() <= reach) {
            if (!lay(level, world, all, this.cursor)) {
                // The ground is not ready, or something is standing where the ties go. Stop rather than
                // skipping: a railway with one piece missing out of the middle is not a railway.
                break;
            }
            this.cursor++;
            done++;
        }
        return done;
    }

    @Override
    public int skipTo(double chainage) {
        double reach = chainage >= Double.MAX_VALUE / 2.0 ? Double.MAX_VALUE : chainage * this.scale;
        List<TrackPieces.Piece> all = this.pieces.pieces();
        int was = this.cursor;
        while (this.cursor < all.size() && all.get(this.cursor).to() <= reach) {
            this.cursor++;
        }
        this.skipped += this.cursor - was;
        return this.cursor - was;
    }

    /** Lay one piece, shortening it if it is an end piece and the full length will not go in. */
    private boolean lay(ServerLevel level, World world, List<TrackPieces.Piece> all, int index) {
        TrackPieces.Piece piece = all.get(index);
        String problem = build(level, world, piece);
        if (problem == null) {
            this.stuck = null;
            if (meetingNear(piece) != null) {
                this.crossed++;
            }
            return true;
        }
        if (WAITING.equals(problem)) {
            return false;
        }
        boolean atStart = index == 0;
        boolean atEnd = index == all.size() - 1;
        if (atStart || atEnd) {
            for (double trim = TRIM_STEP; trim <= MAX_TRIM + 1.0E-9; trim += TRIM_STEP) {
                double near = atStart ? trim : 0.0;
                double far = atEnd ? trim : 0.0;
                TrackPieces.Piece shorter =
                        TrackPieces.cut(this.centreline, piece.from() + near, piece.to() - far);
                if (shorter == null) {
                    break;
                }
                if (build(level, world, shorter) == null) {
                    this.trimmed += near + far;
                    this.stuck = null;
                    if (meetingNear(piece) != null) {
                        this.crossed++;
                    }
                    return true;
                }
            }
        }
        if (!problem.equals(this.stuck)) {
            this.stuck = problem;
            this.refused++;
            LOGGER.warn("[wflib] Immersive Railroading will not lay the piece at chainage {}: {}",
                    String.format(Locale.ROOT, "%.0f", piece.from()), problem);
        }
        return false;
    }

    /**
     * Hand IR one piece and let it lay the ties.
     *
     * @return null when it went in, {@link #WAITING} when the ground is not here yet, otherwise why not
     */
    private String build(ServerLevel level, World world, TrackPieces.Piece piece) {
        int bx = (int) Math.floor(piece.x1());
        int bz = (int) Math.floor(piece.z1());
        // Both ends, because a piece is up to two dozen blocks long and IR will read every block
        // between them. Asking about an unloaded chunk loads it on the server thread, which is the
        // one thing a machine laying track a piece at a time must never do.
        if (!level.isLoaded(new BlockPos(bx, this.floorY, bz))
                || !level.isLoaded(BlockPos.containing(piece.x2(), this.floorY, piece.z2()))) {
            return WAITING;
        }
        Vec3i pos = new Vec3i(bx, this.floorY, bz);

        // Everything IR is given is relative to that block, endpoints and handles alike: its curve
        // solver takes the difference of the two placement positions and measures the handles from the
        // near one, so mixing frames here is the one mistake that produces track in the wrong place
        // while every number still looks plausible.
        PlacementInfo near = new PlacementInfo(relative(piece.x1(), piece.z1(), bx, bz),
                TrackDirection.NONE, piece.nearYaw(), relative(piece.c1x(), piece.c1z(), bx, bz));
        PlacementInfo far = new PlacementInfo(relative(piece.x2(), piece.z2(), bx, bz),
                TrackDirection.NONE, piece.farYaw(), relative(piece.c2x(), piece.c2z(), bx, bz));

        RailInfo info = new RailInfo(this.settings, near, far, SwitchState.NONE, SwitchState.NONE, 0.0);
        BuilderBase builder = info.getBuilder(world, pos);
        if (builder == null) {
            return "Immersive Railroading has no builder for this shape";
        }
        // Let this piece lay its sleepers over another line's. IR keeps the tile it covers inside the
        // new one in a "replaced" chain and a moving train is then offered both paths and takes
        // whichever matches the way it is going, which is precisely what a diamond crossing is. It only
        // ever applies to gags: a parent block is never flexible, and that case is handled below.
        builder.overrideFlexible = true;
        // Asked of every piece, and asked before anything is built rather than only when IR says it
        // cannot. IR will happily lay a gag over another line's anchor block and break the anchor as it
        // does, which deletes that whole piece of the other railway and reports nothing at all: by the
        // time canBuild() has a complaint it is already too late to have this one.
        String anchored = foreignAnchor(world, builder, piece);
        if (anchored != null) {
            return anchored;
        }
        if (!builder.canBuild()) {
            String problem = makeRoom(level, world, builder, piece);
            if (problem != null) {
                return problem;
            }
            if (!builder.canBuild()) {
                return blockage(level, builder, piece);
            }
        }
        builder.build();
        this.built++;
        return null;
    }

    /**
     * Shift whatever has settled where the ties go, and say what stopped it if anything did.
     *
     * <p>Two quite different things can be in the way and they need opposite answers. <b>Spoil</b> is
     * gravel that has fallen into a finished bore after the machine passed, and it is the tunneller's to
     * move; it is cleared, and only ever inside the bore, so clearing can never take out a course of
     * lining. <b>Another railway's anchor block</b> is not, and IR would break it without a word,
     * taking that whole piece of the other line with it. The one exception is this route's own previous
     * attempt at this very piece, which is what a re-lay of an existing line is made of.</p>
     *
     * @return null when there is now room, or why there is not
     */
    private String makeRoom(ServerLevel level, World world, BuilderBase builder,
                            TrackPieces.Piece piece) {
        for (TrackBase track : builder.getTracksForRender()) {
            if (track.canPlaceTrack()) {
                continue;
            }
            Vec3i at = track.getPos();
            TileRail rail = world.getBlockEntity(at, TileRail.class);
            if (rail != null) {
                if (!samePiece(rail, at, builder)) {
                    // Somebody else's anchor, and not ours to break. It reaches here rather than being
                    // refused above only when this piece's own anchor is elsewhere and IR still will
                    // not place a sleeper here, which is a refusal with a position in it.
                    return blockage(level, builder, piece);
                }
                // This route's own earlier attempt at this very piece. Taking it out is what lets a
                // line be laid again after the first pass was stopped halfway down it.
                level.destroyBlock(new BlockPos(at.x, at.y, at.z), false);
                this.cleared++;
                continue;
            }
            if (this.inside == null || !this.inside.contains(at.x, at.y, at.z)) {
                // Outside the tunnel: not ours to clear, and refusing is the right answer.
                return blockage(level, builder, piece);
            }
            BlockPos block = new BlockPos(at.x, at.y, at.z);
            if (!level.getBlockState(block).isAir()) {
                level.destroyBlock(block, false);
                this.cleared++;
            }
        }
        return null;
    }

    /**
     * Whether this piece would put its own anchor on another railway's.
     *
     * <p>Only that case, and the narrowing is deliberate. A <b>sleeper</b> laid over another line's
     * anchor is not destructive: {@code BuilderBase.build} spots it with {@code isOverTileRail} and
     * files this piece's sleeper <em>inside</em> the other tile's {@code replaced} chain, which is
     * exactly what a shared junction block is made of. Two <b>anchors</b> in one block is the case IR
     * cannot resolve - it would break the one already there - and IR refuses it itself, because an
     * anchor tile is never flexible. Refusing it here as well is worth it only for the message: it
     * names the position, which turns an invisible stop into somewhere a person can walk to.</p>
     *
     * @return why this piece cannot go in, or null when nothing is in the way
     */
    private String foreignAnchor(World world, BuilderBase builder, TrackPieces.Piece piece) {
        for (TrackBase track : builder.getTracksForRender()) {
            Vec3i at = track.getPos();
            TileRail rail = world.getBlockEntity(at, TileRail.class);
            if (rail == null || track.isOverTileRail() || samePiece(rail, at, builder)) {
                continue;
            }
            RouteMeeting meeting = meetingNear(piece);
            return "another railway is anchored at " + at.x + ", " + at.y + ", " + at.z
                    + (meeting == null ? "" : " (" + meeting.otherThan(this.routeId).label() + ")")
                    + ", and this piece wants its own anchor in that block;"
                    + " lay that route again so its joins fall clear of the meeting";
        }
        return null;
    }

    /**
     * Whether an existing anchor block is this very piece, laid before.
     *
     * <p>Compared as the two curve endpoints in world coordinates, which is the only thing that
     * distinguishes a second pass over one's own route from somebody else's siding running through it.
     * Both look like an immovable rail block from every other angle.</p>
     */
    private static boolean samePiece(TileRail rail, Vec3i at, BuilderBase builder) {
        RailInfo theirs = rail.info;
        if (theirs == null) {
            return false;
        }
        Vec3d here = new Vec3d(at);
        Vec3d ours = new Vec3d(builder.pos);
        return theirs.placementInfo.placementPosition.add(here)
                .distanceTo(builder.info.placementInfo.placementPosition.add(ours)) < SAME_PIECE
                && theirs.customInfo.placementPosition.add(here)
                .distanceTo(builder.info.customInfo.placementPosition.add(ours)) < SAME_PIECE;
    }

    /** The meeting this piece runs through, or null when it meets nothing. */
    private RouteMeeting meetingNear(TrackPieces.Piece piece) {
        for (RouteMeeting meeting : this.meetings) {
            RouteMeeting.Side side = meeting.sideOf(this.routeId);
            if (side != null && side.chainage() >= piece.from() - MEETING_REACH
                    && side.chainage() <= piece.to() + MEETING_REACH) {
                return meeting;
            }
        }
        return null;
    }

    private static Vec3d relative(double x, double z, int bx, int bz) {
        return new Vec3d(x - bx, 0.0, z - bz);
    }

    /**
     * Say what is standing where a piece of track wants to go.
     *
     * <p>A refusal is otherwise the most opaque failure this feature has: the machine keeps digging, the
     * tunnel finishes, and the railway is simply not there. Naming the block and the position turns that
     * into something a person can walk to.</p>
     */
    private String blockage(ServerLevel level, BuilderBase builder, TrackPieces.Piece piece) {
        StringBuilder blocked = new StringBuilder();
        int named = 0;
        for (TrackBase track : builder.getTracksForRender()) {
            if (track.canPlaceTrack() || named >= 4) {
                continue;
            }
            Vec3i at = track.getPos();
            BlockPos pos = new BlockPos(at.x, at.y, at.z);
            blocked.append(named == 0 ? "" : ", ").append(pos.toShortString()).append(" is ")
                    .append(level.getBlockState(pos).getBlock().getName().getString())
                    .append(track.isDownSolid(true) ? "" : " (and nothing solid under it)");
            named++;
        }
        RouteMeeting meeting = meetingNear(piece);
        return (blocked.length() == 0 ? "no reason given" : blocked.toString())
                + (meeting == null ? "" : "; " + meeting.otherThan(this.routeId).label()
                        + " meets this route here");
    }

    /** @return whether a tile at this position belongs to any Immersive Railroading track. */
    static boolean isTrack(World world, Vec3i at) {
        return world.getBlockEntity(at, TileRailBase.class) != null;
    }

    @Override
    public int laid() {
        return this.cursor;
    }

    @Override
    public int total() {
        return this.pieces.pieces().size();
    }

    @Override
    public String kind() {
        return "Immersive Railroading track";
    }

    @Override
    public BlockPos railhead() {
        List<TrackPieces.Piece> all = this.pieces.pieces();
        if (all.isEmpty()) {
            return BlockPos.ZERO;
        }
        TrackPieces.Piece piece = all.get(Math.max(0, Math.min(this.cursor, all.size() - 1)));
        return BlockPos.containing(piece.x1(), this.floorY, piece.z1());
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%d IR piece(s)%s%s%s%s%s", this.built,
                this.skipped > 0 ? " (" + this.skipped + " already there)" : "",
                this.crossed > 0 ? ", " + this.crossed + " over a meeting with another route" : "",
                this.cleared > 0 ? ", " + this.cleared + " block(s) shifted off the invert" : "",
                this.trimmed > 0.0
                        ? String.format(Locale.ROOT, ", %.1f blocks trimmed at the portals", this.trimmed)
                        : "",
                this.refused > 0 ? ", " + this.refused + " refused (something in the way)" : "");
    }
}

package com.wf.wflib.rail.build;

import com.wf.wflib.compat.TerritoryVerdict;
import com.wf.wflib.rail.TunnelBuilder;
import com.wf.wflib.rail.TunnelTerritory;
import com.wf.wflib.rail.align.Alignment;
import com.wf.wflib.rail.align.AlignmentEdits;
import com.wf.wflib.rail.align.AlignmentService;
import com.wf.wflib.rail.align.AlignmentStore;
import com.wf.wflib.rail.align.Centreline;
import com.wf.wflib.rail.align.RouteMeetings;
import com.wf.wflib.rail.excavate.CarvePlan;
import com.wf.wflib.rail.excavate.CarveVolume;
import com.wf.wflib.rail.excavate.TunnelProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Lays the track along a route that has already been dug, a few pieces per tick.
 *
 * <p>The gang that follows the tunnel. It is its own job rather than a fifth pass of the carve because
 * a rail is not a volume: the carve asks "is this cell one of yours" of every block in a box, and track
 * is a chain in which each piece's shape is decided by the two either side of it. Running it after the
 * bore is not a convenience either, since a boring pass over a cell that already has a rail in it takes
 * the rail straight back out.</p>
 *
 * <p>It places blocks in loaded chunks with no block-place event behind it, exactly as the excavator
 * does, so it asks about territory itself for every chunk it reaches.</p>
 */
public final class TrackJob {

    /** Pieces laid per tick. A couple of hundred blocks of railway in a handful of ticks. */
    private static final int BUDGET = 64;

    /** Ticks spent waiting for ground that never arrives before the job gives up. */
    private static final int MAX_STALL = 600;

    /** Blocks ahead of the railhead to ask for, so the next chunk is on its way. */
    private static final int LOOKAHEAD = 32;

    private final UUID routeId;
    private final String routeName;
    private final UUID faction;
    private final CarveVolume.Corridor path;
    private final TrackLine line;
    private final int floorY;
    private final double routeLength;
    private final Set<Long> cleared = new HashSet<>();

    private int stalled;
    private String halted;

    private TrackJob(Alignment route, Centreline centreline, CarveVolume.Corridor path,
                     TrackLine line, int floorY, UUID faction) {
        this.routeId = route.id();
        this.routeName = route.name().isEmpty() ? "unnamed route" : route.name();
        this.faction = faction;
        this.path = path;
        this.line = line;
        this.floorY = floorY;
        this.routeLength = centreline.length();
    }

    /** @return a job, or null when the route has no length to lay track on. */
    public static TrackJob on(ServerLevel level, Alignment route, TunnelProfile profile, String bed,
                              int floorY, int boostSpacing, UUID faction) {
        Centreline centreline = route.compile().centreline();
        if (centreline.length() <= 0.0) {
            return null;
        }
        CarveVolume.Corridor path = TunnelBuilder.corridor(centreline, 0.0, centreline.length(),
                profile, floorY);
        // Where other routes cross or branch off this one, so the joins between pieces land on the
        // meetings rather than across them. See TrackPieces: a join at a meeting is what leaves nothing
        // but gags where two railways share their ground.
        var meetings = RouteMeetings.on(AlignmentStore.of(level).all(), route.id());
        // The bore the track runs in, so the layer may shift what has fallen into it and nothing else.
        // Open at a junction, because the block two routes share is inside both of their tunnels.
        CarveVolume tunnel = TunnelBuilder.sweep(path, profile, TunnelProfile.Kind.BORE, floorY,
                meetings, route.id());
        TrackLine line = TrackLines.on(level, route.id(), centreline, path, floorY,
                CarvePlan.stateOf(bed), boostSpacing, tunnel, meetings);
        return line.total() == 0 ? null
                : new TrackJob(route, centreline, path, line, floorY, faction);
    }

    public String routeName() {
        return this.routeName;
    }

    public UUID routeId() {
        return this.routeId;
    }

    public String halted() {
        return this.halted;
    }

    public int pieces() {
        return this.line.total();
    }

    /** @return what kind of railway this gang is laying. */
    public String kind() {
        return this.line.kind();
    }

    /** @return whether there is more to lay. */
    public boolean tick(ServerLevel level) {
        if (this.halted != null) {
            return false;
        }
        BlockPos head = this.line.railhead();
        BoundingBox around = new BoundingBox(head.getX(), this.floorY - 1, head.getZ(),
                head.getX(), this.floorY, head.getZ());
        if (!ChunkHold.hold(level, around, LOOKAHEAD)) {
            if (++this.stalled > MAX_STALL) {
                return fail(level, "the ground never loaded");
            }
            return true;
        }
        this.stalled = 0;
        if (!mayLay(level, new ChunkPos(head.getX() >> 4, head.getZ() >> 4))) {
            return false;
        }
        int before = this.line.laid();
        this.line.layTo(level, reachFor(before + BUDGET));
        if (this.line.laid() < this.line.total()) {
            // Either the budget ran out or something is in the way; either way, again next tick.
            return this.line.laid() > before || ++this.stalled <= MAX_STALL;
        }
        report(level);
        return false;
    }

    private boolean mayLay(ServerLevel level, ChunkPos chunk) {
        if (!this.cleared.add(chunk.toLong())) {
            return true;
        }
        TerritoryVerdict verdict = TunnelTerritory.verdict(level, this.faction, chunk, this.floorY);
        if (verdict.allowed()) {
            return true;
        }
        return fail(level, "chunk " + chunk.x + ", " + chunk.z + ": " + verdict.reason());
    }

    /** @return false always, so a caller can {@code return fail(...)} and be finished. */
    private boolean fail(ServerLevel level, String why) {
        this.halted = why;
        report(level);
        return false;
    }

    /**
     * How far along the corridor to let the line lay, to put down about this many pieces.
     *
     * <p>A budget in pieces rather than in blocks, because a piece of IR track is twenty-odd blocks and
     * a piece of vanilla rail is one. Converting through the fraction of the line already down keeps
     * one number meaningful for both.</p>
     */
    private double reachFor(int pieces) {
        int total = Math.max(1, this.line.total());
        return this.path.length() * Math.min(1.0, pieces / (double) total) + 1.0;
    }

    /** The route carries track between here and there now, so it says so. */
    private void report(ServerLevel level) {
        if (this.line.laid() <= 0) {
            return;
        }
        double fraction = this.line.laid() / (double) Math.max(1, this.line.total());
        double to = fraction * this.routeLength;
        AlignmentStore store = AlignmentStore.of(level);
        Alignment current = store.get(this.routeId);
        if (current == null) {
            return;
        }
        store.put(AlignmentEdits.applyBuild(current, 0.0, to, true, this.routeLength));
        AlignmentService.pushAll(level);
    }

    public String summary() {
        return String.format(Locale.ROOT, "%d of %d piece(s) of %s, %s%s",
                this.line.laid(), this.line.total(), this.line.kind(), this.line.describe(),
                this.halted == null ? "" : ", stopped: " + this.halted);
    }

    /** Where the railhead has got to, for a machine that wants to be told. */
    public BlockPos railhead() {
        return this.line.railhead();
    }
}
